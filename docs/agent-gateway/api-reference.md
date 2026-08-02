# Gateway API 参考

本文是当前 A2A4J Agent Gateway MVP 的**完整北向 API 清单**。内容以
`GatewayAgentCatalogController`、`GatewayHttpJsonController`、`GatewayJsonRpcController` 和异常处理器的实际实现为准。

Gateway 基地址示例为 `http://localhost:8099`。所有业务 API 均是租户隔离的：租户取自已认证身份，客户端不能通过路径、Header 或请求体指定或覆盖租户。

> 2026-08-02 复核：本参考同步当前 Gateway 实现。流式建连阶段的上游非 2xx 响应会在
> SSE 响应提交前按入站 Binding 返回普通 A2A 错误；当前验证结果和发布门槛见
> [MVP 状态与 Backlog](./mvp-backlog.md)。

## 1. 适用范围与通用约定

### 1.1 协议和内容类型

| API 类型 | 请求 | 成功响应 |
| --- | --- | --- |
| Agent 目录 | 无 | `application/json` |
| HTTP+JSON 非流式 | `application/json` 或 `application/a2a+json` | `application/a2a+json` |
| HTTP+JSON 流式 | `application/json` 或 `application/a2a+json` | `text/event-stream` |
| JSON-RPC 非流式 | `application/json` 或 `application/a2a+json` | `application/json`（JSON-RPC 2.0 envelope） |
| JSON-RPC 流式 | `application/json` 或 `application/a2a+json`，`Accept: text/event-stream` | `text/event-stream`；每个 `data` 是 JSON-RPC 2.0 对象 |

- 当前唯一支持的 A2A 协议版本为 `1.0`。所有业务请求都应明确携带
  `A2A-Version: 1.0`；当前 Adapter 不把缺失 Header 当作 1.0，缺失或显式传入其他版本均会被版本校验拒绝。
- Gateway 对外可同时接收 HTTP+JSON 与 JSON-RPC；Gateway 到上游 Agent 根据 Card 的 `supportedInterfaces` 选择 Binding，默认优先 JSON-RPC，只有目标仅支持 HTTP+JSON 时才选择 HTTP+JSON。
- 新建或返回任务时，Gateway 会改写 Task/Context ID。后续调用必须使用 Gateway 返回的 ID，不能使用上游 Agent 的内部 ID。
- 版本化路径与短路径功能等价；新客户端建议使用 `/gateway/v1/...` 路径，短路径保留为简洁入口。
- `SendStreamingMessage` 和 `SubscribeToTask` 只有在上游 Card 的 `capabilities.streaming` 明确为 `true` 时才允许调用；字段缺失或为 `false` 均返回 `400 UNSUPPORTED_OPERATION`。订阅还会校验任务订阅能力（若 Card 明确声明该能力为 `false`）。
- `GetExtendedAgentCard` 的 `extendedAgentCard` 缺失或为 `false` 时返回 `UNSUPPORTED_OPERATION`；上游声明支持但没有配置扩展 Card 时，按 `EXTENDED_AGENT_CARD_NOT_CONFIGURED` 返回。
- JSON-RPC 入站即使选择到 HTTP+JSON-only 上游，成功同步响应仍返回 JSON-RPC envelope；流式的每个 `data` 事件也返回 JSON-RPC envelope。错误则先归一化为 A2A 标准错误，再按入站 Binding 编码。

### 1.2 鉴权与公共 Header

除下文明确说明的 Actuator 健康端点和标准 `/.well-known/` Agent Card 发现入口外，所有 API 均需要认证。

| Header | 适用模式 | 说明 |
| --- | --- | --- |
| `Authorization: Bearer <JWT>` | JWT | JWT/OIDC 入站认证。 |
| `X-A2A-API-Key: <secret>` | API Key | 本地开发模式；实际名称可用 `a2a.gateway.security.api-key.header-name` 修改。 |
| `A2A-Version: 1.0` | 全部业务 API | A2A 版本协商。 |
| `X-A2A-Target-Agent: <agentId>` | 数据面 | 显式逻辑 Agent 路由提示。 |
| `X-A2A-Target-Skill: <skillId>` | 数据面 | Skill 路由提示；Gateway 会校验目标 Agent 具有该 Skill。 |
| `Idempotency-Key: <key>` | 写操作 | 同租户内新建消息任务的幂等键；同键不同请求内容返回冲突。 |
| `X-Gateway-Request-Id: <id>` | 全部业务 API | 请求关联 ID；缺省时 Gateway 生成 UUID，最终值会通过同名响应头返回。 |
| `X-Gateway-Task-Id: <taskId>` | 任务操作 | Gateway Task ID 的可选 Header 形式；HTTP+JSON 优先使用路径参数，JSON-RPC 可从 `params.id` 读取。 |
| `Last-Event-ID: <eventId>` | SSE 订阅 | 上游支持时用于任务流的断点续订。 |
| `A2A-Extensions` | 数据面 | 逗号分隔的扩展 URI。 |
| `traceparent` / `tracestate` | 数据面 | 合法 W3C Trace Context 会被记录并透传给上游。 |

路由优先级为：已有 Gateway Task 路由 → 显式 Agent 路径 → `X-A2A-Target-Agent` → `X-A2A-Target-Skill` → 租户默认 Agent。无法唯一选中 Agent 时返回 `404` 或 `409`；路径中的 `{agentId}` 也只是路由提示，仍受租户与权限校验。

### 1.3 权限

| 操作 | 所需权限 |
| --- | --- |
| 目录读取 | 已认证；结果始终按当前租户过滤。 |
| `GetExtendedAgentCard` | `agent:discover` |
| 发送消息 | `agent:invoke` 或 `agent:invoke:{agentId}`；指定 Skill 时还需 `skill:invoke` 或 `skill:invoke:{skillId}`。 |
| `GetTask`、`ListTasks`、`SubscribeToTask`、四个 Push Notification 配置方法 | `task:read` |
| `CancelTask` | `task:cancel` |

`*` 仅适合本地示例。JWT scope/role 与 API Key authorities 都可提供上述权限。

## 2. 全量路径清单

### 2.1 Agent 目录

| 方法 | 规范路径 | 等价短路径 | 说明 |
| --- | --- | --- | --- |
| GET | `/gateway/v1/agents` | `/agents` | 列出当前租户可见 Agent。 |
| GET | `/gateway/v1/agents/{agentId}` | `/agents/{agentId}` | 查询一个 Agent 摘要。 |
| GET | `/gateway/v1/agents/{agentId}/card` | `/agents/{agentId}/card` | 查询 Gateway 根据快照合成的 Agent Card。 |
| GET | `/.well-known/agent-card.json` | — | 标准公共发现入口；无选择器时返回按 tenant/agent 排序后的默认 Card。 |
| GET | `/.well-known/agents/{agentId}/agent-card.json` | — | 多 Agent 部署的 Agent-specific 标准发现入口，可选 `tenantId`。 |

### 2.2 HTTP+JSON 数据面

| 方法 | 规范路径 | 短路径 | 显式 Agent 路径 | 响应 |
| --- | --- | --- | --- | --- |
| POST | `/gateway/v1/message:send` | `/message:send` | `/gateway/v1/agents/{agentId}/message:send` | JSON |
| POST | `/gateway/v1/message:stream` | `/message:stream` | `/gateway/v1/agents/{agentId}/message:stream` | SSE |
| GET | `/gateway/v1/tasks/{taskId}` | `/tasks/{taskId}` | `/gateway/v1/agents/{agentId}/tasks/{taskId}` | JSON |
| GET | `/gateway/v1/tasks` | `/tasks` | `/gateway/v1/agents/{agentId}/tasks` | JSON |
| POST | `/gateway/v1/tasks/{taskId}:cancel` | `/tasks/{taskId}:cancel` | `/gateway/v1/agents/{agentId}/tasks/{taskId}:cancel` | JSON |
| POST | `/gateway/v1/tasks/{taskId}:subscribe` | `/tasks/{taskId}:subscribe` | `/gateway/v1/agents/{agentId}/tasks/{taskId}:subscribe` | SSE |
| POST | `/gateway/v1/tasks/{taskId}/pushNotificationConfigs` | `/tasks/{taskId}/pushNotificationConfigs` | `/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs` | JSON |
| GET | `/gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}` | `/tasks/{taskId}/pushNotificationConfigs/{configId}` | `/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs/{configId}` | JSON |
| GET | `/gateway/v1/tasks/{taskId}/pushNotificationConfigs` | `/tasks/{taskId}/pushNotificationConfigs` | `/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs` | JSON |
| DELETE | `/gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}` | `/tasks/{taskId}/pushNotificationConfigs/{configId}` | `/gateway/v1/agents/{agentId}/tasks/{taskId}/pushNotificationConfigs/{configId}` | JSON |
| GET | `/gateway/v1/extendedAgentCard` | `/extendedAgentCard` | `/gateway/v1/agents/{agentId}/extendedAgentCard` | JSON |

### 2.3 JSON-RPC 数据面

所有 JSON-RPC 方法共用同一 POST 入口：规范路径为 `/gateway/v1/a2a`，短路径为 `/a2a`，显式
Agent 路径为 `/gateway/v1/agents/{agentId}/a2a`。具体操作由请求体的 `method` 字段选择：

| 中文方法名 | JSON-RPC `method` | 模式 | `Accept` | 主要结果 |
| --- | --- | --- | --- | --- |
| 发送消息 | `SendMessage` | 同步 | `application/json` | `result.task` 或 `result.message` |
| 流式发送消息 | `SendStreamingMessage` | SSE | `text/event-stream` | 首个 `result.task`/`result.message`，以及后续更新 |
| 查询任务 | `GetTask` | 同步 | `application/json` | `result` 为 Task |
| 列举任务 | `ListTasks` | 同步 | `application/json` | `result.tasks` 与分页字段 |
| 取消任务 | `CancelTask` | 同步 | `application/json` | `result` 为更新后的 Task |
| 订阅任务 | `SubscribeToTask` | SSE | `text/event-stream` | 当前 Task 与后续更新 |
| 创建任务推送通知配置 | `CreateTaskPushNotificationConfig` | 同步 | `application/json` | `result` 为创建后的配置 |
| 查询任务推送通知配置 | `GetTaskPushNotificationConfig` | 同步 | `application/json` | `result` 为配置对象 |
| 列举任务推送通知配置 | `ListTaskPushNotificationConfigs` | 同步 | `application/json` | `result.configs` 与分页字段 |
| 删除任务推送通知配置 | `DeleteTaskPushNotificationConfig` | 同步 | `application/json` | 通常为 `result: {}` |
| 获取扩展 Agent Card | `GetExtendedAgentCard` | 同步 | `application/json` | `result` 为扩展 Card |

同步方法若使用 `Accept: text/event-stream`，或两个流式方法使用 `Accept: application/json`，均返回
`400 INVALID_ARGUMENT`。所有调用必须携带 `Content-Type: application/json` 或
`application/a2a+json`，并明确声明 `A2A-Version: 1.0`。

### 2.4 运维端点（sample 默认暴露）

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | 无 | 总体健康。 |
| GET | `/actuator/health/liveness` | 无 | 开启 `management.endpoint.health.probes.enabled` 后提供的存活探针。 |
| GET | `/actuator/health/readiness` | 无 | 开启 probes 后提供；仅在应用显式配置时才包含 `gatewayDependency`。 |
| GET | `/actuator/info` | 无 | 应用信息。 |
| GET | `/actuator/prometheus` | 取决于应用安全配置 | Prometheus 抓取端点。 |

这些是 Spring Boot Actuator 端点，不是 A2A 协议端点；实际暴露集合由 `management.endpoints.web.exposure.include`、
probes 和健康分组配置共同决定。Starter 只注册 `gatewayAgent` 与 `gatewayDependency` 两个 HealthIndicator，不会自动修改
readiness/liveness 分组；推荐配置见 [Runbook](./runbook.md)。

## 3. Agent 目录 API

### 3.1 `GET /gateway/v1/agents`

返回当前认证租户中已进入 Gateway 可发现快照的逻辑 Agent。不存在跨租户筛选参数，也不会显示其他租户的 Agent。

```json
{
  "agents": [
    {
      "tenantId": "demo",
      "agentId": "echo-a",
      "displayName": "Echo Agent A",
      "enabled": true,
      "skills": [{"id": "hello-world", "name": "hello-world", "description": "...", "tags": [], "inputModes": ["text/plain"], "outputModes": ["text/plain"]}],
      "routingLabels": {},
      "protocolPolicy": {"protocolVersions": ["1.0"], "protocolBindings": ["JSONRPC", "HTTP+JSON"]},
      "instances": [{
        "instanceId": "echo-a-1",
        "cardUrl": "http://localhost:8091/.well-known/agent-card.json",
        "healthStatus": "HEALTHY",
        "weight": 1,
        "interfaces": [{"interfaceKey": "jsonrpc", "endpointUrl": "http://localhost:8091/a2a/server", "protocolBinding": "JSONRPC", "protocolVersion": "1.0"}],
        "lastCheckedAt": "2026-08-01T04:00:00Z"
      }]
    }
  ]
}
```

`GET /gateway/v1/agents/{agentId}` 返回上述数组中的单个对象。未找到、禁用或不属于当前租户时返回 `404 GATEWAY_ROUTE_NOT_FOUND`。

### 3.2 `GET /gateway/v1/agents/{agentId}/card`

返回 Gateway 从已校验的注册快照生成的 A2A 1.0 Card，而非原样代理上游 Card。它包含 `name`、`description`、`version`、`supportedInterfaces`、`capabilities`、默认输入/输出模式以及 `skills`。上游 Card 校验、版本或 URL 网络策略不通过时不会进入该快照。

当前 sample 的 `echo-a` Card 只会声明其真实上游 JSON-RPC 接口 `http://localhost:8091/a2a/server`；Gateway 自己的 `/message:send` 不是该 Agent 的接口，不会出现在 Card 中。

Gateway 不沿用被改写 Card 的上游 `signatures`、`securitySchemes` 或 `securityRequirements`。启用 Gateway 入站认证时，Card 只投影 Gateway 自己的安全配置，并使用 A2A 1.0 的 oneof 包装结构（例如 `httpAuthSecurityScheme` 和 `schemes.list`）。标准 `/.well-known/` 发现入口免认证；多 Agent 场景使用带 Agent ID 的标准路径或已认证的 Gateway 目录 API。

## 4. HTTP+JSON API

### 4.1 发送消息：`POST /gateway/v1/message:send`

请求体是 A2A 1.0 `SendMessageRequest` 的 ProtoJSON 形状。`message` 必填；可选的会话
`contextId` 和续写任务 `taskId` 属于 `message`，不是请求顶层字段。`configuration` 和请求级
`metadata` 可选：

```json
{
  "message": {
    "messageId": "msg-001",
    "contextId": "client-context-001",
    "role": "ROLE_USER",
    "parts": [
      {"text": "总结本季度行业变化", "mediaType": "text/plain"}
    ]
  },
  "configuration": {
    "acceptedOutputModes": ["text/plain"],
    "historyLength": 10,
    "returnImmediately": true
  },
  "metadata": {"clientRequestType": "summary"}
}
```

`configuration` 字段语义：

| 字段 | 类型 | 缺省值 | 说明 |
| --- | --- | --- | --- |
| `acceptedOutputModes` | string[] | 未限制 | 客户端可接收的响应 Part media type。 |
| `taskPushNotificationConfig` | object | 无 | 随发送请求一并配置 Push；目标 Agent 必须声明 `pushNotifications: true`。 |
| `historyLength` | integer ≥ 0 | Agent 默认 | 返回 Task 时最多携带的最近历史条数；`0` 请求省略历史。 |
| `returnImmediately` | boolean | `false` | `false`/缺失时等待终态或 `INPUT_REQUIRED`/`AUTH_REQUIRED`；`true` 时创建 Task 后立即返回。对直接 `Message` 响应和流式发送无影响。 |

`returnImmediately: true` 的成功响应通常为 `200 application/a2a+json`：

```json
{
  "task": {
    "id": "gateway-task-id",
    "contextId": "gateway-context-id",
    "status": {
      "state": "TASK_STATE_WORKING",
      "timestamp": "2026-08-02T13:34:52Z"
    }
  }
}
```

也允许返回 `{"message": {...}}`。所有 Task/Context 标识均已改写为 Gateway 标识。拿到
`WORKING` 后可轮询 `GET /tasks/{taskId}`，或立即建立 `POST /tasks/{taskId}:subscribe` SSE。
请求体超过 `a2a.gateway.max-request-bytes` 时返回 `413 REQUEST_TOO_LARGE`。

可直接执行的 PowerShell 示例：

```powershell
$body = @'
{"message":{"messageId":"msg-001","role":"ROLE_USER","parts":[{"text":"hello","mediaType":"text/plain"}]},"configuration":{"returnImmediately":true}}
'@
curl.exe -X POST http://localhost:8099/gateway/v1/message:send `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "X-A2A-Target-Agent: echo-a" -H "A2A-Version: 1.0" `
  -H "Content-Type: application/a2a+json" -H "Accept: application/a2a+json" `
  --data $body
```

### 4.2 流式发送：`POST /gateway/v1/message:stream`

请求体与 4.1 相同，必须设置 `Accept: text/event-stream`。`returnImmediately` 对该方法没有作用。
符合 A2A 1.0 的 Task 流第一条 `data` 是完整 `Task`，随后是零到多个 `statusUpdate` 或
`artifactUpdate`；直接 Message 模式则只返回一个 `message` 后结束。Gateway 不聚合上游流。

Gateway 对外 SSE `event` 名称为 `task-status`、`task-artifact`、`task-completed` 或 `error`；
协议数据只应从 `data` JSON 判断，不能依赖上游自定义的事件名：

```text
event: task-status
data: {"task":{"id":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_WORKING"}}}

event: task-artifact
data: {"artifactUpdate":{"taskId":"gateway-task-id","contextId":"gateway-context-id","artifact":{"artifactId":"answer","parts":[{"text":"partial"}]},"append":true,"lastChunk":false}}

event: task-completed
data: {"statusUpdate":{"taskId":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_COMPLETED"}}}
```

`TaskStatusUpdateEvent` 和 `TaskArtifactUpdateEvent` 的 `taskId`、`contextId` 均为必填；它们应与首个
Task 中的 Gateway ID 一致。客户端持续读取至终态后连接正常关闭。主动断开下游会取消上游连接。
`max-event-bytes`、`stream-idle-timeout` 与 `max-concurrent-streams` 分别约束事件大小、空闲时间和并发流。

### 4.3 查询单个任务：`GET /gateway/v1/tasks/{taskId}`

`taskId` 必须是 Gateway 返回的 ID；可使用 `historyLength` 查询参数：

```text
GET /gateway/v1/tasks/gateway-task-id?historyLength=20
```

跨租户、过期或不存在均返回不泄露内部 ID 的 `404 TASK_NOT_FOUND`。成功响应是完整 Task，而不是
`{"task": ...}` wrapper。轮询上游成功后，Gateway 会同步更新自己的 Task Route 状态和快照。

### 4.4 列举任务：`GET /gateway/v1/tasks`

这是 Gateway 本地 Task Route 查询，不会向所有上游 Agent 广播 `ListTasks`。查询始终按当前租户、
调用主体指纹和选定 Agent 隔离；`/agents/{agentId}/tasks` 不会混入其他 Agent 的任务。

| Query | 类型/范围 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `pageSize` | 1..100 | 50 | 每页最大任务数。兼容别名 `limit`。 |
| `pageToken` | string | 无 | 上一页的 `nextPageToken`，是不透明 cursor，不能当 offset 修改。 |
| `contextId` | string | 无 | Gateway Context ID 精确过滤。 |
| `status` | string | 无 | 支持 `TASK_STATE_WORKING` 等规范枚举，也接受 `WORKING`、`ACTIVE` 等兼容值。 |
| `statusTimestampAfter` | RFC 3339 | 无 | 只返回状态时间晚于该时间的 Task。 |
| `historyLength` | integer ≥ 0 | 0 | 大于 0 时包含最近 N 条 history；0 时省略。 |
| `includeArtifacts` | boolean | false | false 时省略 `artifacts`；true 时包含实际数组。 |

响应始终包含 `tasks`、`nextPageToken`（最后一页为空字符串）、`pageSize` 和 `totalSize`。结果按保存的
`status.timestamp` 倒序排列；流式 Artifact 使用相同 `artifactId` 且 `append=true` 时会合并 parts。
Gateway 重启后当前内存快照和路由均不可恢复。

### 4.5 取消任务：`POST /gateway/v1/tasks/{taskId}:cancel`

请求体可以是 `{}`，也可传 A2A 1.0 `CancelTaskRequest` 的可选 `metadata`：

```json
{"metadata":{"reason":"user requested"}}
```

必须携带 `Content-Type: application/json` 或 `application/a2a+json`，需要 `task:cancel`。成功响应是状态已
更新的 Task。终态或不可取消任务返回 `400 TASK_NOT_CANCELABLE`，不存在返回 `404 TASK_NOT_FOUND`。

### 4.6 订阅任务：`POST /gateway/v1/tasks/{taskId}:subscribe`

请求体使用 `{}`，必须设置 `Accept: text/event-stream` 并携带 `task:read`。可选 `Last-Event-ID` 会在
目标 Binding 支持时透传。Gateway 根据 Task Route 固定回创建任务时的 Agent 实例和 Binding。

对活动 Task，首个 `data` 必须是当前完整 Task，之后才是更新事件；所有更新都必须携带相同的
`contextId`。`INPUT_REQUIRED` 和 `AUTH_REQUIRED` 是可继续交互的非终态，仍可订阅。订阅建立后到达
`COMPLETED`、`FAILED`、`CANCELED` 或 `REJECTED` 时发送最后状态并关闭流。任务已经处于上述终态时，
不建立 SSE，而返回 `400 UNSUPPORTED_OPERATION`。

```powershell
curl.exe -N -X POST "http://localhost:8099/gateway/v1/tasks/$env:A2A_TASK_ID`:subscribe" `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "A2A-Version: 1.0" -H "Content-Type: application/a2a+json" `
  -H "Accept: text/event-stream" --data '{}'
```

### 4.7 Push Notification 配置

四个 HTTP+JSON 入口与 A2A 1.0 路径一致，均需要 `task:read`，且目标 Agent Card 必须声明
`capabilities.pushNotifications: true`：

| 操作 | 请求 |
| --- | --- |
| 创建 | `POST /gateway/v1/tasks/{taskId}/pushNotificationConfigs`，body 为配置对象 |
| 查询 | `GET /gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}` |
| 列举 | `GET /gateway/v1/tasks/{taskId}/pushNotificationConfigs?pageSize=20&pageToken=...` |
| 删除 | `DELETE /gateway/v1/tasks/{taskId}/pushNotificationConfigs/{configId}` |

创建示例：

```json
{
  "url": "https://client.example.com/a2a/events",
  "token": "per-task-verification-token",
  "authentication": {
    "scheme": "Bearer",
    "credentials": "callback-credential"
  }
}
```

路径中的 `taskId` 使用 Gateway Task ID；Gateway 会映射为上游 Task ID，并固定到原 Agent 实例。
`configId` 是 Agent 返回的配置 ID，Gateway 不另行改写。Gateway 只转发这些配置 API，不保存配置，
也不充当 webhook 接收或二次投递服务；实际回调由 Agent 直接请求 `url`。Gateway 重启后 Task Route
丢失，届时不能再通过 Gateway 管理旧配置。

### 4.8 扩展 Agent Card：`GET /gateway/v1/extendedAgentCard`

需要 `agent:discover` 和目标 Agent 路由提示。目标必须声明 `capabilities.extendedAgentCard: true`；缺失或
false 返回 `400 UNSUPPORTED_OPERATION`，声明支持但未配置返回
`400 EXTENDED_AGENT_CARD_NOT_CONFIGURED`。成功 Card 会由 Gateway 重新投影可见接口和安全对象，
不会原样暴露失效的上游签名。

## 5. A2A 1.0 JSON-RPC API

### 5.1 基本请求与流式选择

请求必须是 JSON-RPC 2.0 对象，包含非空 `id`、受支持的 `method` 和对象类型的 `params`。文本 Part 直接使用 `text` 与可选的 `mediaType`；A2A 1.0 不生成或要求 v0.3 的 `kind` 判别字段：

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-001",
  "method": "SendMessage",
  "params": {
    "message": {"messageId": "msg-001", "role": "ROLE_USER", "parts": [{"text": "hello", "mediaType": "text/plain"}]}
  }
}
```

`SendStreamingMessage` 与 `SubscribeToTask` 必须使用 `Accept: text/event-stream`；在同步 Accept 下调用会返回 `400 INVALID_ARGUMENT`。反之，在 SSE Accept 下调用非流式方法也返回 `400 INVALID_ARGUMENT`。

SSE 的每个 JSON-RPC `result` 必须使用 A2A 1.0 的 oneof wrapper：`task`、`message`、`statusUpdate` 或 `artifactUpdate`。网关不接受 v0.3 的直接事件对象或 `kind` 判别字段。

### 5.2 方法索引

| JSON-RPC method | 响应 | 主要 `params` | 权限/说明 |
| --- | --- | --- | --- |
| `SendMessage` | 同步 | `message`、`configuration`、`metadata` | 新建或推进任务；`contextId`/`taskId` 位于 `message`。`configuration.returnImmediately` 控制是否等待。 |
| `SendStreamingMessage` | SSE | `message`、`configuration`、`metadata` | 新建或推进任务并逐事件返回；`returnImmediately` 对流式调用无效。 |
| `GetTask` | 同步 | `id`、`historyLength` | `id` 为 Gateway Task ID；需要 `task:read`。 |
| `ListTasks` | 同步 | `contextId`、`status`、`pageSize`、`pageToken`、`statusTimestampAfter`、`historyLength`、`includeArtifacts` | 需要 `task:read`。 |
| `CancelTask` | 同步 | `id` | 需要 `task:cancel`。 |
| `SubscribeToTask` | SSE | `id` | 需要 `task:read`，可带 `Last-Event-ID`。 |
| `CreateTaskPushNotificationConfig` | 同步 | `taskId`、`url`、`token`、`authentication` | 使用 Gateway Task ID；需 `task:read` 且上游支持 Push。 |
| `GetTaskPushNotificationConfig` | 同步 | `taskId`、`id` | `id` 为 Agent 配置 ID。 |
| `ListTaskPushNotificationConfigs` | 同步 | `taskId`、`pageSize`、`pageToken` | 返回 `configs` 与 `nextPageToken`。 |
| `DeleteTaskPushNotificationConfig` | 同步 | `taskId`、`id` | 删除 Agent 上的配置。 |
| `GetExtendedAgentCard` | 同步 | A2A Card 参数 | 需要 `agent:discover`；仅当目标 Agent 实现该方法时可转发，一般优先使用目录 Card API。 |

任务方法优先读取 `X-Gateway-Task-Id`；未提供时，`GetTask`、`CancelTask`、`SubscribeToTask` 从 `params.id` 读取。

### 5.3 发送消息：`SendMessage`

`SendMessage` 是同步单响应方法。它返回 `result.task` 或 `result.message`，不会因为
`returnImmediately: true` 变成 SSE。请求使用 `Accept: application/json`，需要 `agent:invoke`。

```powershell
$body = '{"jsonrpc":"2.0","id":"rpc-001","method":"SendMessage","params":{"message":{"messageId":"msg-001","role":"ROLE_USER","parts":[{"text":"hello","mediaType":"text/plain"}]},"configuration":{"returnImmediately":true}}}'
curl.exe -X POST http://localhost:8099/gateway/v1/a2a `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "A2A-Version: 1.0" -H "X-A2A-Target-Agent: echo-a" `
  -H "Content-Type: application/json" -H "Accept: application/json" --data $body
```

`returnImmediately` 的语义与 4.1 相同：缺失/false 时等待任务进入终态或中断态；true 时立即返回
`result.task`，常见状态为 `TASK_STATE_WORKING`。随后用 `GetTask` 轮询，或在任务仍活动时调用
`SubscribeToTask`。它不是“把 SendMessage 变成 SSE”；`SendMessage` 始终只返回一个同步 JSON-RPC
响应，只有 `SendStreamingMessage` 和 `SubscribeToTask` 使用 SSE。

立即返回的成功响应示例：

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-001",
  "result": {
    "task": {
      "id": "gateway-task-id",
      "contextId": "gateway-context-id",
      "status": {"state": "TASK_STATE_WORKING"}
    }
  }
}
```

### 5.4 流式发送消息：`SendStreamingMessage`

请求参数与 `SendMessage` 相同，但必须设置 `Accept: text/event-stream`。`returnImmediately` 对该方法
无效。Task 生命周期流第一条必须是 `result.task`，随后才是 `result.statusUpdate` 或
`result.artifactUpdate`；直接 Message 模式只返回一条 `result.message`。

```json
{
  "jsonrpc": "2.0",
  "id": "stream-001",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "messageId": "msg-stream-001",
      "role": "ROLE_USER",
      "parts": [{"text": "生成报告", "mediaType": "text/plain"}]
    },
    "configuration": {"acceptedOutputModes": ["text/plain"]}
  }
}
```

```text
event: task-status
data: {"jsonrpc":"2.0","id":"stream-001","result":{"task":{"id":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_WORKING"}}}}

event: task-artifact
data: {"jsonrpc":"2.0","id":"stream-001","result":{"artifactUpdate":{"taskId":"gateway-task-id","contextId":"gateway-context-id","artifact":{"artifactId":"report","parts":[{"text":"partial"}]},"append":true,"lastChunk":false}}}
```

### 5.5 查询任务：`GetTask`

先从 JSON-RPC 响应保存 Gateway Task ID：`SendMessage` 使用 `result.task.id`；
`SendStreamingMessage` 的 Task 生命周期首帧使用 `result.task.id`，后续事件才使用
`result.statusUpdate.taskId` 或 `result.artifactUpdate.taskId`。如果响应是 `result.message`，则没有可查询的
Task。`GetTask.params.id` 必须使用这个 Gateway ID。

Gateway ID 与 Agent 控制台中打印的上游 Task ID **预期不同**。Gateway 创建外部 ID 后，在内存 Task Route 中保存它到租户、调用主体、Agent 实例、Binding 和上游 Task ID 的映射；客户端只能使用 Gateway ID，控制台排障才在受控环境中关联上游 ID，二者都不应互相替换。

```json
{"jsonrpc":"2.0","id":"get-001","method":"GetTask","params":{"id":"gateway-task-id","historyLength":20}}
```

成功时 `result` 直接是 Task，不带 `task` wrapper。`historyLength` 必须是非负整数；Header
`X-Gateway-Task-Id` 存在时优先于 `params.id`。不存在或不可见返回 `TASK_NOT_FOUND (-32001/404)`。

### 5.6 列举任务：`ListTasks`

```json
{"jsonrpc":"2.0","id":"list-001","method":"ListTasks","params":{"contextId":"gateway-context-id","status":"TASK_STATE_WORKING","pageSize":20,"pageToken":"optional-token","historyLength":20,"includeArtifacts":false}}
```

这是 Gateway 本地 Task Route 查询，不向所有上游广播。参数、范围和响应字段与 4.4 相同；成功
`result` 始终包含 `tasks`、`nextPageToken`、`pageSize` 和 `totalSize`。最后一页的
`nextPageToken` 是空字符串。建议显式指定 Agent 或配置租户默认 Agent。

### 5.7 取消任务：`CancelTask`

```json
{"jsonrpc":"2.0","id":"cancel-001","method":"CancelTask","params":{"id":"gateway-task-id"}}
```

需要 `task:cancel`，可在 `params.metadata` 携带取消原因。成功 `result` 直接是更新后的 Task；任务不存在
返回 `TASK_NOT_FOUND`，不可取消返回 `TASK_NOT_CANCELABLE (-32002/400)`。

### 5.8 订阅任务：`SubscribeToTask`

`SubscribeToTask` 使用同一入口，但必须协商 SSE：

```powershell
$body = '{"jsonrpc":"2.0","id":"subscribe-001","method":"SubscribeToTask","params":{"id":"gateway-task-id"}}'
curl.exe -N -X POST http://localhost:8099/gateway/v1/a2a `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "A2A-Version: 1.0" -H "Accept: text/event-stream" `
  -H "Content-Type: application/json" --data $body
```

活动任务的合法响应顺序如下；每个事件都是独立 JSON-RPC envelope，顶层 `id` 与订阅请求 ID 相同：

```text
event: task-status
data: {"jsonrpc":"2.0","id":"subscribe-001","result":{"task":{"id":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_WORKING"}}}}

event: task-status
data: {"jsonrpc":"2.0","id":"subscribe-001","result":{"statusUpdate":{"taskId":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_WORKING"}}}}

event: task-completed
data: {"jsonrpc":"2.0","id":"subscribe-001","result":{"statusUpdate":{"taskId":"gateway-task-id","contextId":"gateway-context-id","status":{"state":"TASK_STATE_COMPLETED"}}}}
```

`contextId` 在完整 Task、状态事件和 Artifact 事件中必须保持一致且非 null。如果任务已经终态，响应为
HTTP 400 的普通 JSON-RPC error，而不是一个成功 SSE 流：

```json
{
  "jsonrpc": "2.0",
  "id": "subscribe-001",
  "error": {
    "code": -32004,
    "message": "cannot subscribe to a task that is not active",
    "data": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "UNSUPPORTED_OPERATION",
        "domain": "a2a-protocol.org"
      }
    ]
  }
}
```

Gateway 已有 Task Route 时会固定回创建任务的原实例，`X-A2A-Target-Agent` 不能迁移任务。

### 5.9 创建任务推送通知配置：`CreateTaskPushNotificationConfig`

创建 Push 配置需要 `task:read`。`params.taskId` 必须是 Gateway Task ID；Gateway 根据 Task Route
转换为上游 ID，并固定到创建任务的实例。目标 Card 未声明 `pushNotifications: true` 时返回
`PUSH_NOTIFICATION_NOT_SUPPORTED (-32003/400)`。

创建配置：

```json
{
  "jsonrpc": "2.0",
  "id": "push-create-001",
  "method": "CreateTaskPushNotificationConfig",
  "params": {
    "taskId": "gateway-task-id",
    "url": "https://callback.example.com/a2a/events",
    "token": "callback-verification-token",
    "authentication": {
      "scheme": "Bearer",
      "credentials": "callback-credential"
    }
  }
}
```

成功 `result` 是 Agent 创建的配置对象，包含 Agent 分配的配置 `id`；Gateway 不改写该配置 ID。

### 5.10 查询任务推送通知配置：`GetTaskPushNotificationConfig`

读取指定 Task 的一个 Push 配置，需要 `task:read` 和上游 Push 能力。Gateway 将 `taskId` 转换为上游
Task ID，但不改写 `id`。

```json
{"jsonrpc":"2.0","id":"push-get-001","method":"GetTaskPushNotificationConfig","params":{"taskId":"gateway-task-id","id":"config-id"}}
```

`taskId` 是 Gateway Task ID，`id` 是 Agent 返回的配置 ID。成功 `result` 是配置对象。

### 5.11 列举任务推送通知配置：`ListTaskPushNotificationConfigs`

列出指定 Task 的 Push 配置，需要 `task:read` 和上游 Push 能力。`pageSize` 控制每页数量，
`pageToken` 使用上一页返回的不透明值。

```json
{"jsonrpc":"2.0","id":"push-list-001","method":"ListTaskPushNotificationConfigs","params":{"taskId":"gateway-task-id","pageSize":20,"pageToken":""}}
```

成功 `result` 包含 `configs` 和 `nextPageToken`；`pageToken` 必须原样使用上一页返回的不透明值。

### 5.12 删除任务推送通知配置：`DeleteTaskPushNotificationConfig`

删除指定 Task 的一个 Push 配置，需要 `task:read` 和上游 Push 能力。Gateway 映射 `taskId`，配置
`id` 原样传给上游。

```json
{"jsonrpc":"2.0","id":"push-delete-001","method":"DeleteTaskPushNotificationConfig","params":{"taskId":"gateway-task-id","id":"config-id"}}
```

成功响应通常为 `result: {}`。Gateway 不持久化 Push 配置或代理 webhook 回调；Agent 会直接调用配置
中的 `url`。Gateway 重启导致内存 Task Route 丢失后，无法再通过 Gateway 管理旧配置。`echo-a`、
`echo-b` 不声明 `pushNotifications: true`，因此不能用于 Push 成功路径演示。

### 5.13 获取扩展 Agent Card：`GetExtendedAgentCard`

`GetExtendedAgentCard` 的 Gateway 调用形状如下，但只有上游明确实现该 JSON-RPC 方法时才成功：

```json
{"jsonrpc":"2.0","id":"card-001","method":"GetExtendedAgentCard","params":{}}
```

携带 `X-A2A-Target-Agent` 和 `agent:discover` 权限。对 Gateway 当前注册快照的常规查询，应优先使用 `GET /gateway/v1/agents/{agentId}/card`，它不依赖上游扩展 Card 方法。

扩展 Card 成功时保留上游扩展的 skills、provider、文档等字段，但会重新投影 Gateway 可见的 interface URL 和安全对象，并删除失效的上游签名；上游错误不会被重新包装成成功的 Card。

## 6. 错误响应

### 6.1 HTTP+JSON 与目录

控制器接收请求后的错误形状固定为：

```json
{"error":{"code":503,"status":"UNAVAILABLE","message":"selected Agent is no longer available","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"AGENT_UNAVAILABLE","domain":"a2a-protocol.org","metadata":{"gatewayCode":"GATEWAY_AGENT_UNAVAILABLE"}}]}}
```

跨 Binding 转发上游错误时，Gateway 先从 JSON-RPC code、HTTP ErrorInfo.reason/status 和 HTTP status 推导 canonical reason，再按入口协议编码。A2A 标准错误映射为：

| canonical reason | JSON-RPC | HTTP+JSON | 说明 |
| --- | ---: | ---: | --- |
| `TASK_NOT_FOUND` | `-32001` | `404` | 任务不存在或任务路由不可见。 |
| `TASK_NOT_CANCELABLE` | `-32002` | `400` | 任务当前不可取消。 |
| `PUSH_NOTIFICATION_NOT_SUPPORTED` | `-32003` | `400` | 上游未声明 Push 能力。 |
| `UNSUPPORTED_OPERATION` | `-32004` | `400` | 能力缺失或操作不支持。 |
| `CONTENT_TYPE_NOT_SUPPORTED` | `-32005` | `400` | Binding/Content-Type 不支持。 |
| `INVALID_AGENT_RESPONSE` | `-32006` | `500` | 上游显式返回该标准错误。 |
| `EXTENDED_AGENT_CARD_NOT_CONFIGURED` | `-32007` | `400` | 上游声明支持但没有扩展 Card。 |
| `EXTENSION_SUPPORT_REQUIRED` | `-32008` | `400` | 必需扩展未被客户端支持。 |
| `VERSION_NOT_SUPPORTED` | `-32009` | `400` | A2A 版本不支持。 |

无法归入上述标准错误的 Gateway 上游协议/传输失败，仍使用 Gateway 私有错误（例如 HTTP `502 GATEWAY_UPSTREAM_PROTOCOL_ERROR`）；这不应与上游标准 `INVALID_AGENT_RESPONSE` 混淆。

| HTTP | Gateway code | 常见原因 |
| ---: | --- | --- |
| 400 | `INVALID_ARGUMENT` / `GATEWAY_INVALID_REQUEST` | JSON、版本、参数、Binding 或方法不合法。 |
| 401 | `UNAUTHENTICATED` | 缺少或无效 JWT/API Key。过滤链中的认证失败可只返回状态码。 |
| 403 | `GATEWAY_POLICY_DENIED` | 租户、Agent、Skill 或 Task 权限不足。 |
| 404 | `GATEWAY_ROUTE_NOT_FOUND` | Agent/Task 不存在、跨租户或 Task Route 过期。 |
| 409 | `GATEWAY_ROUTE_CONFLICT` / `GATEWAY_DUPLICATE_IN_FLIGHT` | 路由不唯一或相同幂等键请求仍在执行。 |
| 413 | `REQUEST_TOO_LARGE` | 请求体超过限制。 |
| 429 | `GATEWAY_RATE_LIMITED` | 流式并发或配额超限。 |
| 502 | `GATEWAY_UPSTREAM_PROTOCOL_ERROR` | 上游响应不符合所选 A2A 1.0 Binding。 |
| 503 | `GATEWAY_AGENT_UNAVAILABLE` / `GATEWAY_OUTCOME_UNKNOWN` | 上游连接、凭据、接口、熔断或未知执行结果。 |
| 500 | `INTERNAL` | 未分类内部错误。 |

### 6.2 JSON-RPC

JSON-RPC 错误为 `{"jsonrpc":"2.0","id":null,"error":{"code":...,"message":...,"data":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":...,"domain":"a2a-protocol.org","metadata":{"gatewayCode":...}}]}}`。`error.data` 是 ProtoJSON `Any` 详情数组，不是 Gateway 私有对象。

| gatewayCode | JSON-RPC code | HTTP |
| --- | ---: | ---: |
| `GATEWAY_ROUTE_NOT_FOUND` | -32080 | 404 |
| `GATEWAY_ROUTE_CONFLICT` | -32081 | 409 |
| `GATEWAY_POLICY_DENIED` | -32083 | 403 |
| `GATEWAY_AGENT_UNAVAILABLE` | -32084 | 503 |
| `GATEWAY_UPSTREAM_PROTOCOL_ERROR` | -32006 | 502 |
| `GATEWAY_RATE_LIMITED` | -32085 | 429 |
| `UNAUTHENTICATED` | -32001 | 401 |
| `INVALID_ARGUMENT` / `GATEWAY_INVALID_REQUEST` | -32602 | 400 |
| 其他内部错误 | -32603 | 500 |

## 7. 明确不存在的 API

MVP 不提供以下北向接口：

- `POST`/`PUT`/`DELETE /gateway/v1/agents` 动态注册或管理；Agent 通过 Gateway YAML 静态注册。
- `POST /gateway/v1/agents/{agentId}/skills` 等运行时 Skill 管理；Skill 来自 Agent Card 的 `skills[]`。
- gRPC Binding、分布式任务恢复、跨副本 SSE 恢复。
- Gateway 自己托管的 Push Notification 存储或回调代理；现有 Push 配置 API 只是带 Task ID 映射的上游透传。

Agent 注册、鉴权配置、超时与大小限制见 [configuration.md](./configuration.md)；故障处理见 [runbook.md](./runbook.md)。
