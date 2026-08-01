# A2A4J Client Hello World Sample

This is a sample A2A 1.0 (Agent2Agent) protocol client implementation demonstrating how to use the A2A4J framework to interact with an A2A server.

## Features

- ✅ A2A protocol client implementation
- ✅ JSON-RPC 2.0 synchronous and streaming communication
- ✅ Example of sending messages to an A2A server
- ✅ Real-time status updates and progress tracking
- ✅ Detailed logging

## Quick Start

> **Before running the client, please make sure the `server-hello-world` module is started and running on http://localhost:8089.**

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher

### Build Project

```bash
# Clone repository (if you haven't already)
git clone https://github.com/a2ap/a2a4j.git
cd a2a4j

# Build entire project
mvn clean install

# Navigate to sample directory
cd a2a4j-samples/client-hello-world
```

### Run Client

```bash
# Run with Maven
mvn spring-boot:run

# Or run compiled JAR
mvn clean package
java -jar target/client-hello-world-*.jar
```

### Example Usage

Starting the client only starts its HTTP application; it does not send a message automatically. Invoke one of the
client endpoints below to resolve the server Card and send a request. The default server URL is
`http://localhost:8089` and can be changed in the configuration file.

The client first resolves `/.well-known/agent-card.json` (and falls back to `/.well-known/agent.json`), then sends
`A2A-Version: 1.0` JSON-RPC requests using `SendMessage` or `SendStreamingMessage`. The client application's own
HTTP endpoints are:

```text
GET /a2a/client/send?message=hello
GET /a2a/client/stream/send?message=hello
```

The second endpoint returns `text/event-stream` and must be read as SSE. The old JSON-RPC method names
`message/send` and `message/stream` are not valid for the current 1.0 client/server pair.

---

For more details, see the source code and comments.
