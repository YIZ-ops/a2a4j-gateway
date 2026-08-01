# Gateway API 参考

本文是当前 A2A4J Agent Gateway MVP 的**完整北向 API 清单**。内容以
`GatewayAgentCatalogController`、`GatewayHttpJsonController`、`GatewayJsonRpcController` 和异常处理器的实际实现为准。

Gateway 基地址示例为 `http://localhost:8099`。所有业务 API 均是租户隔离的：租户取自已认证身份，客户端不能通过路径、Header 或请求体指定或覆盖租户。

## 1. 适用范围与通用约定

### 1.1 协议和内容类型

| API 类型 | 请求 | 成功响应 |
| --- | --- | --- |
| Agent 目录 | 无 | `application/json` |
| HTTP+JSON 非流式 | `application/json` 或 `application/a2a+json` | `application/a2a+json` |
| HTTP+JSON 流式 | `application/json` 或 `application/a2a+json` | `text/event-stream` |
| JSON-RPC 非流式 | `application/json` 或 `application/a2a+json` | `application/json`（JSON-RPC 2.0 envelope） |
| JSON-RPC 流式 | `application/json` 或 `application/a2a+json`，`Accept: text/event-stream` | `text/event-stream`；每个 `data` 是 JSON-RPC 2.0 对象 |

- 当前唯一支持的 A2A 协议版本为 `1.0`。建议所有请求携带 `A2A-Version: 1.0`；未携带时按 1.0 处理，显式传入其他值返回 `400`。
- Gateway 对外可同时接收 HTTP+JSON 与 JSON-RPC；Gateway 到上游 Agent 根据 Card 的 `supportedInterfaces` 选择 Binding，默认优先 JSON-RPC，只有目标仅支持 HTTP+JSON 时才选择 HTTP+JSON。
- 新建或返回任务时，Gateway 会改写 Task/Context ID。后续调用必须使用 Gateway 返回的 ID，不能使用上游 Agent 的内部 ID。
- 版本化路径与短路径功能等价；新客户端建议使用 `/gateway/v1/...` 路径，短路径保留为简洁入口。

### 1.2 鉴权与公共 Header

除下文明确说明的 Actuator 健康端点外，所有 API 均需要认证。

| Header | 适用模式 | 说明 |
| --- | --- | --- |
| `Authorization: Bearer <JWT>` | JWT | JWT/OIDC 入站认证。 |
| `X-A2A-API-Key: <secret>` | API Key | 本地开发模式；实际名称可用 `a2a.gateway.security.api-key.header-name` 修改。 |
| `A2A-Version: 1.0` | 全部业务 API | A2A 版本协商。 |
| `X-A2A-Target-Agent: <agentId>` | 数据面 | 显式逻辑 Agent 路由提示。 |
| `X-A2A-Target-Skill: <skillId>` | 数据面 | Skill 路由提示；Gateway 会校验目标 Agent 具有该 Skill。 |
| `Idempotency-Key: <key>` | 写操作 | 同租户内新建消息任务的幂等键；同键不同请求内容返回冲突。 |
| `X-Gateway-Request-Id: <id>` | 全部业务 API | 请求关联 ID；缺省时 Gateway 生成 UUID。 |
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
| 发送消息、Push Notification JSON-RPC 方法 | `agent:invoke` 或 `agent:invoke:{agentId}`；指定 Skill 时还需 `skill:invoke` 或 `skill:invoke:{skillId}`。 |
| `GetTask`、`ListTasks`、`SubscribeToTask` | `task:read` |
| `CancelTask` | `task:cancel` |

`*` 仅适合本地示例。JWT scope/role 与 API Key authorities 都可提供上述权限。

## 2. 全量路径清单

### 2.1 Agent 目录

| 方法 | 规范路径 | 等价短路径 | 说明 |
| --- | --- | --- | --- |
| GET | `/gateway/v1/agents` | `/agents` | 列出当前租户可见 Agent。 |
| GET | `/gateway/v1/agents/{agentId}` | `/agents/{agentId}` | 查询一个 Agent 摘要。 |
| GET | `/gateway/v1/agents/{agentId}/card` | `/agents/{agentId}/card` | 查询 Gateway 根据快照合成的 Agent Card。 |

### 2.2 HTTP+JSON 数据面

| 方法 | 规范路径 | 短路径 | 显式 Agent 路径 | 响应 |
| --- | --- | --- | --- | --- |
| POST | `/gateway/v1/message:send` | `/message:send` | `/gateway/v1/agents/{agentId}/message:send` | JSON |
| POST | `/gateway/v1/message:stream` | `/message:stream` | `/gateway/v1/agents/{agentId}/message:stream` | SSE |
| GET | `/gateway/v1/tasks/{taskId}` | `/tasks/{taskId}` | `/gateway/v1/agents/{agentId}/tasks/{taskId}` | JSON |
| GET | `/gateway/v1/tasks` | `/tasks` | `/gateway/v1/agents/{agentId}/tasks` | JSON |
| POST | `/gateway/v1/tasks/{taskId}:cancel` | `/tasks/{taskId}:cancel` | `/gateway/v1/agents/{agentId}/tasks/{taskId}:cancel` | JSON |
| POST | `/gateway/v1/tasks/{taskId}:subscribe` | `/tasks/{taskId}:subscribe` | `/gateway/v1/agents/{agentId}/tasks/{taskId}:subscribe` | SSE |

### 2.3 JSON-RPC 数据面

| 方法 | 规范路径 | 等价路径 | 响应选择 |
| --- | --- | --- | --- |
| POST | `/gateway/v1/a2a` | `/a2a`、`/gateway/v1/agents/{agentId}/a2a` | `Accept: application/json` 为同步 JSON-RPC；`Accept: text/event-stream` 为流式 JSON-RPC。 |

### 2.4 运维端点（sample 默认暴露）

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | 无 | 总体健康。 |
| GET | `/actuator/health/liveness` | 无 | 存活探针。 |
| GET | `/actuator/health/readiness` | 无 | 就绪探针。 |
| GET | `/actuator/info` | 无 | 应用信息。 |
| GET | `/actuator/prometheus` | 取决于应用安全配置 | Prometheus 抓取端点。 |

这些是 Spring Boot Actuator 端点，不是 A2A 协议端点；实际暴露集合由 `management.endpoints.web.exposure.include` 决定。

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

## 4. HTTP+JSON API

### 4.1 发送消息：`POST /gateway/v1/message:send`

请求体使用 A2A HTTP+JSON 消息 envelope。`message` 是推荐字段；`contextId`、`configuration` 可选。

```json
{
  "message": {
    "messageId": "msg-001",
    "role": "ROLE_USER",
    "parts": [{"text": "总结本季度行业变化"}]
  },
  "contextId": "client-context-001",
  "configuration": {"acceptedOutputModes": ["text/plain"]}
}
```

成功时返回 `200 application/a2a+json` 的上游结果投影，Task/Context 标识已改写为 Gateway 标识。请求体超过 `a2a.gateway.max-request-bytes` 时返回 `413 REQUEST_TOO_LARGE`。

### 4.2 流式发送：`POST /gateway/v1/message:stream`

请求体与发送消息相同，设置 `Accept: text/event-stream`。Gateway 不聚合上游流，逐事件返回：

```text
event: status-update
id: event-001
data: {"statusUpdate":{"taskId":"gateway-task-id","status":{"state":"WORKING"}}}
```

客户端应持续读取直到收到终态 `status.state`（例如 `TASK_STATE_COMPLETED`）；断开客户端连接会取消上游连接。`max-event-bytes`、`stream-idle-timeout` 与 `max-concurrent-streams` 约束事件、空闲和并发。

### 4.3 查询与列举任务

- `GET /gateway/v1/tasks/{taskId}`：`taskId` 必须是 Gateway 返回的 ID。跨租户、过期或不存在均返回不泄露内部 ID 的 `404 GATEWAY_ROUTE_NOT_FOUND`。
- `GET /gateway/v1/tasks`：查询参数会被透传为 A2A `ListTasks` 参数。常用项为 `pageSize`/`limit`、`pageToken`、`contextId`、`status`、`historyLength`、`includeArtifacts`。返回结构以选中上游的 A2A ListTasks 结果为准，Gateway 按当前主体和任务路由保护访问。

### 4.4 取消与订阅

- `POST /gateway/v1/tasks/{taskId}:cancel`：请求体可为空；建议发 `{}` 并携带 `Content-Type: application/json`。需要 `task:cancel`。
- `POST /gateway/v1/tasks/{taskId}:subscribe`：请求体可为空；设置 `Accept: text/event-stream`。需要 `task:read`，可带 `Last-Event-ID`。

两者都会根据持久化于内存的 Task Route 固定回创建任务时的 Agent 实例和 Binding；Gateway 重启后该 MVP 内存映射丢失。

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

### 5.2 支持的方法

| JSON-RPC method | 响应 | 主要 `params` | 权限/说明 |
| --- | --- | --- | --- |
| `SendMessage` | 同步 | `message`、`contextId`、`configuration` | 新建或推进任务。 |
| `SendStreamingMessage` | SSE | `message`、`contextId`、`configuration` | 新建或推进任务并逐事件返回。 |
| `GetTask` | 同步 | `id`、`historyLength` | `id` 为 Gateway Task ID；需要 `task:read`。 |
| `ListTasks` | 同步 | `contextId`、`status`、`pageSize`、`pageToken`、`historyLength`、`includeArtifacts` | 需要 `task:read`。 |
| `CancelTask` | 同步 | `id` | 需要 `task:cancel`。 |
| `SubscribeToTask` | SSE | `id` | 需要 `task:read`，可带 `Last-Event-ID`。 |
| `CreateTaskPushNotificationConfig` | 同步 | A2A Push 参数 | Adapter 可识别；**MVP 不提供 Gateway Task ID 映射、持久化或 Push 代理，不应作为可用 Gateway 功能使用**。 |
| `GetTaskPushNotificationConfig` | 同步 | A2A Push 参数 | 同上。 |
| `ListTaskPushNotificationConfigs` | 同步 | A2A 分页参数 | 同上。 |
| `DeleteTaskPushNotificationConfig` | 同步 | A2A Push 参数 | 同上。 |
| `GetExtendedAgentCard` | 同步 | A2A Card 参数 | 需要 `agent:discover`；仅当目标 Agent 实现该方法时可转发，一般优先使用目录 Card API。 |

任务方法优先读取 `X-Gateway-Task-Id`；未提供时，`GetTask`、`CancelTask`、`SubscribeToTask` 从 `params.id` 读取。

### 5.3 可直接使用的任务方法

```powershell
$body = '{"jsonrpc":"2.0","id":"rpc-001","method":"SendMessage","params":{"message":{"messageId":"msg-001","role":"ROLE_USER","parts":[{"text":"hello","mediaType":"text/plain"}]}}}'
curl.exe -X POST http://localhost:8099/gateway/v1/a2a `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "A2A-Version: 1.0" -H "X-A2A-Target-Agent: echo-a" `
  -H "Content-Type: application/json" -H "Accept: application/json" --data $body
```

先从 `SendMessage` 或 `SendStreamingMessage` 响应中保存 Gateway 返回的 Task ID：HTTP+JSON 非流式响应使用 `task.id`；JSON-RPC 非流式响应使用 `result.task.id`；JSON-RPC 流式响应使用首个事件的 `result.statusUpdate.taskId` 或 `result.artifactUpdate.taskId`。如果响应是直接 `message`，则没有可查询的 Task。以下任务方法均使用这个 Gateway ID：

Gateway ID 与 Agent 控制台中打印的上游 Task ID **预期不同**。Gateway 创建外部 ID 后，在内存 Task Route 中保存它到租户、调用主体、Agent 实例、Binding 和上游 Task ID 的映射；客户端只能使用 Gateway ID，控制台排障才在受控环境中关联上游 ID，二者都不应互相替换。

```json
{"jsonrpc":"2.0","id":"get-001","method":"GetTask","params":{"id":"gateway-task-id","historyLength":20}}
```

```json
{"jsonrpc":"2.0","id":"list-001","method":"ListTasks","params":{"contextId":"gateway-context-id","status":"WORKING","pageSize":20,"pageToken":"optional-token","historyLength":20,"includeArtifacts":false}}
```

```json
{"jsonrpc":"2.0","id":"cancel-001","method":"CancelTask","params":{"id":"gateway-task-id"}}
```

`SubscribeToTask` 使用同一入口，但必须协商 SSE：

```powershell
$body = '{"jsonrpc":"2.0","id":"subscribe-001","method":"SubscribeToTask","params":{"id":"gateway-task-id"}}'
curl.exe -N -X POST http://localhost:8099/gateway/v1/a2a `
  -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "A2A-Version: 1.0" -H "Accept: text/event-stream" `
  -H "Content-Type: application/json" --data $body
```

四个方法都可加 `X-A2A-Target-Agent`；对于 `GetTask`、`CancelTask`、`SubscribeToTask`，Gateway 已有 Task Route 时会固定回创建任务的原实例，Header 不能迁移任务。`ListTasks` 没有单任务 Route，建议显式指定 Agent 或为租户配置默认 Agent。

### 5.4 Push Notification 与扩展 Card 的当前边界

`CreateTaskPushNotificationConfig`、`GetTaskPushNotificationConfig`、`ListTaskPushNotificationConfigs`、`DeleteTaskPushNotificationConfig` 的名称和 JSON-RPC 请求可以被 Gateway 解析并转发，但当前 MVP **不会**：

- 将 `task_id`/`id` 中的 Gateway Task ID 转换为上游 Task ID；
- 持久化 Push 配置或在 Gateway 重启后恢复它；
- 代为接收、鉴权和投递 Agent 的回调通知。

所以不要向 Gateway 发送这四种任务 Push 调用；请直接调用明确声明 `pushNotifications: true` 且实现相应方法的 Agent，并传递该 Agent 的原生 Task ID。例如直接对上游 Agent 的 JSON-RPC 请求形状通常是：

```json
{
  "jsonrpc": "2.0",
  "id": "push-create-001",
  "method": "CreateTaskPushNotificationConfig",
  "params": {
    "task_id": "upstream-task-id",
    "url": "https://callback.example.com/a2a/events",
    "auth_token": "callback-secret"
  }
}
```

`echo-a`、`echo-b` 的 Card 不声明 `pushNotifications: true`，不应作为 Push 示例 Agent。

`GetExtendedAgentCard` 的 Gateway 调用形状如下，但只有上游明确实现该 JSON-RPC 方法时才成功：

```json
{"jsonrpc":"2.0","id":"card-001","method":"GetExtendedAgentCard","params":{}}
```

携带 `X-A2A-Target-Agent` 和 `agent:discover` 权限。对 Gateway 当前注册快照的常规查询，应优先使用 `GET /gateway/v1/agents/{agentId}/card`，它不依赖上游扩展 Card 方法。

## 6. 错误响应

### 6.1 HTTP+JSON 与目录

控制器接收请求后的错误形状固定为：

```json
{"error":{"code":503,"status":"UNAVAILABLE","message":"selected Agent is no longer available","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"AGENT_UNAVAILABLE","domain":"a2a-protocol.org","metadata":{"gatewayCode":"GATEWAY_AGENT_UNAVAILABLE"}}]}}
```

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
- Gateway 自己托管的 Push Notification 存储或回调代理。

Agent 注册、鉴权配置、超时与大小限制见 [configuration.md](./configuration.md)；故障处理见 [runbook.md](./runbook.md)。
