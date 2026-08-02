# A2A4J - Agent2Agent Java协议实现

[![Maven Central](https://img.shields.io/maven-central/v/io.github.a2ap/a2a4j)](https://search.maven.org/artifact/io.github.a2ap/a2a4j)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-green.svg)](https://openjdk.org/projects/jdk/17/)

📖 **[English Documentation](README.md)**

[Agent2Agent (A2A)](https://github.com/google-a2a/A2A) 协议为独立 AI 智能体系统之间的通信和互操作性提供开放标准。本仓库当前以 A2A 1.0 Wire 契约为基线。

[A2A4J](https://github.com/a2ap/a2a4j) 是 Agent2Agent (A2A) 协议的 Java 实现，包含可复用核心、Spring Boot 服务端/客户端 Starter，以及可扩展的 Agent Gateway。基于 Reactor 构建，支持智能体发布能力、交换任务和转发请求，同时不暴露彼此的内部状态。

## 🚀 功能特性

- ✅ **完整的 A2A 协议支持** - Agent2Agent 规范的完整实现
- ✅ **JSON-RPC 2.0 通信** - 基于标准的请求/响应消息传递
- ✅ **Server-Sent Events 流式处理** - 实时任务更新和流式响应
- ✅ **任务生命周期管理** - 全面的任务状态管理和监控
- ✅ **Spring Boot 集成** - 与 Spring Boot 应用程序轻松集成
- ✅ **响应式编程支持** - 基于 Reactor 构建，可扩展的非阻塞操作
- ✅ **多种内容类型** - 支持文本、文件和结构化数据交换
- ⚪️ **推送通知配置** - A2A 推送通知配置 API；实际投递由应用负责集成
- ✅ **Agent Card 发现机制** - A2A 1.0 Agent Card 和 Gateway well-known 发现入口
- ⚪️ **企业级安全** - 身份验证和授权支持

## 📋 环境要求

- **Java 17+** - 运行应用程序所需
- **Maven 3.6+** - 构建工具

仓库同时提供 Agent Gateway MVP：支持租户隔离的 Agent 发现、鉴权、路由、负载均衡、JSON-RPC/HTTP+JSON 协议转换、任务转发和 SSE 桥接。MVP 使用 YAML 注册和内存 Store，企业版演进方向请参阅 Gateway 文档。

## 🏗️ 项目结构

```
a2a4j/
├── a2a4j-bom/                     # 依赖管理
├── a2a4j-core/                    # 核心 A2A 协议实现
├── a2a4j-gateway-api/             # Gateway 公共 SPI 和契约
├── a2a4j-gateway-core/            # 发现、路由、转发和 Store
├── a2a4j-gateway-spring-boot-starter/ # Gateway Spring Boot 集成
├── a2a4j-spring-boot-starter/     # Spring Boot 自动配置
│   ├── a2a4j-server-spring-boot-starter/   # 服务器端启动器
│   └── a2a4j-client-spring-boot-starter/   # 客户端启动器
├── a2a4j-samples/                 # 示例实现
│   └── server-hello-world/        # Hello World 服务器示例
│   └── client-hello-world/        # Hello World 客户端示例
│   └── gateway-hello-world/       # Agent Gateway MVP 样例
├── specification/                 # A2A 协议规范
├── docs/agent-gateway/             # Gateway 架构、API 和 MVP backlog
├── tools/                        # 开发工具和配置
```

## 🚀 快速开始

仓库中的 Gateway 模块包括：

- `a2a4j-gateway-api`：Gateway 公共契约和扩展点。
- `a2a4j-gateway-core`：发现、路由、负载均衡、转发、任务路由和协议适配器。
- `a2a4j-gateway-spring-boot-starter`：Spring Boot 配置、安全、HTTP 入口和可观测性。
- `a2a4j-samples/gateway-hello-world`：连接两个真实 Agent 的可运行 Gateway 样例。

### 1. 使用 A2A4J 构建智能体

#### 引入 A2A4J SDK

如果是基于 Spring Boot 构建，推荐使用 `a2a4j-server-spring-boot-starter`

```xml
<dependency>
    <groupId>io.github.a2ap</groupId>
    <artifactId>a2a4j-server-spring-boot-starter</artifactId>
    <version>0.0.1</version>
</dependency>
```

其它框架构建，推荐引入 `a2a4j-core`

```xml
<dependency>
    <groupId>io.github.a2ap</groupId>
    <artifactId>a2a4j-core</artifactId>
    <version>0.0.1</version>
</dependency>
```

当前运行时使用 A2A 1.0 方法名：`SendMessage`、`SendStreamingMessage`、`GetTask`、`ListTasks`、`CancelTask` 和
`SubscribeToTask`。Agent Card 路径为 `/.well-known/agent-card.json`；旧版 Agent Card 路径和 0.2.1 方法名均不支持。

#### 实现对外 EndPoint 端点

```java
@RestController
public class MyA2AController {

    @Autowired
    private A2AServer a2aServer;
    @Autowired
    private final Dispatcher a2aDispatch;

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<AgentCard> getAgentCard() {
        AgentCard card = a2aServer.getSelfAgentCard();
        return ResponseEntity.ok(card);
    }

    @PostMapping(value = "/a2a/server", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JSONRPCResponse> handleA2ARequestTask(@RequestBody JSONRPCRequest request) {
        return ResponseEntity.ok(a2aDispatch.dispatch(request));
    }

    @PostMapping(value = "/a2a/server", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<JSONRPCResponse>> handleA2ARequestTaskSubscribe(@RequestBody JSONRPCRequest request) {
        return a2aDispatch.dispatchStream(request).map(event -> ServerSentEvent.<JSONRPCResponse>builder()
                .data(event).event("task-update").build());
    }
}
```

#### 实现 `Agent` 消息任务执行 `AgentExecutor` 接口

```java
@Component
public class MyAgentExecutor implements AgentExecutor {

    @Override
    public Mono<Void> execute(RequestContext context, EventQueue eventQueue) {
        // 你的智能体逻辑
        TaskStatusUpdateEvent completedEvent = TaskStatusUpdateEvent.builder()
                .taskId(taskId)
                .contextId(contextId)
                .status(TaskStatus.builder()
                        .state(TaskState.COMPLETED)
                        .timestamp(String.valueOf(Instant.now().toEpochMilli()))
                        .message(createAgentMessage("Task completed successfully! Hi you."))
                        .build())
                .metadata(Map.of(
                        "executionTime", "3000ms",
                        "artifactsGenerated", 4,
                        "success", true))
                .build();

        eventQueue.enqueueEvent(completedEvent);
        return Mono.empty();
    }
}
```

#### Done

完毕, 主要的步骤就是这些，具体内容可以参考我们写的 [智能体Demo](./a2a4j-samples/server-hello-world) 代码。

### 2. 测试智能体 Demo

#### 运行 Hello World 示例

```bash
git clone https://github.com/a2ap/a2a4j.git

cd a2a4j

mvn clean install

cd a2a4j-samples/server-hello-world

mvn spring-boot:run
```

服务器将在 `http://localhost:8089` 启动。

#### 获取 Agent Card
```bash
curl http://localhost:8089/.well-known/agent-card.json
```

#### 发送消息
```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "user",
        "parts": [
          {
            "text": "你好，A2A！"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "1"
  }'
```

#### 流式消息
```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "user",
        "parts": [
          {
            "text": "你好，流式 A2A！"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "1"
  }'
```

## 📚 核心模块

### Agent Gateway 模块与 MVP 样例

Gateway 样例由一个 Gateway 和两个独立的 A2A 1.0 Agent 组成。在仓库根目录执行：

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/gateway-hello-world -am package -DskipTests
$env:A2A_SAMPLE_API_KEY = 'change-me-locally'
```

在两个终端启动 Agent：

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-a --server.port=8091
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-b --server.port=8092
```

在第三个终端启动 Gateway：

```powershell
java -jar a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar
```

Gateway 监听 `http://localhost:8099`，API Key 模式仅用于本地开发。最小调用示例：

```powershell
$body = '{"message":{"role":"ROLE_USER","parts":[{"text":"hello from gateway"}]}}'
Invoke-RestMethod http://localhost:8099/message:send -Method Post `
  -Headers @{ 'X-A2A-API-Key' = $env:A2A_SAMPLE_API_KEY; 'A2A-Version' = '1.0' } `
  -ContentType 'application/a2a+json' -Body $body
```

可使用 `X-A2A-Target-Agent: echo-b` 或 `X-A2A-Target-Skill: code-generation` 选择路由。完整配置、API
矩阵、安全说明、SSE 错误行为和 MVP 限制请参阅 [Gateway 文档](docs/agent-gateway/README.md) 和
[Gateway 样例 README](a2a4j-samples/gateway-hello-world/README.md)。

### A2A4J 核心模块 (`a2a4j-core`)

核心模块提供基础的 A2A 协议实现：

- **模型**: Agent Cards、Tasks、Messages 和 Artifacts 的数据结构
- **服务器**: 服务器端 A2A 协议实现
- **客户端**: 客户端 A2A 协议实现
- **JSON-RPC**: JSON-RPC 2.0 请求/响应处理
- **异常处理**: 全面的错误管理

[📖 查看核心文档](a2a4j-core/README_CN.md)

### Spring Boot 启动器

#### 服务器启动器 (`a2a4j-server-spring-boot-starter`)
为 A2A 服务器提供 Spring Boot 自动配置，包括：
- 自动端点配置
- Agent Card 发布
- 任务管理
- SSE 流式处理支持

#### 客户端启动器 (`a2a4j-client-spring-boot-starter`)
为 A2A 客户端提供 Spring Boot 自动配置，包括：
- 智能体发现
- HTTP 客户端配置
- 响应式客户端支持

### 示例 (`a2a4j-samples`)

演示 A2A4J 使用方法的完整工作示例：
- **[server-hello-world](./a2a4j-samples/server-hello-world)**: 基于 A2A 服务器实现
- **[client-hello-world](./a2a4j-samples/client-hello-world)**: 基于 A2A 客户端实现
- **[gateway-hello-world](./a2a4j-samples/gateway-hello-world)**：连接两个真实 A2A 1.0 Agent 的 Gateway MVP 样例

## 📊 JSON-RPC 方法

### 核心方法
- `SendMessage` - 发送消息并创建任务
- `SendStreamingMessage` - 发送消息并获取流式更新

### 任务管理
- `GetTask` - 获取任务状态和详情
- `ListTasks` - 获取当前调用方可见的任务
- `CancelTask` - 取消运行中的任务
- `SubscribeToTask` - 重新订阅任务更新

### 推送通知
- `CreateTaskPushNotificationConfig` - 配置推送通知
- `GetTaskPushNotificationConfig` - 获取通知配置
- `DeleteTaskPushNotificationConfig` - 删除通知配置


## 📖 文档

- [A2A 协议规范](specification/specification.md)
- [核心模块文档](a2a4j-core/README_CN.md)
- [API 参考](a2a4j-core/API_REFERENCE.md)
- [Hello World 示例](a2a4j-samples/server-hello-world/README.md)
- [Gateway 架构与 MVP Backlog](docs/agent-gateway/README.md)
- [Gateway 配置](docs/agent-gateway/configuration.md)与 [API 参考](docs/agent-gateway/api-reference.md)
- [Gateway 样例](a2a4j-samples/gateway-hello-world/README.md)

## 🤝 贡献

我们欢迎贡献！请查看我们的[贡献指南](CONTRIBUTING_CN.md)了解详情。

1. Fork 仓库
2. 创建功能分支: `git checkout -b feature/my-feature`
3. 提交更改: `git commit -am 'Add new feature'`
4. 推送到分支: `git push origin feature/my-feature`
5. 提交 Pull Request

## 📄 许可证

本项目根据 Apache License 2.0 许可 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🌟 支持

- **问题反馈**: [GitHub Issues](https://github.com/a2ap/a2a4j/issues)
- **讨论**: [GitHub Discussions](https://github.com/a2ap/a2a4j/discussions)
- **CI/CD**: [GitHub Actions](https://github.com/a2ap/a2a4j/actions)

## 🔗 参考来自

- [A2A 协议规范](https://google-a2a.github.io/A2A/specification/)
- [A2A 协议官网](https://google-a2a.github.io)

---

由 A2AP 社区用 ❤️ 构建
