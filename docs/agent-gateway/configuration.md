# Gateway 配置指南

本文只描述当前 Agent Gateway MVP 的启动、静态 Agent 注册、路由、安全与运行限制。完整的北向 HTTP+JSON、JSON-RPC、SSE、目录和 Actuator 端点清单已独立维护在 [Gateway API 参考](./api-reference.md)，避免配置说明和接口契约混杂。

G0-G9 的实施过程集中记录在 [mvp-backlog.md](./mvp-backlog.md)，架构取舍和企业版演进见 [architecture.md](./architecture.md)。本文只记录当前代码已经提供的配置；动态控制平面、分布式状态、gRPC 与持久化 Push Notification 代理不属于 MVP。

## 1. 运行模型与快速开始

### 1.1 三个独立进程

示例由一个 Gateway 和两个真实 A2A Server 进程组成。Gateway 负责鉴权、发现、路由和转发；两个 Agent 独立发布 Card 并执行 `DemoAgentExecutor`。这不是 Gateway 进程内的 HTTP fixture。

| 进程 | 端口 | 启动 profile | 作用 |
| --- | ---: | --- | --- |
| Gateway | 8099 | 无 | 鉴权、发现、路由、转发。 |
| Echo Agent A | 8091 | `echo-a` | Card 声明 `hello-world`、`code-generation`。 |
| Echo Agent B | 8092 | `echo-b` | Card 声明 `task-summary`。 |

构建两个 sample：

```powershell
.\mvnw.cmd -pl a2a4j-samples/server-hello-world,a2a4j-samples/gateway-hello-world -am package -DskipTests
```

启动 Gateway：

```powershell
$env:A2A_SAMPLE_API_KEY = 'change-me-locally'
java -jar a2a4j-samples/gateway-hello-world/target/gateway-hello-world-0.0.1-exec.jar
```

在另外两个终端启动 Agent：

```powershell
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-a --server.port=8091
java -jar a2a4j-samples/server-hello-world/target/server-hello-world-0.0.1-exec.jar --spring.profiles.active=echo-b --server.port=8092
```

运行中的普通 jar 在 Windows 上可能阻塞 Maven 重建；因此 Gateway 与 Server sample 都应运行 `*-exec.jar`。Gateway 没有用户名/密码登录；API Key 模式使用 `X-A2A-API-Key`，未认证时返回普通 `401`，不会触发浏览器 Basic Auth 登录框。

### 1.2 示例配置位置

- [Gateway 静态注册与 API Key](../../a2a4j-samples/gateway-hello-world/src/main/resources/application.yml)
- [Echo A Card/Skill profile](../../a2a4j-samples/server-hello-world/src/main/resources/application-echo-a.yml)
- [Echo B Card/Skill profile](../../a2a4j-samples/server-hello-world/src/main/resources/application-echo-b.yml)
- [配置绑定与启动校验](../../a2a4j-gateway-spring-boot-starter/src/main/java/io/github/a2ap/gateway/spring/GatewayProperties.java)

MVP 的 Agent 注册入口是 Gateway YAML，不是网页，也不是运行时注册 API。修改 `a2a.gateway.agents` 后需要重启 Gateway。

## 2. 静态 Agent 注册

### 2.1 完整示例

```yaml
a2a:
  gateway:
    enabled: true
    allow-http: false
    allow-private-network: false
    allowed-cidrs: []
    max-card-bytes: 1048576
    card-timeout: 5s
    refresh-interval: 5m
    unhealthy-after-failures: 3
    connect-timeout: 2s
    response-timeout: 60s
    max-response-bytes: 4194304
    max-request-bytes: 1048576
    max-event-bytes: 1048576
    stream-idle-timeout: 30s
    max-concurrent-streams: 200
    task-route-ttl: 24h
    task-route-max-entries: 10000
    idempotency-ttl: 24h
    idempotency-max-entries: 10000
    default-agent-by-tenant:
      tenant-a: research-agent
    agents:
      - tenant-id: tenant-a
        agent-id: research-agent
        display-name: Research Agent
        enabled: true
        routing-labels:
          region: cn
          tier: standard
        protocol-versions: ["1.0"]
        protocol-bindings: ["JSONRPC", "HTTP+JSON"]
        instances:
          - instance-id: research-agent-1
            card-url: https://research-1.example.com/.well-known/agent-card.json
            weight: 100
            credential-ref: secret://agents/research-agent-1
```

### 2.2 Agent 与实例字段

| 属性 | 级别 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `tenant-id` | Agent | 无 | 租户隔离键，必填。 |
| `agent-id` | Agent | 无 | 逻辑 Agent ID；同一租户内必须唯一。 |
| `display-name` | Agent | 无 | 目录展示名称；Card 缺省描述会使用它。 |
| `enabled` | Agent | `true` | 是否接受新请求；禁用 Agent 不参与目录和路由。 |
| `routing-labels` | Agent | `{}` | 为后续策略保留的标签，不替代租户隔离。 |
| `protocol-versions` | Agent | `["1.0"]` | 允许的 Card 协议版本；MVP 实际只接受 1.0。 |
| `protocol-bindings` | Agent | `["JSONRPC", "HTTP+JSON"]` | 允许 Gateway 选择的上游 Binding。 |
| `instances` | Agent | 无 | 至少一个；逻辑 Agent 与实例 ID 的组合必须唯一。 |
| `instance-id` | 实例 | 无 | 粘滞、健康、负载均衡使用的稳定 ID。 |
| `card-url` | 实例 | 无 | Agent Card 地址；Gateway 不会根据 host 猜测。 |
| `weight` | 实例 | `1` | 加权最少在途选择的权重，必须大于 0。 |
| `credential-ref` | 实例 | 空 | 出站凭据引用；不设置则不额外携带凭据。 |

Agent 必须在配置的 `card-url` 返回可校验的 A2A 1.0 Card。示例 Server 同时提供 `/.well-known/agent-card.json` 和兼容别名 `/.well-known/agent.json`。

### 2.3 从 YAML 到可发现快照

1. Spring Boot 将 `a2a.gateway.agents` 绑定为 `GatewayProperties`。
2. `toRegistrations()` 将 YAML 转成不可变的逻辑 Agent/实例注册记录。
3. `AgentCardRefreshScheduler` 首次启动及每个 `refresh-interval` 拉取每个 `card-url`。
4. Gateway 校验 Card 的 A2A 1.0 结构、协议版本、Binding、Skill 与 URL 策略。
5. 规范化快照原子写入默认 `InMemoryAgentRegistry`。
6. 目录 API、路由器和负载均衡器读取同一份快照。

任一逻辑 Agent 的 Card 初次拉取失败时，该 Agent 可能尚未出现在目录；已有快照在刷新失败时会保留，但实例按连续失败次数进入 `DEGRADED` 或 `UNHEALTHY`。只启动 Gateway 而未启动 8091/8092 的示例 Agent 时，目录可能为空或实例不可用，不能完成消息转发。

### 2.4 Card、Skill 与路由

Skill 的唯一声明来源是上游 Agent Card 的 `skills[]`，不是 Gateway 动态注册 API。Card 刷新后，Skill 会进入当前租户目录、路由与授权判定。

`echo-a`/`echo-b` 的 Card 与 Skills 由 Server Starter 的 `a2a.server` profile 配置提供。`echo-a` 的 Card 只声明它实际实现的 `JSONRPC` binding，因此 Gateway 到这两个示例 Agent 的调用会走 JSON-RPC；如果某个目标 Agent Card 仅声明 `HTTP+JSON`，Gateway 才会选择 HTTP+JSON 上游调用。

路由优先级、请求 Header、Skill-only 路由与冲突行为见 [Gateway API 参考](./api-reference.md)。

## 3. Gateway 运行属性

配置前缀为 `a2a.gateway`。下表是 `GatewayProperties` 支持的通用运行属性。

| 属性 | 默认值 | 作用和约束 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 Gateway Starter。 |
| `allow-http` | `false` | 是否允许 Card/上游使用 HTTP；仅本地开发建议开启。 |
| `allow-private-network` | `false` | 是否允许私网、回环与 link-local 目标；生产不建议全局开启。 |
| `allowed-cidrs` | `[]` | 私网目标的显式 CIDR 白名单。 |
| `max-card-bytes` | `1048576` | 单个 Card 最大字节数，至少 1024。 |
| `card-timeout` | `5s` | Card 拉取超时，必须为正。 |
| `connect-timeout` | `2s` | 上游连接超时，必须为正。 |
| `response-timeout` | `60s` | 非流式上游响应超时，必须为正。 |
| `max-response-bytes` | `4194304` | 非流式响应最大字节数，至少 1024。 |
| `max-request-bytes` | `1048576` | HTTP+JSON/JSON-RPC 入站请求体上限，至少 1024。 |
| `max-event-bytes` | `1048576` | 单个 SSE 事件上限，至少 256 且不大于 `max-response-bytes`。 |
| `stream-idle-timeout` | `30s` | SSE 空闲超时，必须为正。 |
| `max-concurrent-streams` | `200` | 单实例流式并发上限，必须大于 0。 |
| `refresh-interval` | `5m` | Card 刷新周期，必须为正。 |
| `unhealthy-after-failures` | `3` | 连续探测失败后标为 `UNHEALTHY`。 |
| `task-route-ttl` | `24h` | Gateway Task 到上游实例/Task 的内存保留时间。 |
| `task-route-max-entries` | `10000` | Task Route 内存容量上限。 |
| `idempotency-ttl` | `24h` | 幂等记录内存保留时间。 |
| `idempotency-max-entries` | `10000` | 幂等记录容量上限。 |
| `default-agent-by-tenant` | `{}` | 租户到默认 Agent 的映射。 |
| `agents` | `[]` | 静态逻辑 Agent 注册列表。 |

默认路由的 value 必须引用同租户已配置的 `agent-id`。启动校验会拒绝重复租户/Agent、重复实例、空协议策略、缺失实例以及非法默认路由。Card URL 不允许 `userinfo` 或 `fragment`。

## 4. 入站安全配置

配置前缀为 `a2a.gateway.security`。Starter 默认不启用入站认证；sample 显式启用 API Key。

### 4.1 JWT/OIDC（生产推荐）

```yaml
a2a:
  gateway:
    security:
      enabled: true
      mode: jwt
      jwt:
        issuer-uri: https://id.example.com
        # issuer-uri 与 jwk-set-uri 至少配置一个
        audiences: [a2a-gateway]
        tenant-claim: tenant_id
        subject-claim: sub
        authority-claims: [scope, scp, roles, authorities]
        clock-skew: 30s
```

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `security.enabled` | `false` | 是否启用 Gateway 入站认证。 |
| `security.mode` | `jwt` | `jwt` 或 `api-key`。 |
| `security.jwt.issuer-uri` | 无 | OIDC issuer；与 `jwk-set-uri` 至少配置一个。 |
| `security.jwt.jwk-set-uri` | 无 | 不使用 issuer discovery 时的 JWK Set URL。 |
| `security.jwt.audiences` | `[]` | 至少一个；JWT `aud` 必须匹配。 |
| `security.jwt.tenant-claim` | `tenant_id` | 可信租户 Claim。 |
| `security.jwt.subject-claim` | `sub` | 可信主体 Claim。 |
| `security.jwt.authority-claims` | `scope,scp,roles,authorities` | 提取权限的 Claim 列表。 |
| `security.jwt.clock-skew` | `30s` | 时钟偏差容忍度，不能为负。 |

### 4.2 API Key（本地开发）

```yaml
a2a:
  gateway:
    security:
      enabled: true
      mode: api-key
      api-key:
        enabled: true
        header-name: X-A2A-API-Key
        entries:
          - key-id: local-demo
            secret-env: A2A_SAMPLE_API_KEY
            tenant-id: demo
            subject: local-user
            authorities: ["*"]
```

`secret-env` 只保存环境变量名，不保存 secret。启动时读取不到 secret 会快速失败。每个 `key-id` 必须唯一，`tenant-id` 与 `subject` 必填。入站 JWT/API Key 仅用于 Gateway 鉴权和租户隔离，默认不会向下游透传；下游凭据应由实例的 `credential-ref` 独立解析。

## 5. 健康、指标与 MVP 限制

sample 默认暴露 `health`、`info`、`prometheus`，具体路径见 [Gateway API 参考](./api-reference.md)。存在 Micrometer `MeterRegistry` 时，Starter 会注册 `gateway.requests.total`、`gateway.request.duration`、`gateway.streams.started`、`gateway.stream.duration`、`gateway.task.routes`、`gateway.store.entries`、`gateway.store.capacity`、`gateway.store.evictions`、`gateway.store.expired` 等指标。

MVP 的 Agent 快照、Task Route 和幂等 Store 均为单实例内存实现，Gateway 重启后旧任务路由不可恢复。企业版需以 Redis/JDBC/控制平面替换这些 SPI，再增加动态注册、分布式限流、跨副本 SSE 恢复、gRPC Binding 与完整 Push Notification 代理。
