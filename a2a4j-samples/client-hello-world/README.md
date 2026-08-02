# A2A4J Client Hello World 客户端示例

本示例演示如何使用 A2A4J 客户端发现 Agent Card，并通过 A2A 1.0 JSON-RPC 与 Agent 通信。应用启动后不会自动发送消息，而是在本地暴露两个便于测试的 HTTP 接口。

## 功能

- 从 `/.well-known/agent-card.json` 发现服务端能力和 JSON-RPC 地址
- 使用 `A2A-Version: 1.0` 调用 `SendMessage` 和 `SendStreamingMessage`
- 支持普通响应和 SSE 流式响应
- 自动生成 `messageId`

## 环境要求

- Java 17 或更高版本
- Maven 3.6 或更高版本，或使用仓库自带的 Maven Wrapper
- 已启动的 [`server-hello-world`](../server-hello-world/README.md)，默认地址为 `http://localhost:8089`

## 构建与运行

在仓库根目录执行：

```powershell
.\mvnw.cmd -pl a2a4j-samples/client-hello-world -am install -DskipTests
.\mvnw.cmd -pl a2a4j-samples/client-hello-world spring-boot:run
```

客户端监听 `http://localhost:8090`。服务端地址由 `client.a2a-server-url` 配置，默认值为 `http://localhost:8089`，也可以在启动时覆盖：

```powershell
.\mvnw.cmd -pl a2a4j-samples/client-hello-world spring-boot:run `
  "-Dspring-boot.run.arguments=--client.a2a-server-url=http://localhost:8091"
```

## 测试普通消息

```powershell
Invoke-RestMethod `
  'http://localhost:8090/a2a/client/send?message=hello'
```

本地入口会调用上游 `SendMessage` 并等待其响应。上游是否立即返回工作中任务，取决于发送参数中的 `configuration.returnImmediately`；当前示例未设置该字段，因此采用服务端默认行为。

## 测试流式消息

浏览器或支持 SSE 的客户端可访问：

```text
GET http://localhost:8090/a2a/client/stream/send?message=hello
```

curl 示例：

```bash
curl -N "http://localhost:8090/a2a/client/stream/send?message=hello"
```

该接口返回 `text/event-stream`，内部调用 A2A 1.0 的 `SendStreamingMessage`。旧版方法名 `message/send`、`message/stream` 不适用于当前 JSON-RPC 1.0 实现。

## 相关源码

- `src/main/java/io/github/a2ap/client/hello/world/controller/A2aClientController.java`：示例入口与客户端调用
- `src/main/resources/application.yml`：端口和上游服务地址

更完整的 JSON-RPC、HTTP+JSON、任务查询和订阅说明见[网关 API 文档](../../docs/agent-gateway/api-reference.md)。
