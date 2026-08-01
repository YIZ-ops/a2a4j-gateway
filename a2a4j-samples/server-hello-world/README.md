# A2A4J Server Hello World Sample

This is a complete A2A 1.0 (Agent2Agent) protocol server implementation sample that demonstrates how to build a fully functional intelligent agent server using the A2A4J framework.

The JSON-RPC wire method names in the current implementation are `SendMessage`, `SendStreamingMessage`, `GetTask`,
`CancelTask`, and `SubscribeToTask`. The former 0.2.1-style names such as `message/send` and `message/stream` are
not accepted by the 1.0 dispatcher.

## Sample Features

- ✅ Complete A2A protocol implementation
- ✅ JSON-RPC 2.0 synchronous and streaming communication
- ✅ Automatic Agent Card discovery
- ✅ Multiple artifact type generation (text, code, summaries)
- ✅ Real-time status updates and progress tracking
- ✅ Server-Sent Events streaming responses
- ✅ CORS cross-origin support
- ✅ Detailed logging

## Quick Start

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- curl or other HTTP client (for testing)

### Build Project

```bash
# Clone repository (if you haven't already)
git clone https://github.com/a2ap/a2a4j.git
cd a2a4j

# Build entire project
mvn clean install

# Navigate to sample directory
cd a2a4j-samples/server-hello-world
```

### Run Server

```bash
# Run with Maven
mvn spring-boot:run

# Or run compiled JAR
mvn clean package
java -jar target/server-hello-world-*-exec.jar
```

The server will start at **http://localhost:8089**.

The server starter defaults to advertising both `JSONRPC` and `HTTP+JSON` in the Agent Card. This sample controller
implements the JSON-RPC endpoint `/a2a/server`; for a truthful Card, configure
`a2a.server.protocol-bindings: [JSONRPC]` (the `echo-a` and `echo-b` profiles already do this). The HTTP+JSON
Gateway endpoint `/message:send` is a northbound Gateway API and is not served by this sample.

### Run the two Gateway demo Agents

The Gateway sample uses two separate instances of this server, rather than an in-process Gateway fixture. Build both
samples, then start these commands in separate terminals:

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-a --server.port=8091
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-b --server.port=8092
```

`echo-a` advertises the `hello-world` and `code-generation` skills; `echo-b` advertises `task-summary`. Their Cards are
configuration-driven in `src/main/resources/application-echo-a.yml` and `application-echo-b.yml`, and truthfully
advertise the JSON-RPC binding implemented by this sample.

### Verify Server Status

```bash
# Check if server is running
curl -X GET http://localhost:8089/actuator/health

# Expected response
{"status":"UP"}
```

## A2A Protocol Endpoint Testing

### 1. Agent Card Discovery

Get agent capabilities and metadata information:

```bash
curl -X GET http://localhost:8089/.well-known/agent-card.json
```

**Expected Response Example (with `a2a.server.protocol-bindings: [JSONRPC]`):**
```json
{
  "id": "server-hello-world",
  "name": "A2A Java Server",
  "description": "A sample A2A agent implemented in Java",
  "url": "http://localhost:8089/a2a/server",
  "supportedInterfaces": [
    {
      "url": "http://localhost:8089/a2a/server",
      "protocolBinding": "JSONRPC",
      "protocolVersion": "1.0"
    }
  ],
  "provider": {
    "organization": "A2A",
    "url": "https://github.com/google-a2a/a2a-samples"
  },
  "version": "1.0.0",
  "documentationUrl": "https://google-a2a.github.io/A2A/",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "stateTransitionHistory": true
  },
  "defaultInputModes": [
    "text"
  ],
  "defaultOutputModes": [
    "text"
  ],
  "skills": [
    {
      "id": "hello-world",
      "name": "hello-world",
      "description": "A simple hello world skill",
      "tags": [
        "greeting",
        "basic"
      ],
      "examples": [
        "Say hello to me",
        "Greet me"
      ],
      "inputModes": [
        "text"
      ],
      "outputModes": [
        "text"
      ]
    }
  ]
}
```

### 2. Synchronous Message Sending

Send a message and wait for complete response:

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
            "text": "Please help me analyze basic machine learning concepts"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "test-1"
  }'
```

### 3. Streaming Message Sending

Send a message and receive real-time updates:

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
            "text": "Generate a simple Java class example"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "stream-1"
  }'
```

## Advanced Testing Scenarios

### Test Streaming Response Handling

Use more sophisticated tools to observe streaming responses:

```bash
# Use httpie to observe streaming responses
echo '{
  "jsonrpc": "2.0",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "user",
      "parts": [{"text": "Create a data structure example"}]
    }
  },
  "id": "1"
}' | http POST localhost:8089/a2a/server \
  Content-Type:application/json \
  Accept:text/event-stream
```

### Concurrent Request Testing

Test the server's ability to handle multiple concurrent requests:

```bash
# Start multiple concurrent requests
for i in {1..5}; do
  curl -X POST http://localhost:8089/a2a/server \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -H "A2A-Version: 1.0" \
    -d "{
      \"jsonrpc\": \"2.0\",
      \"method\": \"SendStreamingMessage\",
      \"params\": {
        \"message\": {
          \"role\": \"user\",
          \"parts\": [{\"text\": \"Concurrent request $i\"}]
        }
      },
      \"id\": \"concurrent-$i\"
    }" &
done

# Wait for all requests to complete
wait
```

### Error Handling Testing

Test various error scenarios:

```bash
# Test invalid JSON-RPC method
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "UnknownMethod",
    "params": {},
    "id": "error-1"
  }'

# Test invalid parameters
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendMessage",
    "params": {
      "invalidParam": "value"
    },
    "id": "error-2"
  }'
```

## Code Structure Explanation

### Core Components

- **`A2AServerApplication`**: Spring Boot main application class, configures CORS and application startup
- **`A2AServerController`**: REST controller implementing A2A protocol endpoints
- **`DemoAgentExecutor`**: Sample agent executor demonstrating various event types and artifact generation

### Execution Flow

1. **Task Creation**: Receives `SendMessage` or `SendStreamingMessage` request
2. **Status Updates**: Sends "Starting", "Analyzing", "Generating" statuses
3. **Content Generation**: Sends text response in chunks
4. **Artifact Creation**: Generates code examples and task summaries
5. **Task Completion**: Sends final completion status and closes event queue

### Configuration Options

Configure in `application.yml`:

```yaml
server:
  port: 8089  # Modify server port

a2a:
  server:
    id: "server-hello-world"
    name: "A2A Java Server"
    description: "A sample A2A agent implemented in Java"
    version: "1.0.0"
    url: "http://localhost:${server.port}/a2a/server"
    provider:
      name: "A2AP Team"
      url: "https://github.com/a2ap"
    documentationUrl: "https://github.com/a2ap/a2a4j"
    capabilities:
      streaming: true
      pushNotifications: false
      stateTransitionHistory: true
    defaultInputModes:
      - "text"
    defaultOutputModes:
      - "text"
    skills:
      - name: "hello-world"
        description: "A simple hello world skill"
        tags:
          - "greeting"
          - "basic"
        examples:
          - "Say hello to me"
          - "Greet me"
        inputModes:
          - "text"
        outputModes:
          - "text"
```

## Troubleshooting

### Common Issues

1. **Port in use**: Modify `server.port` in `application.yml`
2. **Java version incompatible**: Ensure using Java 17 or higher
3. **Dependency issues**: Run `mvn clean install` to rebuild

### Debug Mode

Enable detailed logging:

```yaml
logging:
  level:
    io.github.a2ap: DEBUG
    org.springframework.web: DEBUG
```

### Performance Monitoring

Add Spring Boot Actuator endpoints:

```bash
# View application info
curl http://localhost:8089/actuator/info

# View health status
curl http://localhost:8089/actuator/health

# View metrics
curl http://localhost:8089/actuator/metrics
```

## Extension Development

### Custom Agent Executor

Create your own `AgentExecutor` implementation:

```java
@Component
public class MyCustomExecutor implements AgentExecutor {
    
    @Override
    public Mono<Void> execute(RequestContext context, EventQueue eventQueue) {
        // Implement custom logic
        return Mono.empty();
    }
    
    @Override
    public Mono<Void> cancel(String taskId) {
        // Implement cancellation logic
        return Mono.empty();
    }
}
```

### Add Custom Endpoints

Extend controller to support more functionality:

```java
@RestController
public class CustomController {
    
    @GetMapping("/custom/endpoint")
    public ResponseEntity<String> customEndpoint() {
        return ResponseEntity.ok("Custom response");
    }
}
```

## Production Deployment

### Docker Deployment

```dockerfile
FROM openjdk:17-jre-slim
COPY target/server-hello-world-*-exec.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

## References

- [A2A4J Core Documentation](../../a2a4j-core/README.md)
- [Spring Boot Starter Documentation](../../a2a4j-spring-boot-starter/a2a4j-server-spring-boot-starter/README.md)
- [A2A Protocol Specification](https://google-a2a.github.io/A2A/specification/)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)

## License

This project is licensed under the Apache License 2.0 - see [LICENSE](../../LICENSE) file for details. 
