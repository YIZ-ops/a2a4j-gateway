# A2A4J Server Hello World 服务端示例

本示例使用 A2A4J 构建一个支持 A2A 1.0 JSON-RPC 的 Agent 服务端，展示 Agent Card、同步/异步任务、SSE 流式响应、任务查询与任务订阅。

## 功能

- Agent Card：`GET /.well-known/agent-card.json`
- JSON-RPC：`POST /a2a/server`
- 普通方法返回 `application/json`
- `SendStreamingMessage`、`SubscribeToTask` 返回 `text/event-stream`
- 示例执行器产生工作状态、文本/代码/摘要工件及最终完成状态
- `echo-a`、`echo-b` Profile 可作为 Gateway 的两个独立上游 Agent

当前实现使用 A2A 1.0 方法名，例如 `SendMessage`、`SendStreamingMessage`、`GetTask`、`ListTasks`、`CancelTask` 和 `SubscribeToTask`。旧版 `message/send`、`message/stream` 不是 JSON-RPC 1.0 方法名。

## 环境要求

- Java 17 或更高版本
- Maven 3.6 或更高版本，或仓库自带的 Maven Wrapper
- curl、Postman 等 HTTP/SSE 客户端

## 构建与运行

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world -am package -DskipTests
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar
```

默认监听 `http://localhost:8089`。默认配置的 Agent Card 可能声明多个协议绑定，而本示例控制器实际提供的是 `/a2a/server` JSON-RPC 端点；作为真实上游使用时，应像 `echo-a`、`echo-b` Profile 一样设置：

```yaml
a2a:
  server:
    protocol-bindings: [JSONRPC]
```

## 检查服务与 Agent Card

```bash
curl http://localhost:8089/actuator/health
curl http://localhost:8089/.well-known/agent-card.json
```

需要认证的扩展 Agent Card 示例端点为 `/a2a/agent/authenticatedExtendedCard`，示例 API Key 是 `your-secure-api-key`；该固定密钥只用于演示。

## SendMessage：发送消息

不设置 `configuration.returnImmediately` 时，服务端按默认方式等待执行结果：

```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "id": "rpc-001",
    "method": "SendMessage",
    "params": {
      "message": {
        "messageId": "msg-001",
        "role": "ROLE_USER",
        "parts": [{"text": "hello", "mediaType": "text/plain"}]
      }
    }
  }'
```

`SendMessage` 本身不是 SSE 方法。即使执行过程中产生多次状态更新，普通请求也只返回一个 JSON-RPC 响应。

## 异步任务：returnImmediately、GetTask 与 SubscribeToTask

若希望快速取得任务快照并让任务在后台继续运行，在 `params` 中设置：

```json
"configuration": {
  "returnImmediately": true
}
```

完整请求如下：

```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "id": "rpc-async-001",
    "method": "SendMessage",
    "params": {
      "message": {
        "messageId": "msg-async-001",
        "role": "ROLE_USER",
        "parts": [{"text": "hello", "mediaType": "text/plain"}]
      },
      "configuration": {"returnImmediately": true}
    }
  }'
```

从响应的 `result.task.id` 或 `result.statusUpdate.taskId` 取得任务 ID，具体字段取决于返回的结果变体。之后可轮询：

```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "id": "rpc-get-001",
    "method": "GetTask",
    "params": {"id": "替换为任务ID"}
  }'
```

也可以在任务仍处于活动状态时订阅：

```bash
curl -N -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "id": "rpc-sub-001",
    "method": "SubscribeToTask",
    "params": {"id": "替换为任务ID"}
  }'
```

订阅建立后第一条事件是当前完整 `Task` 快照，后续事件继续使用相同的 `taskId` 和 `contextId`。任务已完成、失败、取消或拒绝时不能重新订阅，服务端返回任务不可订阅错误；此时使用 `GetTask` 获取最终结果。

由于示例执行器完成很快，人工复制任务 ID 时任务可能已经完成。使用 Postman 测试订阅时，可先增大示例执行器延迟，或通过脚本立即发起订阅。

## SendStreamingMessage：流式发送消息

```bash
curl -N -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "id": "rpc-stream-001",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "messageId": "msg-stream-001",
        "role": "ROLE_USER",
        "parts": [{"text": "生成一个 Java 类", "mediaType": "text/plain"}]
      }
    }
  }'
```

客户端必须按 SSE 持续读取；任务生命周期流的第一条事件是完整 `Task`，后续才是状态或 Artifact 更新，直到终态事件或连接关闭。

## 启动 Gateway 演示 Agent

在两个终端分别运行：

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-a --server.port=8091
```

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-b --server.port=8092
```

`echo-a` 声明 `hello-world`、`code-generation`，`echo-b` 声明 `task-summary`。随后按 [`gateway-hello-world`](../gateway-hello-world/README.md) 文档启动网关。

## 代码位置

- `A2AServerController`：Agent Card、JSON-RPC 普通响应和 SSE 入口
- `DemoAgentExecutor`：状态、工件与终态事件生成
- `application.yml`：默认 Agent 配置
- `application-echo-a.yml`、`application-echo-b.yml`：Gateway 演示 Profile

完整的 11 个 JSON-RPC 方法、请求响应结构、错误码及 HTTP+JSON 对照见[网关 API 文档](../../docs/agent-gateway/api-reference.md)。

## 常见问题

- 端口占用：通过 `--server.port=新端口` 覆盖。
- `SendMessage` 返回 `COMPLETED`：未设置 `returnImmediately: true`，或任务在初始快照返回前已快速完成。
- SSE 没有逐条显示：确认请求头为 `Accept: text/event-stream`，curl 使用 `-N`。
- `SubscribeToTask` 报错：确认任务 ID 正确且任务尚未进入终态。
- Agent Card 与实际入口不一致：将 `a2a.server.protocol-bindings` 限定为 `[JSONRPC]`。
