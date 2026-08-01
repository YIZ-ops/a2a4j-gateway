# Agent Gateway 故障排查 Runbook

本文是运行参考，不记录阶段进度；MVP 实施过程统一见 [mvp-backlog.md](./mvp-backlog.md)。

## 1. 先建立关联键

从客户端响应头或网关审计日志取得 `X-Gateway-Request-Id`。若调用方携带合法 W3C
`traceparent`，优先使用其中的 traceId；网关会把它透传到上游。不要把 Token、请求正文、Task ID
或 URL 复制到工单和指标标签中。

## 2. 按结果分层定位

| 现象 | 首查位置 | 常见原因 |
| --- | --- | --- |
| 401/403 | `GatewayAuthenticationToken`、审计 outcome/policyDecision | JWT issuer/audience/时间 Claim、租户或 Agent/Skill 权限 |
| 404/409 | `RouteDecision`、`gateway.requests.total{error=...}` | Agent/Skill 不存在、路由冲突、幂等键处理中 |
| 429 | `gateway.streams.started` 与租户并发配置 | 流式连接配额已满 |
| 502/503 | dependency health、Agent Card 快照、熔断状态 | 无健康实例、上游协议错误、连接/响应超时 |
| SSE 中断 | `gateway.stream.duration`、上游连接日志 | 空闲超时、客户端取消、上游终态/错误事件 |

## 3. 健康检查顺序

1. 查看 `/actuator/health/liveness`，若失败先按进程/线程池问题处理。
2. 查看 `/actuator/health/readiness`；`OUT_OF_SERVICE` 通常表示没有 HEALTHY Agent 实例。
3. 查看 Agent Card 刷新时间、健康状态和连续失败次数；确认 Card URL 未触发 SSRF 策略。
4. 检查目标 Agent 的 `A2A-Version: 1.0`、Binding 和 endpoint 是否与注册快照一致。

## 4. 路由与任务问题

- 用同一个 requestId/traceId 对照审计事件中的 operation、agentId、gatewayTaskId（仅在受控诊断系统
  内查看）和 outcome。
- 查询/取消/订阅必须复用 TaskRouteStore 中的实例粘滞；重启单节点 MVP 后内存路由不可恢复，这是
  预期限制，不应自动切换实例重发未知结果。
- 若出现重复任务，检查 idempotency key 是否被复用为不同请求，以及是否发生 `OUTCOME_UNKNOWN`。

### `GATEWAY_ROUTE_NOT_FOUND`：任务路由不存在

JSON-RPC 的 `GetTask`、`CancelTask`、`SubscribeToTask` 返回 `GATEWAY_ROUTE_NOT_FOUND`，表示 Gateway
无法以“认证租户 + Gateway Task ID”找到创建任务时保存的内存路由。它不是 JSON-RPC 请求关联 ID 错误。

1. 先确认 `params.id` 是发送响应中的 **Gateway Task ID**：HTTP+JSON 非流式响应使用根级 `id`，JSON-RPC
   非流式响应使用 `result.id`，JSON-RPC 流式响应通常使用首个事件的 `result.taskId`（部分上游会包装为
   `result.statusUpdate.taskId` 或 `result.artifactUpdate.taskId`）。不要使用 JSON-RPC 顶层的 `id`；例如
   `"id":"send-001"` 只是请求关联 ID，不能用于 `GetTask`。
2. 用创建任务时同一个租户/API Key/JWT 调用。跨租户查询会刻意返回同一个 404，避免泄露 Task ID。
3. 确认 Gateway 没有重启，且 Task Route 没有超过 `task-route-ttl`（默认 24h）或因
   `task-route-max-entries` 达到上限而被淘汰。当前 MVP 的 Store 仅在 Gateway 单进程内存中。
4. 不要把直接调用 Agent 获得的上游 Task ID 用于 Gateway；Gateway 只接受它自己返回的改写后 ID。

正确的调用形状：

```json
{"jsonrpc":"2.0","id":"get-001","method":"GetTask","params":{"id":"<JSON-RPC SendMessage 返回的 result.id>"}}
```

## 5. 安全检查

审计与访问日志不应出现 Authorization、凭据值、消息正文、文件内容或异常 message。若发现泄漏，
立即停用相关日志采集、轮换外部凭据，并修复或替换 `GatewayAuditSink`；不要通过提高日志级别获取
请求正文来排查业务问题。

## 6. 变更与回滚

先回滚最近的 Agent Card/路由/策略配置，再回滚代码。保留 requestId/traceId、时间窗口、错误类型、
健康探针和指标聚合结果；不要提交完整请求和 Token。企业版接入持久化 TaskRouteStore 或 OTel
后端后，需同步更新本 Runbook 的存储和追踪查询步骤。
