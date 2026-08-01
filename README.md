# A2A4J - Agent2Agent Protocol for Java

[![Maven Central](https://img.shields.io/maven-central/v/io.github.a2ap/a2a4j)](https://search.maven.org/artifact/io.github.a2ap/a2a4j)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-green.svg)](https://openjdk.org/projects/jdk/17/)

📖 **[中文文档](README_CN.md)**

[Agent2Agent (A2A)](https://github.com/google-a2a/A2A) is an open standard for communication and interoperability between independent AI agent systems. This repository currently targets the A2A 1.0 wire contract.

[A2A4J](https://github.com/a2ap/a2a4j) is a Java implementation of A2A with a reusable core, Spring Boot server/client starters, and an extensible Agent Gateway. Built on Reactor, it enables agents to publish capabilities, exchange tasks, and route requests without exposing their internal state.

## 🚀 Features

- ✅ **Complete A2A Protocol Support** - Full implementation of the Agent2Agent specification
- ✅ **JSON-RPC 2.0 Communication** - Standards-based request/response messaging
- ✅ **Server-Sent Events Streaming** - Real-time task updates and streaming responses
- ✅ **Task Lifecycle Management** - Comprehensive task state management and monitoring
- ✅ **Spring Boot Integration** - Easy integration with Spring Boot applications
- ✅ **Reactive Programming Support** - Built on Reactor for scalable, non-blocking operations
- ✅ **Multiple Content Types** - Support for text, files, and structured data exchange
- ⚪️ **Agent Card Discovery** - Dynamic capability discovery mechanism
- ⚪️ **Push Notification Configuration** - A2A push-notification configuration APIs; delivery integration is application-owned
- ⚪️ **Enterprise Security** - Authentication and authorization support

## 📋 Prerequisites

- **Java 17+** - Required for running the application
- **Maven 3.6+** - Build tool

The repository includes an Agent Gateway MVP in addition to the core SDK. It provides tenant-scoped Agent discovery,
authentication, routing, load balancing, JSON-RPC/HTTP+JSON conversion, task forwarding, and SSE bridging. The MVP
uses YAML registration and in-memory stores; see the Gateway documentation for the enterprise extension path.

## 🏗️ Project Structure

```
a2a4j/
├── a2a4j-bom/                     # A2A4J dependency management
├── a2a4j-core/                    # Core A2A protocol implementation
├── a2a4j-gateway-api/             # Gateway public SPI and contracts
├── a2a4j-gateway-core/            # Discovery, routing, forwarding, and stores
├── a2a4j-gateway-spring-boot-starter/ # Gateway Spring Boot integration
├── a2a4j-spring-boot-starter/     # Spring Boot auto-configuration
│   ├── a2a4j-server-spring-boot-starter/   # Server-side starter
│   └── a2a4j-client-spring-boot-starter/   # Client-side starter
├── a2a4j-samples/                 # Example implementations
│   └── server-hello-world/        # Hello World server example
│   └── client-hello-world/        # Hello World client example
│   └── gateway-hello-world/       # Agent Gateway MVP example
├── specification/                 # A2A protocol specification
├── docs/agent-gateway/             # Gateway architecture, API, and MVP backlog
├── tools/                        # Development tools and configuration
```

## 🚀 Quick Start

The repository also contains these Gateway modules:

- `a2a4j-gateway-api`: public Gateway contracts and extension points.
- `a2a4j-gateway-core`: discovery, routing, load balancing, forwarding, task routes, and protocol adapters.
- `a2a4j-gateway-spring-boot-starter`: Spring Boot configuration, security, HTTP endpoints, and observability.
- `a2a4j-samples/gateway-hello-world`: runnable Gateway connected to two real sample Agents.

### 1. Build an A2A Agent

#### Integrate the A2A4J SDK

If you’re building with Spring Boot, use `a2a4j-server-spring-boot-starter`.

```xml
<dependency>
    <groupId>io.github.a2ap</groupId>
    <artifactId>a2a4j-server-spring-boot-starter</artifactId>
    <version>0.0.1</version>
</dependency>
```

For other frameworks, it is recommended to use `a2a4j-core`.

```xml
<dependency>
    <groupId>io.github.a2ap</groupId>
    <artifactId>a2a4j-core</artifactId>
    <version>0.0.1</version>
</dependency>
```

The current runtime uses A2A 1.0 method names (`SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`,
`CancelTask`, and `SubscribeToTask`). The primary Agent Card path is `/.well-known/agent-card.json`; the sample
also keeps `/.well-known/agent.json` as a compatibility alias. Legacy 0.2.1 method names such as `message/send`
and `message/stream` are rejected by the 1.0 dispatcher.

#### Expose an External Endpoint

```java
@RestController
public class MyA2AController {

    @Autowired
    private A2AServer a2aServer;
    @Autowired
    private final Dispatcher a2aDispatch;

    @GetMapping({ "/.well-known/agent-card.json", "/.well-known/agent.json" })
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

#### Implementing the `AgentExecutor` Interface for Agent Task Execution

```java
@Component
public class MyAgentExecutor implements AgentExecutor {

    @Override
    public Mono<Void> execute(RequestContext context, EventQueue eventQueue) {
        // your agent logic code
        TaskStatusUpdateEvent completedEvent = TaskStatusUpdateEvent.builder()
                .taskId(taskId)
                .contextId(contextId)
                .status(TaskStatus.builder()
                        .state(TaskState.COMPLETED)
                        .timestamp(String.valueOf(Instant.now().toEpochMilli()))
                        .message(createAgentMessage("Task completed successfully! Hi you."))
                        .build())
                .isFinal(true)
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

That’s it — these are the main steps. For detailed implementation, please refer to our [Agent Demo example](./a2a4j-samples/server-hello-world).

### 2. Test Run Agent Example

#### Run the Server Hello World

```bash
git clone https://github.com/a2ap/a2a4j.git

cd a2a4j

mvn clean install

cd a2a4j-samples/server-hello-world

mvn spring-boot:run
```

The server will start at `http://localhost:8089`.

#### Get Agent Card
```bash
curl http://localhost:8089/.well-known/agent-card.json
```

#### Send a Message
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
            "kind": "text",
            "text": "Hello, A2A!"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "1"
  }'
```

#### Stream Messages
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
            "kind": "text",
            "text": "Hello, streaming A2A!"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "1"
  }'
```

## 📚 Core Modules

### Agent Gateway Module and MVP Sample

The Gateway sample starts one Gateway and two independent A2A 1.0 Agents. From the repository root:

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/gateway-hello-world -am package -DskipTests
$env:A2A_SAMPLE_API_KEY = 'change-me-locally'
```

Start the two Agents in separate terminals:

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-a --server.port=8091
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-b --server.port=8092
```

Start the Gateway in a third terminal:

```powershell
java -jar a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar
```

The Gateway listens on `http://localhost:8099`. Its API-key mode is for local development only. A minimal request is:

```powershell
$body = '{"message":{"role":"ROLE_USER","parts":[{"text":"hello from gateway"}]}}'
Invoke-RestMethod http://localhost:8099/message:send -Method Post `
  -Headers @{ 'X-A2A-API-Key' = $env:A2A_SAMPLE_API_KEY; 'A2A-Version' = '1.0' } `
  -ContentType 'application/a2a+json' -Body $body
```

Use `X-A2A-Target-Agent: echo-b` or `X-A2A-Target-Skill: code-generation` to select a route. For the complete
configuration, API matrix, security notes, and MVP limitations, see the [Gateway documentation](docs/agent-gateway/README.md)
and the [Gateway sample README](a2a4j-samples/gateway-hello-world/README.md).

### A2A4J Core (`a2a4j-core`)

The core module provides the fundamental A2A protocol implementation:

- **Models**: Data structures for Agent Cards, Tasks, Messages, and Artifacts
- **Server**: Server-side A2A protocol implementation
- **Client**: Client-side A2A protocol implementation
- **JSON-RPC**: JSON-RPC 2.0 request/response handling
- **Exception Handling**: Comprehensive error management

[📖 View Core Documentation](a2a4j-core/README.md)

### Spring Boot Starters

#### Server Starter (`a2a4j-server-spring-boot-starter`)
Auto-configuration for A2A servers with Spring Boot, providing:
- Automatic endpoint configuration
- Agent Card publishing
- Task management
- SSE streaming support

#### Client Starter (`a2a4j-client-spring-boot-starter`)
Auto-configuration for A2A clients with Spring Boot, providing:
- Agent discovery
- HTTP client configuration
- Reactive client support

### Examples (`a2a4j-samples`)

Complete working examples demonstrating A2A4J usage:
- **[Hello World Server](./a2a4j-samples/server-hello-world)**: Basic A2A4J server implementation
- **[Hello World Client](./a2a4j-samples/client-hello-world)**: Basic A2A4J client implementation
- **[Gateway Hello World](./a2a4j-samples/gateway-hello-world)**: Gateway MVP connected to two real A2A 1.0 Agents

## 📊 JSON-RPC Methods

### Core Methods
- `SendMessage` - Send a message and create a task
- `SendStreamingMessage` - Send a message with streaming updates

### Task Management
- `GetTask` - Get task status and details
- `ListTasks` - List tasks visible to the caller
- `CancelTask` - Cancel a running task
- `SubscribeToTask` - Resubscribe to task updates

### Push Notifications
- `CreateTaskPushNotificationConfig` - Configure push notifications
- `GetTaskPushNotificationConfig` - Get notification configuration
- `DeleteTaskPushNotificationConfig` - Delete notification configuration


## 📖 Documentation

- [A2A 1.0 Protocol Specification](specification/specification.md)
- [Core Module Documentation](a2a4j-core/README.md)
- [API Reference](a2a4j-core/API_REFERENCE.md)
- [Hello World Example](a2a4j-samples/server-hello-world/README.md)
- [Gateway Architecture and MVP Backlog](docs/agent-gateway/README.md)
- [Gateway Configuration and API Reference](docs/agent-gateway/configuration.md)
- [Gateway Sample](a2a4j-samples/gateway-hello-world/README.md)

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -am 'Add new feature'`
4. Push to the branch: `git push origin feature/my-feature`
5. Submit a Pull Request

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🌟 Support

- **Issues**: [GitHub Issues](https://github.com/a2ap/a2a4j/issues)
- **Discussions**: [GitHub Discussions](https://github.com/a2ap/a2a4j/discussions)
- **CI/CD**: [GitHub Actions](https://github.com/a2ap/a2a4j/actions)

## 🔗 Refer Projects

- [A2A Protocol Specification](https://google-a2a.github.io/A2A/specification/)
- [A2A Protocol Website](https://google-a2a.github.io)

---

Built with ❤️ by the A2AP Community
