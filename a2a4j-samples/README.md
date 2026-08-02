# A2A4J 示例

本目录包含三个可独立运行的示例，用于演示 A2A 1.0 的服务端、客户端和网关集成。

| 示例 | 默认端口 | 说明 |
| --- | ---: | --- |
| [`server-hello-world`](server-hello-world/README.md) | 8089 | A2A 1.0 JSON-RPC Agent，支持普通消息、流式消息、任务查询与订阅 |
| [`client-hello-world`](client-hello-world/README.md) | 8090 | 发现 Agent Card，并调用普通与流式消息接口 |
| [`gateway-hello-world`](gateway-hello-world/README.md) | 8099 | 连接两个 Agent，演示鉴权、发现、路由和 JSON-RPC/HTTP+JSON 转发 |

## 快速体验服务端与客户端

在仓库根目录构建：

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/client-hello-world -am install -DskipTests
```

先启动服务端：

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar
```

再启动客户端：

```powershell
.\mvnw.cmd -pl a2a4j-samples/client-hello-world spring-boot:run
```

调用客户端入口：

```text
GET http://localhost:8090/a2a/client/send?message=hello
GET http://localhost:8090/a2a/client/stream/send?message=hello
```

## 快速体验网关

网关示例需要在 8091、8092 分别启动 `echo-a`、`echo-b` 两个服务端 Profile，再启动 8099 端口的 Gateway。完整命令、API Key 和请求示例见 [`gateway-hello-world/README.md`](gateway-hello-world/README.md)。

## 文档约定

样例目录及各子项目只保留一份 UTF-8 中文 `README.md`，不再维护单独的英文版或 `README_CN.md`，避免内容漂移。

协议与网关完整接口说明见[网关 API 文档](../docs/agent-gateway/api-reference.md)。
