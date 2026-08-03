# 外部 Agent 接入 Gateway 指南

本文说明如何将真实的外部 Agent 注册到 A2A4J Gateway，并通过 Gateway 调用它。

外部 Agent 不要求使用 Java，也不要求使用 A2A4J 的 Server Starter；但必须对外提供符合当前 Gateway MVP 要求的 A2A 1.0 接口。

## 1. 接入架构

```text
调用方
  |
  | A2A 1.0 / Gateway API
  v
Gateway
  | 读取 Agent Card、路由、鉴权、协议转换
  | A2A 1.0 / JSON-RPC 或 HTTP+JSON
  v
外部 Agent
```

Gateway 负责：

- 静态注册外部 Agent；
- 拉取并校验 Agent Card；
- 根据 Agent、Skill 和租户进行路由；
- 选择 JSON-RPC 或 HTTP+JSON 出站接口；
- 处理出站凭证、超时、响应大小和 SSRF 网络策略；
- 改写 Gateway Task ID/Context ID，并保存任务路由；
- 将外部 Agent 的结果返回给调用方。

当前 MVP 不提供运行时 Agent 注册 API。Agent 通过 Gateway YAML 静态注册，修改配置后需要重启 Gateway。
Gateway 也不会代理任意 REST/OpenAI API；外部服务必须先暴露 A2A 1.0 JSON-RPC 或 HTTP+JSON 接口。

## 2. 外部 Agent 的最低要求

### 2.1 Agent Card

外部 Agent 必须提供一个 Gateway 可以访问的 Agent Card URL。Gateway 启动时以及后续刷新时会发送：

```http
GET <card-url>
Accept: application/json
A2A-Version: 1.0
```

推荐使用标准地址：

```text
https://agent.example.com/.well-known/agent-card.json
```

当前 Gateway 的内置 Card 拉取器不会为 Card 请求自动添加 `credential-ref` 中的凭证，因此 Card URL 默认必须允许 Gateway 直接读取。如果 Card 必须鉴权，需要提供自定义 `AgentCardFetcher`。

Card 至少需要包含以下字段：

```json
{
  "name": "Research Agent",
  "description": "External research agent",
  "version": "1.0.0",
  "supportedInterfaces": [
    {
      "url": "https://agent.example.com/a2a",
      "protocolBinding": "JSONRPC",
      "protocolVersion": "1.0"
    }
  ],
  "capabilities": {
    "streaming": false,
    "pushNotifications": false,
    "stateTransitionHistory": true
  },
  "defaultInputModes": ["text/plain"],
  "defaultOutputModes": ["text/plain"],
  "skills": [
    {
      "id": "research",
      "name": "Research",
      "description": "Researches a topic",
      "tags": ["research"]
    }
  ]
}
```

Gateway 会校验：

- Card 是合法 JSON 对象；
- `name`、`description`、`version` 非空；
- `supportedInterfaces`、`capabilities`、`defaultInputModes`、`defaultOutputModes` 和 `skills` 存在且为非空结构；
- 每个接口包含 `url`、`protocolBinding` 和 `protocolVersion`；
- 当前协议版本为 `1.0`；
- 每个 Skill 包含 `id`、`name`、`description` 和 `tags`；
- Card 中声明的接口地址通过 Gateway 网络策略校验。

`skills[].id` 是 Skill 的稳定标识。调用方使用 `X-A2A-Target-Skill` 指定 Skill 时，必须使用这个 ID。

### 2.2 A2A 数据接口

当前 Gateway 内置支持：

- A2A 1.0 JSON-RPC；
- A2A 1.0 HTTP+JSON；
- SSE 流式响应。

Gateway 默认优先选择 JSON-RPC，只有目标 Agent 没有 JSON-RPC 接口时才选择 HTTP+JSON。

Card 虽然可以声明 `GRPC`，但当前 Gateway 没有 gRPC 出站 Adapter，因此不能只提供 gRPC 接口。

外部 Agent 至少应根据实际能力实现以下方法：

| 能力 | A2A 方法 |
| --- | --- |
| 普通消息 | `SendMessage` |
| 流式消息 | `SendStreamingMessage` |
| 查询单个任务 | `GetTask` |
| 取消任务 | `CancelTask` |
| 订阅任务 | `SubscribeToTask` |
| Push Notification 配置 | `CreateTaskPushNotificationConfig`、`GetTaskPushNotificationConfig`、`ListTaskPushNotificationConfigs`、`DeleteTaskPushNotificationConfig` |
| 扩展 Agent Card | `GetExtendedAgentCard` |

只需要同步消息时，至少实现 `SendMessage`。如果 Gateway 调用方需要查询、取消、订阅、Push Notification 或扩展 Card，外部 Agent 也必须实现对应方法并在 `capabilities` 中声明相应能力。

Gateway 的 `ListTasks` 是例外：它从本地 `TaskRouteStore` 投影已保存的任务快照，不会向外部 Agent 调用`ListTasks`。因此外部 Agent 不需要为了支持 Gateway 的任务列表而实现该方法；Gateway 重启后，默认内存 Store 中的列表和路由都会丢失。

外部 Agent 对执行模式和订阅必须满足 A2A 1.0：

- `SendMessage.configuration.returnImmediately` 缺失或为 false 时等待 Task 到达终态或
  `INPUT_REQUIRED`/`AUTH_REQUIRED`；为 true 时创建 Task 后立即返回当前活动 Task，后台继续执行。
- `SubscribeToTask` 只接受非终态 Task，第一条流事件必须是当前完整 Task，随后才是
  `TaskStatusUpdateEvent`/`TaskArtifactUpdateEvent`。
- 所有 Task、状态事件和 Artifact 事件的 `contextId` 必须非 null 且在同一任务生命周期内一致。
- 对 `COMPLETED`、`FAILED`、`CANCELED`、`REJECTED` Task 再订阅时返回
  `UnsupportedOperationError (-32004)`，不能返回 `contextId: null` 的伪完成事件。

## 3. Gateway 注册配置

下面是一个生产环境方向的 JSON-RPC Agent 配置：

```yaml
a2a:
  gateway:
    enabled: true

    # 生产环境默认使用 HTTPS，并阻止私网目标
    allow-http: false
    allow-private-network: false

    card-timeout: 5s
    connect-timeout: 2s
    response-timeout: 60s
    refresh-interval: 5m
    unhealthy-after-failures: 3

    default-agent-by-tenant:
      tenant-a: research-agent

    agents:
      - tenant-id: tenant-a
        agent-id: research-agent
        display-name: Research Agent
        enabled: true
        routing-labels:
          region: cn
          tier: production
        protocol-versions: ["1.0"]
        protocol-bindings: ["JSONRPC"]
        instances:
          - instance-id: research-agent-1
            card-url: https://agent.example.com/.well-known/agent-card.json
            weight: 100
            credential-ref: env://RESEARCH_AGENT_TOKEN
```

字段要求：

| 字段 | 要求 |
| --- | --- |
| `tenant-id` | 必填；用于租户隔离，必须与调用方身份中的租户一致 |
| `agent-id` | 必填；同一租户内唯一 |
| `display-name` | 可选；用于目录展示 |
| `enabled` | 是否允许参与目录和路由，默认 `true` |
| `protocol-versions` | 当前应包含 `1.0` |
| `protocol-bindings` | 应与 Card 中的接口匹配；可用 `JSONRPC` 或 `HTTP+JSON` |
| `instance-id` | 必填；同一个 Agent 下唯一 |
| `card-url` | 必填；Gateway 拉取 Agent Card 的地址 |
| `weight` | 必须大于 0；多个实例时用于负载选择 |
| `credential-ref` | 可选；指定出站调用凭证 |

一个逻辑 Agent 可以配置多个实例。每个实例可以有不同的 Card URL、权重和出站凭证，Gateway 会根据健康状态和负载选择实例。
这些实例必须代表同一个逻辑能力集合。当前快照的 Skills 和 capabilities 以配置中第一个实例的 Card 为准，Gateway 不会在启动时比较所有实例的完整能力一致性；部署方应保证各实例的 Skill ID、流式、Push 和扩展 Card 能力一致。

## 4. 网络和 URL 要求

默认网络策略为生产安全配置：

- Card URL 和上游接口默认必须使用 HTTPS；
- 默认拒绝 `localhost`、回环地址、link-local 地址、内网地址和云元数据地址；
- Gateway 会解析目标域名并再次检查解析结果，防止 DNS rebinding；
- 不跟随 HTTP 重定向；
- Card、普通响应和单个 SSE 事件都有大小限制；
- 连接、响应和流式空闲时间都有超时限制。

开发环境访问本机或私网 Agent 时，可以临时配置：

```yaml
a2a:
  gateway:
    allow-http: true
    allow-private-network: true
```

生产环境不建议全局开启 `allow-private-network`。如果必须访问内网，优先配置明确的 `allowed-cidrs`，并限制 Gateway 所在网络的出站访问范围。

## 5. 出站认证

Gateway 默认提供基于环境变量的凭证解析：

```yaml
credential-ref: env://RESEARCH_AGENT_TOKEN
```

启动 Gateway 前设置：

```powershell
$env:RESEARCH_AGENT_TOKEN = "your-agent-access-token"
```

默认出站请求会携带：

```http
Authorization: Bearer your-agent-access-token
```

注意：

- Gateway 的入站认证和到外部 Agent 的出站认证是两套独立配置；
- `credential-ref` 不会把 secret 写入 YAML；
- 未配置 `credential-ref` 时，默认不携带出站认证；
- 当前默认实现不支持 `X-API-Key`、自定义签名或 OAuth token 自动刷新；
- 外部 Agent 使用这些认证方式时，需要实现自定义 `CredentialProvider` 或 `AgentTransport`；
- 默认 Card 拉取请求不会携带该凭证，需要公共 Card 地址或自定义 `AgentCardFetcher`。

## 6. Gateway 入站鉴权和权限

生产环境建议启用 JWT/OIDC：

```yaml
a2a:
  gateway:
    security:
      enabled: true
      mode: jwt
      jwt:
        issuer-uri: https://id.example.com
        audiences: [a2a-gateway]
        tenant-claim: tenant_id
        subject-claim: sub
```

调用方的身份租户必须满足：

```text
JWT tenant claim == a2a.gateway.agents[].tenant-id
```

常用权限如下：

| 操作 | 所需权限 |
| --- | --- |
| 查看 Agent 目录 | 已认证 |
| 发送消息 | `agent:invoke` 或 `agent:invoke:{agentId}` |
| 指定 Skill 调用 | `skill:invoke` 或 `skill:invoke:{skillId}` |
| 查询任务、订阅任务 | `task:read` |
| 管理 Push Notification 配置 | `task:read` |
| 取消任务 | `task:cancel` |
| 获取扩展 Agent Card | `agent:discover` |

`*` 权限仅适合本地 Demo，不建议用于生产身份。

## 7. JSON-RPC 外部接口示例

如果 Card 声明了 JSON-RPC 接口，例如：

```json
{
  "url": "https://agent.example.com/a2a",
  "protocolBinding": "JSONRPC",
  "protocolVersion": "1.0"
}
```

则外部 Agent 应能够处理：

```http
POST /a2a
Content-Type: application/json
Accept: application/json
A2A-Version: 1.0
Authorization: Bearer <token>
```

请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": "request-001",
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "message-001",
      "role": "ROLE_USER",
      "parts": [
        {
          "text": "请研究 A2A 协议的主要能力"
        }
      ]
    }
  }
}
```

同步响应应返回合法的 JSON-RPC 2.0 响应。流式请求需要使用：

```http
Accept: text/event-stream
```

并返回合法 SSE 事件。Gateway 不会聚合整个流，而是逐事件转发给调用方。

如果 Card 声明 `HTTP+JSON`，其中的 `url` 是 A2A HTTP+JSON 基础地址。Gateway 会在该地址下调用`message:send`、`message:stream`、`tasks/{id}`、`tasks/{id}:cancel`、`tasks/{id}:subscribe`、Push Notification 配置和 `extendedAgentCard` 等规范相对路径；不要把 `url` 配成某一个具体操作路径。

## 8. 通过 Gateway 调用

### 8.1 HTTP+JSON

使用显式 Agent 路径最容易排查：

```http
POST /gateway/v1/agents/research-agent/message:send
Content-Type: application/a2a+json
A2A-Version: 1.0
Authorization: Bearer <gateway-jwt>
```

请求体：

```json
{
  "message": {
    "messageId": "message-001",
    "role": "ROLE_USER",
    "parts": [
      {
        "text": "请研究 A2A 协议的主要能力"
      }
    ]
  },
  "configuration": {
    "acceptedOutputModes": ["text/plain"],
    "returnImmediately": true
  }
}
```

### 8.2 JSON-RPC

```http
POST /gateway/v1/agents/research-agent/a2a
Content-Type: application/json
Accept: application/json
A2A-Version: 1.0
Authorization: Bearer <gateway-jwt>
```

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-001",
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "message-001",
      "role": "ROLE_USER",
      "parts": [
        {
          "text": "请研究 A2A 协议的主要能力"
        }
      ]
    },
    "configuration": {
      "returnImmediately": true
    }
  }
}
```

如果 Agent 返回 Task，Gateway Task ID 位于 HTTP+JSON 的 `task.id` 或 JSON-RPC 的 `result.task.id`；Agent 也可以直接返回 Message，此时不存在可查询或订阅的 Task ID。任务仍活动时可使用
`POST /gateway/v1/agents/research-agent/tasks/{taskId}:subscribe`，或发送 JSON-RPC
`SubscribeToTask` 并将该 Gateway Task ID 放入 `params.id`。两种 Binding 都必须设置
`Accept: text/event-stream`；订阅首条数据应是完整 Task。

也可以使用路由 Header：

```http
X-A2A-Target-Agent: research-agent
```

如果不指定 Agent，Gateway 会依次尝试：

1. 已存在的 Gateway Task 路由；
2. 已存在的 Gateway Context 路由；
3. 显式 Agent 路径或 `X-A2A-Target-Agent`；二者不一致时拒绝请求；
4. `X-A2A-Target-Skill`；
5. 当前租户的 `default-agent-by-tenant`。

公开 HTTP/JSON-RPC 入口不会接收任意路由标签；`routing-labels` 只供受信任扩展代码构造 `GatewayCommand` 时使用。

### 8.3 任务 ID

Gateway 会将外部 Agent 的 Task ID 和 Context ID 映射为 Gateway 自己的 ID。后续查询、订阅和取消必须使用 Gateway 返回的 ID：

```text
不要把外部 Agent 返回的内部 taskId 直接用于 Gateway 的任务接口。
```

例如：

```http
GET /gateway/v1/tasks/<gateway-task-id>
A2A-Version: 1.0
Authorization: Bearer <gateway-jwt>
```

Gateway 重启后，当前 MVP 的内存任务路由会丢失；需要持久化任务路由时，应替换默认的 `TaskRouteStore`。

## 9. 接入验证清单

### 9.1 从 Gateway 主机检查 Card

```powershell
Invoke-WebRequest `
  -Uri "https://agent.example.com/.well-known/agent-card.json" `
  -Headers @{ "A2A-Version" = "1.0"; "Accept" = "application/json" }
```

确认：

- HTTP 状态码为 `200`；
- 返回体是 JSON；
- Card 声明 `protocolVersion: "1.0"`；
- Card 的接口 URL 从 Gateway 主机可访问；
- Card 的 Binding 与 Gateway 配置一致。

### 9.2 检查 Gateway 目录

```http
GET /gateway/v1/agents
```

确认目标 Agent 已出现在当前调用租户的目录中，并且实例健康状态为 `HEALTHY`。

### 9.3 检查 Gateway 合成 Card

```http
GET /gateway/v1/agents/research-agent/card
```

如果该接口返回 Agent Card，说明 Card 已经通过 Gateway 的结构、协议和 URL 校验。

### 9.4 检查健康状态

```http
GET /actuator/health
```

Starter 注册 `gatewayAgentHealthIndicator` 和 `gatewayDependencyHealthIndicator`，但不会自动创建 readiness/liveness 分组。只有应用自行配置 Spring Boot Availability Probe 或 health group 后，才能依赖`/actuator/health/readiness`。如果实例反复进入 `DEGRADED` 或 `UNHEALTHY`，重点检查 Card 拉取、DNS、TLS、网络策略、接口地址和外部 Agent 的响应状态；当前没有独立的上游 `/health` 主动探测。

## 10. 常见故障

| 现象 | 常见原因 |
| --- | --- |
| Agent 不出现在目录 | Card 初次拉取失败、租户配置错误或 Agent 被禁用 |
| Card 校验失败 | 使用旧版 A2A Card、缺少必填字段或 `protocolVersion` 不是 `1.0` |
| `NETWORK_POLICY` | 使用 HTTP、私网地址或 DNS 解析到了被禁止的地址 |
| `target Agent has no ... interface` | Card、`protocol-bindings` 和实际接口不一致 |
| `401`/`403` | Gateway 入站凭证、租户或权限不满足 |
| 上游 `401` | 外部 Agent 的出站 token 无效，或 `credential-ref` 未配置 |
| 上游 `404` | Card 中声明的接口 URL 不是真实调用地址 |
| 上游 `502` | 外部响应不是合法 A2A 1.0 响应、超时或响应超过大小限制 |
| 流式调用中断 | 未返回 `text/event-stream`、SSE 事件格式错误或超过空闲超时 |
| 任务后续操作找不到 | 使用了外部 Agent 的 Task ID，或 Gateway 重启后内存路由丢失 |

## 11. 不适合直接注册的 Agent

以下 Agent 不能直接注册到当前 Gateway：

- 只提供普通 REST API；
- 只提供 OpenAI-compatible API；
- 只提供 WebSocket 或自定义 TCP 协议；
- 只提供旧版 A2A 0.2.x 协议；
- 只提供 gRPC，而没有 JSON-RPC 或 HTTP+JSON 接口。

这类 Agent 需要先增加一个 A2A 适配层，至少暴露：

1. 可访问的 A2A 1.0 Agent Card；
2. A2A 1.0 JSON-RPC 或 HTTP+JSON 数据接口；
3. Gateway 可使用的认证方式；
4. 与 Card 中声明一致的任务、响应和流式语义。

相关项目文档：

- [Gateway 配置指南](./configuration.md)
- [Gateway API 参考](./api-reference.md)
- [Gateway 运行手册](./runbook.md)
