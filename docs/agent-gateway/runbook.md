# Agent Gateway 运维与故障排查 Runbook

本文面向当前 A2A4J Agent Gateway MVP，内容以现有代码、默认 Starter Bean 和 sample 配置为准。配置项详见
[configuration.md](./configuration.md)，API 与错误码详见 [api-reference.md](./api-reference.md)，尚未实现的运维能力见
[mvp-backlog.md](./mvp-backlog.md)。

## 1. 已知运维边界

- `TaskRouteStore`、`IdempotencyStore`、Agent 注册表、事件队列、流并发计数和熔断状态默认都在单进程内存中；重启后不可恢复，也不在多副本间共享。
- Card 主动探测是拉取并校验 Agent Card，不会额外调用上游 `/health`。
- Starter 提供 `gatewayAgent`、`gatewayDependency` 两个 HealthIndicator，但不会自动修改 readiness/liveness 分组。
- 默认没有 Agent health、route decision、active request、协议转换或 circuit state 指标，也没有连续探测失败次数查询接口。
- 默认没有自动创建 OpenTelemetry span；只校验并透传合法的 W3C `traceparent`/`tracestate`。

不要把上述未提供的信息写成告警或排障步骤。需要这些能力时，应先接入自定义指标、持久化 Store 或追踪后端。

## 2. 先建立请求关联

1. 从响应头读取 `X-Gateway-Request-Id`。调用方传入非空值时 Gateway 原样复用；未传时 Gateway 生成 UUID，并在响应头返回。
2. 若请求携带合法 W3C version 00 `traceparent`，从中取得 trace ID；Gateway 会把合法的 `traceparent` 和 `tracestate` 透传给上游。
3. 用 request ID 对照结构化审计记录。默认审计字段包括 `requestId`、`traceId`、`tenantId`、`principalId`、
   `operation`、`agentId`、`skillId`、`outcome`、`gatewayTaskId`、`latencyBucket` 和 `errorCode`；部分字段可为空。

不要把 Token、API Key、消息正文、文件内容或完整 URL 放入工单、日志搜索条件或指标标签。

## 3. 首次分诊

| 现象 | 当前可查证据 | 常见原因 |
| --- | --- | --- |
| 400 | A2A error code、`gateway.protocol.errors` | Header/参数/版本错误，Binding 或能力不支持 |
| 401/403 | 审计 `operation/outcome/errorCode`、认证配置 | API Key/JWT 无效，issuer/audience/时间 Claim 或 Agent/Skill 权限不符 |
| 404 | error code、Agent 目录、Task Route Store 指标 | Agent/Skill/Task 不存在，Task 路由过期、淘汰或进程已重启 |
| 409 | error code、幂等 Store 指标 | 路由选择不唯一，或同一幂等键用于不同请求/仍在处理中 |
| 413 | error code、请求体和 Card 大小配置 | 请求体或 Agent Card 超出上限 |
| 429 | `gateway.streams.started`、租户流量 | 当前租户 SSE 并发达到 `max-concurrent-streams`；普通非流式请求不受该限制 |
| 502 | error code、上游响应 | 上游协议、状态码、Content-Type 或响应体不符合 A2A |
| 503 | `gatewayDependency`、Agent 目录、error code | 无健康实例、连接/响应超时、熔断拒绝或写请求结果未知 |
| SSE 建连失败 | 建连 HTTP 状态与 Content-Type、`gateway.protocol.errors` | 上游在首帧前返回 4xx/5xx 或协议错误 |
| SSE 建连后中断 | `gateway.stream.duration`、上下游连接日志 | 空闲超时、客户端取消、上游异常或终态事件 |

SSE 首帧提交前发生错误时，Gateway 应返回普通 HTTP+JSON 或 JSON-RPC error envelope，而不是
`200 text/event-stream` 中的 `data: {"error": ...}`。若观察到后者，应按协议转换缺陷处理。

## 4. 健康检查

### 4.1 推荐 Actuator 配置

sample 已开启 Spring Boot 原生 probes，但生产应用若要让 readiness 表示“至少有一个健康上游”，应显式配置：

```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
  endpoint.health.group.readiness.include: readinessState,gatewayDependency
  endpoint.health.group.liveness.include: livenessState
```

不要把 `gatewayAgent` 加入 readiness：它会在任意实例 `UNHEALTHY` 时返回 `DOWN`，即使仍有其他健康实例可承载流量。
`show-details: always` 只适合受控环境；生产环境应限制 Actuator 网络可达性和详情暴露。

### 4.2 检查顺序与语义

1. `GET /actuator/health/liveness`：由 Spring Boot `livenessState` 提供；失败时检查进程、事件循环和 JVM。
2. `GET /actuator/health/readiness`：只有应用按上例配置后才包含 `gatewayDependency`。
3. `GET /actuator/health/gatewayDependency`：有至少一个 `HEALTHY` 实例为 `UP`；实例存在但无健康实例为
   `OUT_OF_SERVICE`；注册表为空为 `UNKNOWN`。
4. `GET /actuator/health/gatewayAgent`：任一 `UNHEALTHY` 为 `DOWN`，否则任一 `DEGRADED` 为
   `OUT_OF_SERVICE`，全健康为 `UP`，注册表为空为 `UNKNOWN`。
5. 调用 `GET /gateway/v1/agents` 或 `GET /gateway/v1/agents/{agentId}`，核对实例的 `healthStatus`、
   `lastCheckedAt`、`cardUrl`、`supportedInterfaces` 和权重。目录 API 需要认证。

若状态异常，检查 Card URL 的 DNS/TLS/网络策略、SSRF allowlist、`card-timeout`、`max-card-bytes`、Card 中
`protocolVersion: "1.0"` 以及 endpoint/binding。当前目录和健康端点不提供连续失败次数。

## 5. 指标检查

存在 Micrometer `MeterRegistry` 时，默认指标如下：

| 指标 | 类型/标签 | 用途 |
| --- | --- | --- |
| `gateway.requests.total` | Counter；`operation/protocol/status/error` | 非流式请求结果与错误分布 |
| `gateway.request.duration` | Timer；同上 | 非流式请求耗时 |
| `gateway.streams.started` | Counter；`operation/protocol/status/error` | 已开始的流数量，不是当前活跃流数量 |
| `gateway.stream.duration` | Timer；同上 | 流生命周期与结束状态 |
| `gateway.protocol.errors` | Counter；`operation/protocol/error` | 入站解析/协议层错误 |
| `gateway.store.entries` | Gauge；`store=task-route\|idempotency` | 默认内存 Store 当前条目数 |
| `gateway.store.capacity` | Gauge；同上 | 默认内存 Store 容量 |
| `gateway.store.evictions` | FunctionCounter；同上 | 容量淘汰累计值 |
| `gateway.store.expired` | FunctionCounter；同上 | TTL 过期累计值 |

`status` 的常见值为 `SUCCESS`、`ERROR`、`STARTED`、`COMPLETED`；`error` 为具体
`GatewayForwardingException` code、`UNEXPECTED` 或 `NONE`。Prometheus 会把点转换成下划线。

重点关注 Store 的 `entries / capacity`、`evictions` 和 `expired` 增长。默认没有 active-stream、Agent health、
路由决策或熔断状态指标，不能从 `gateway.streams.started` 直接推导当前并发数。

## 6. 路由、任务和幂等问题

### 6.1 路由失败或无健康实例

1. 用同一认证身份查询 Agent 目录，确认目标 Agent/Skill 对当前租户可见。
2. 核对路由提示优先级：已有 Task Route → Agent 路径 → `X-A2A-Target-Agent` →
   `X-A2A-Target-Skill` → 租户默认 Agent。
3. 核对所选实例的健康状态及 Card 接口。任务后续操作固定到创建任务时的实例和 Binding，不会因另一个实例健康而迁移。
4. 熔断状态仅存在于当前进程的负载均衡器中，默认无查询端点或指标；只能结合 503、上游失败和恢复时间间接判断。

### 6.2 `TASK_NOT_FOUND`

JSON-RPC `GetTask`、`CancelTask`、`SubscribeToTask` 的 `params.id` 必须是 Gateway 返回的 Task ID；JSON-RPC
顶层 `id` 只是请求关联 ID。HTTP+JSON 使用路径中的 Gateway Task ID。

按以下顺序检查：

1. 从 `SendMessage` 的 `result.task.id`，或流式首帧 `result.task.id` 取得 Gateway Task ID。
2. 使用创建任务时相同租户的 API Key/JWT。跨租户访问刻意返回相同的 not-found 结果。
3. 确认 Gateway 未重启，任务未超过 `task-route-ttl`（默认 24h），且没有因
   `task-route-max-entries`（默认 10000）达到上限而被淘汰。
4. 不要使用直接调用上游 Agent 得到的内部 Task ID。

```json
{"jsonrpc":"2.0","id":"get-001","method":"GetTask","params":{"id":"<result.task.id>"}}
```

### 6.3 重复任务或结果未知

- 同租户、同操作和同请求内容应复用同一个 `Idempotency-Key`；同键不同请求内容返回冲突。
- `OUTCOME_UNKNOWN` 表示写请求已发送，但 Gateway 无法确认上游是否执行成功。不要自动换实例重试；先用已知 Gateway Task ID
  查询，无法确认时由业务方决定补偿。
- 检查 `gateway.store.evictions{store="idempotency"}` 和 `gateway.store.expired{store="idempotency"}`；进程重启也会清空记录。

## 7. 流式发送和任务订阅

### 7.1 `SendStreamingMessage`

A2A 1.0 任务生命周期流的首帧是完整 `Task`，后续才是 `TaskStatusUpdateEvent`、
`TaskArtifactUpdateEvent` 或 `Message`。JSON-RPC 下分别读取 `result.task.id`、
`result.statusUpdate.taskId` 和 `result.artifactUpdate.taskId`。所有任务事件中的 Gateway Task/Context ID 应保持一致。

### 7.2 `SubscribeToTask`

- 活动任务的订阅首帧是当前完整 `Task`，后续事件中的缺失 `contextId` 会由服务端补齐。
- `WORKING`、`SUBMITTED`、`INPUT_REQUIRED`、`AUTH_REQUIRED` 等非终态允许订阅。
- `COMPLETED`、`FAILED`、`CANCELED`、`REJECTED` 等终态任务不再建立成功 SSE，返回
  `UNSUPPORTED_OPERATION`（JSON-RPC `-32004` / HTTP 400）；读取最终状态应使用 `GetTask`。
- 若希望“发送后再订阅”，使用 `SendMessage` 且设置 `configuration.returnImmediately=true`，收到活动 Task 后立即调用
  `SubscribeToTask`。该配置不属于 `SendStreamingMessage`，也不能保证极短任务在订阅前仍未终结。
- `Last-Event-ID` 只会向支持恢复的上游透传；当前默认内存事件队列不提供跨进程历史回放。

若订阅事件的 `contextId` 为 null，先确认上游和 Gateway 均为包含 context ID 补齐/改写修复的版本，再核对 Task Route 中
Gateway Context ID 是否存在。若只在进程重启后复现，则属于默认内存 Store 的已知边界。

## 8. 安全事件处理

审计与访问日志不应出现 Authorization、API Key、消息正文、文件内容、完整上游凭据或异常 message。发现泄漏时：

1. 停止相关日志采集或限制访问，保存不含敏感正文的时间窗和 request ID。
2. 轮换已泄漏凭据。
3. 修复或替换 `GatewayAuditSink`/自定义日志逻辑并回归验证。
4. 按组织要求清理或隔离历史日志。

不要通过提高日志级别并打印请求正文来排查业务问题。

## 9. 变更、恢复与证据留存

优先回滚最近的 Agent Card、路由、安全策略或超时配置，再回滚代码。一次只改变一个变量，并保留：

- request ID、合法 trace ID、UTC/带时区时间窗、入口 Binding 与 operation；
- HTTP 状态、A2A error code、Agent/Skill ID、脱敏后的 Gateway Task ID；
- 健康端点结果、Agent 目录快照、相关指标聚合和 Gateway/上游版本；
- 是否发生进程重启、Store 淘汰/过期、SSE 首帧提交或 `OUTCOME_UNKNOWN`。

不得留存 Token、API Key、消息正文或文件内容。引入持久化 Store、共享流配额/熔断、事件回放或 OTel 后端后，必须同步更新本 Runbook。
