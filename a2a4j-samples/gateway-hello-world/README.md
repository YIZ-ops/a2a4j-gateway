# Gateway Hello World Sample

This sample runs the A2A4J Gateway against two real A2A 1.0 `server-hello-world` Agents. The Gateway and
Agents are separate processes: the Gateway demonstrates discovery, routing, authentication and forwarding;
the Agents execute the standard `DemoAgentExecutor` lifecycle.

## Run

From the repository root, build the sample:

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/gateway-hello-world -am package -DskipTests
```

The executable Spring Boot artifact is the `*-exec.jar` classifier. Keeping the plain jar alongside it
allows a running sample process on Windows to be upgraded without replacing its locked file.

Set a local API key without committing it:

```powershell
$env:A2A_SAMPLE_API_KEY = 'change-me-locally'
```

Start two Agents in separate terminals:

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-a --server.port=8091

java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar `
  --spring.profiles.active=echo-b --server.port=8092
```

Start the Gateway in a third terminal:

```powershell
java -jar a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar
```

The Gateway listens on `http://localhost:8099`; the two real Agent Cards are refreshed from ports 8091 and
8092. Profile `echo-a` implements `hello-world` and `code-generation`; profile `echo-b` implements
`task-summary`. Their Card metadata and Skills are defined in
`server-hello-world/src/main/resources/application-echo-a.yml` and `application-echo-b.yml`.

With the default split, a request carrying `X-A2A-Target-Skill: code-generation` is routed to `echo-a`
without specifying `X-A2A-Target-Agent`; the Gateway resolves the target from the Agent Cards.

## Call the Gateway

```powershell
$body = '{"message":{"role":"ROLE_USER","parts":[{"text":"hello from gateway"}]}}'
Invoke-RestMethod http://localhost:8099/message:send -Method Post `
  -Headers @{ 'X-A2A-API-Key' = $env:A2A_SAMPLE_API_KEY; 'A2A-Version' = '1.0' } `
  -ContentType 'application/a2a+json' -Body $body
```

Use `X-A2A-Target-Agent: echo-b` to route explicitly to the second Agent. Health and Prometheus endpoints are
available under `/actuator/health` and `/actuator/prometheus`.

To route by Skill, add `X-A2A-Target-Skill: code-generation`. The Gateway validates that the Skill is present
in the selected Agent Card before forwarding the request.

This is a demonstration profile, not a production deployment: API-key authentication is intentionally local,
the Gateway task/idempotency stores are in memory, and restart loses task affinity.
