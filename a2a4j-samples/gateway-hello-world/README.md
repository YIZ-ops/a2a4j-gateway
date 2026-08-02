# A2A4J Gateway Hello World 网关示例

本示例启动一个 A2A4J Gateway，并连接两个独立运行的 A2A 1.0 Agent，用于演示 Agent Card 发现、按 Agent 或 Skill 路由、API Key 鉴权，以及 JSON-RPC/HTTP+JSON 转发。

## 运行拓扑

| 进程 | 地址 | 用途 |
| --- | --- | --- |
| `echo-a` | `http://localhost:8091` | `hello-world`、`code-generation` |
| `echo-b` | `http://localhost:8092` | `task-summary` |
| Gateway | `http://localhost:8099` | 统一鉴权、路由和转发 |

Gateway 每 30 秒从两个 Agent 的 `/.well-known/agent-card.json` 刷新能力信息。默认租户为 `demo`，默认 Agent 为 `echo-a`。

## 构建

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/gateway-hello-world -am package -DskipTests
```

服务端和网关的可执行 Spring Boot 包均为 `*-exec.jar`。

## 启动

先在当前终端设置示例 API Key：

```powershell
$env:A2A_SAMPLE_API_KEY = 'change-me-locally'
```

在两个独立终端启动 Agent：

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-a --server.port=8091
```

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-b --server.port=8092
```

在已设置 `A2A_SAMPLE_API_KEY` 的终端启动 Gateway：

```powershell
java -jar a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar
```

## HTTP+JSON 调用

所有数据面请求都应携带 `A2A-Version: 1.0` 和 `X-A2A-API-Key`。

### 发送消息

```powershell
$headers = @{
  'A2A-Version' = '1.0'
  'X-A2A-API-Key' = $env:A2A_SAMPLE_API_KEY
}
$sendHeaders = $headers.Clone()
$sendHeaders['X-A2A-Target-Skill'] = 'code-generation'
$body = @{
  message = @{
    messageId = 'msg-001'
    role = 'ROLE_USER'
    parts = @(@{ text = '生成一个 Java 服务示例'; mediaType = 'text/plain' })
  }
} | ConvertTo-Json -Depth 8

Invoke-RestMethod 'http://localhost:8099/message:send' -Method Post `
  -Headers $sendHeaders -ContentType 'application/a2a+json' -Body $body
```

- `X-A2A-Target-Agent: echo-b`：显式路由到指定 Agent。
- `X-A2A-Target-Skill: code-generation`：按 Agent Card 中声明的 Skill 路由；无需同时指定 Agent。
- 两者都未指定时，使用租户默认 Agent `echo-a`。

### 立即返回并查询任务

在请求体中加入 `configuration.returnImmediately: true`，`SendMessage` 会尽快返回当前任务快照，任务继续在后台执行：

```json
{
  "message": {
    "messageId": "msg-async-001",
    "role": "ROLE_USER",
    "parts": [{ "text": "hello", "mediaType": "text/plain" }]
  },
  "configuration": {
    "returnImmediately": true
  }
}
```

从响应中取得 `taskId` 后，可查询或订阅：

```powershell
Invoke-RestMethod 'http://localhost:8099/tasks/{taskId}' -Headers $headers
curl.exe -N -H "A2A-Version: 1.0" -H "X-A2A-API-Key: $env:A2A_SAMPLE_API_KEY" `
  -H "Accept: text/event-stream" -H "Content-Type: application/a2a+json" `
  -d '{}' -X POST "http://localhost:8099/tasks/{taskId}:subscribe"
```

订阅活动任务时，第一条事件是当前完整 `Task` 快照；后续状态和工件事件携带相同的 `taskId` 与 `contextId`。已进入终态的任务不能重新订阅，应改用查询任务接口读取最终结果。

## JSON-RPC 调用

JSON-RPC 入口为 `/a2a`，也支持 `/gateway/v1/a2a` 和 `/gateway/v1/agents/{agentId}/a2a`。普通方法使用 `Accept: application/json`，`SendStreamingMessage` 与 `SubscribeToTask` 使用 `Accept: text/event-stream`。

```powershell
$rpcBody = @{
  jsonrpc = '2.0'
  id = 'rpc-001'
  method = 'SendMessage'
  params = @{
    message = @{
      messageId = 'msg-rpc-001'
      role = 'ROLE_USER'
      parts = @(@{ text = 'hello'; mediaType = 'text/plain' })
    }
    configuration = @{ returnImmediately = $true }
  }
} | ConvertTo-Json -Depth 8

Invoke-RestMethod 'http://localhost:8099/a2a' -Method Post `
  -Headers $headers -ContentType 'application/json' -Body $rpcBody
```

网关支持 11 个 A2A 1.0 JSON-RPC 方法及完整 HTTP+JSON 路由；各方法请求、响应、错误码与 Postman 测试流程见[网关 API 文档](../../docs/agent-gateway/api-reference.md)。

## 管理与限制

- Agent 列表：`GET /agents`
- Agent Card：`GET /agents/{agentId}/card`
- 健康检查：`GET /actuator/health`
- Prometheus：`GET /actuator/prometheus`
- 本示例允许访问本机 HTTP Agent，仅用于开发演示。
- API Key、任务存储和幂等存储均为本地演示配置；进程重启后任务亲和信息会丢失。
