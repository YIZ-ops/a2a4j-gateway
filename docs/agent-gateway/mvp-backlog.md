# Agent Gateway MVP 状态、验收与后续 Backlog

## 1. 文档职责

本文是 Gateway MVP 实施状态、验收证据、发布门槛和后续工作的唯一入口。架构、接口、配置和运行说明分别见：

- [架构与详细设计](./architecture.md)
- [API 参考](./api-reference.md)
- [配置指南](./configuration.md)
- [请求转发链路](./request-forwarding-flow.md)
- [外部 Agent 接入](./external-agent-integration.md)
- [运行手册](./runbook.md)

原独立验收审计和性能快照的必要内容已经并入本文，不再分别维护，避免阶段状态和测试数量相互漂移。

## 2. 当前结论

代码基线：A2A4J `0.0.1`、Java 17 源码级别、Spring Boot 3.5.16、A2A `1.0`。

截至 2026-08-02：

- G0～G9 的 MVP 功能已经落地；
- JSON-RPC、HTTP+JSON、SSE、11 个 JSON-RPC 方法和 27 个 HTTP 路由变体已经实现；
- Core、Client、Server Starter、Samples 与 Gateway 的运行时 Wire 均为 A2A 1.0；
- 全仓 `mvnw.cmd test` 已通过，Proto SHA-256 lock 契约不再阻塞构建；
- 本轮没有运行性能测试、OSV 扫描或多进程 smoke，不能把历史结果当作当前发布证明；
- 当前默认状态是单进程内存实现，不宣称多副本恢复或生产高可用。

MVP 功能可用于集成验证。正式发布前仍应根据目标版本重新运行 package、依赖安全扫描、多进程 smoke，并在需要容量
承诺时才运行目标环境性能测试。

## 3. 增量状态

| 增量 | 状态 | 当前交付 |
| --- | --- | --- |
| G0 协议与工程基线 | 完成 | A2A 1.0 Proto/lock、契约校验、Gateway API/Core/Starter 模块、Client/Server/Sample Wire 迁移 |
| G1 模型与 SPI | 完成 | 不可变 Gateway 模型及 Registry、Routing、Transport、Store、Security、Observability SPI |
| G2 注册、发现与健康 | 完成 | YAML 注册、Card 拉取/校验/hash/周期刷新、Card 失败健康状态、SSRF、目录与健康指标 |
| G3 安全 | 完成 | JWT/API Key、租户与主体映射、Agent/Skill/Task 权限、`env://` 出站凭据和脱敏 |
| G4 路由与实例选择 | 完成 | Task/Context 亲和、显式 Agent、精确 Skill、默认 Agent、内部标签、加权最少在途、bulkhead、熔断 |
| G5 非流式转发 | 完成 | JSON-RPC/HTTP+JSON 转发、协议转换、Task/Context ID 隔离、幂等、有界 Store、错误映射 |
| G6 SSE | 完成 | 流式发送、任务订阅、事件改写、取消传播、事件/空闲/租户并发限制和 200 流 E2E |
| G7 完整数据面 | 完成 | 11 个 JSON-RPC 方法、27 个 HTTP 路由变体、Push、Extended Card、ListTasks 本地快照与 Binding 等价测试 |
| G8 可观测与运维 | 完成 | Micrometer、Store 指标、结构化审计、request ID、W3C Trace Context、Actuator Health 和 Runbook |
| G9 Sample 与发布准备 | 功能完成 | 双 Agent + Gateway Sample、中文 README、配置/JWT/API/外部接入文档及可复跑门禁脚本 |

“完成”表示仓库内 MVP 功能和自动化测试存在，不表示企业级能力已经提供。gRPC、共享状态、动态注册、OTel spans、
生产 Secret Provider 和分布式配额仍属于 MVP 之后的工作。

## 4. 已交付能力清单

### 4.1 协议和入口

- [x] 固定 A2A 1.0 Proto、lock 和 Agent Card/JSON-RPC fixtures；
- [x] 拒绝旧 A2A 0.2.x 方法和 Card 结构，不做静默降级；
- [x] 提供 JSON-RPC 同步与 SSE 入口；
- [x] 提供 HTTP+JSON 规范短路径、`/gateway/v1` 和 Agent-specific 路径；
- [x] 支持 JSON-RPC 与 HTTP+JSON 双向转换；
- [x] 支持 Send、Get/List/Cancel/Subscribe、四个 Push 配置方法和 Extended Agent Card；
- [x] 在流式首帧前处理上游非 2xx，不把失败伪装成成功 SSE。

### 4.2 发现和路由

- [x] 从静态配置注册逻辑 Agent 和实例；
- [x] 拉取、校验、规范化并周期刷新 Agent Card；
- [x] 校验 Card URL 和接口 URL 的 scheme、DNS/IP/CIDR 与响应大小；
- [x] 支持 Task、Context、显式 Agent、精确 Skill 和租户默认 Agent 路由；
- [x] 路径 Agent 与 Header Agent 冲突时拒绝请求；
- [x] 公开 HTTP Adapter 不接受任意标签，标签仅供受信任扩展命令使用；
- [x] 新任务使用加权最少在途选择，已有任务固定实例和接口。

### 4.3 任务和流式语义

- [x] Gateway Task/Context ID 与上游 ID 分离并递归改写；
- [x] `returnImmediately` 原样转发，不把同步请求转换成流；
- [x] `SubscribeToTask` 保持 Context ID，终态任务返回 Unsupported Operation；
- [x] `ListTasks` 从本地 Task Route 快照分页，不调用外部 Agent；
- [x] 保存状态时间、History 和 Artifact；Artifact append 合并 parts；
- [x] 下游取消传播到 Reactor 上游并释放在途计数；
- [x] 幂等记录区分 IN_FLIGHT、COMPLETED 和 OUTCOME_UNKNOWN。

### 4.4 安全和资源边界

- [x] JWT issuer/JWK、audience、时间、tenant/subject/authority Claim 校验；
- [x] 开发 API Key 默认关闭，secret 只从环境变量读取；
- [x] Agent、Skill、Task Read/Cancel 和 Extended Card 权限矩阵；
- [x] 入站身份与出站身份分离，不默认透传调用方 Token；
- [x] 请求、Card、响应和 SSE 单事件大小限制；
- [x] Card、连接、普通响应和 SSE 空闲超时；
- [x] 不对写请求执行跨实例自动重试；
- [x] 默认 Store 有 TTL、容量、淘汰和过期指标。

### 4.5 可观测与示例

- [x] request ID 在 Access Audit 与数据面审计之间复用；
- [x] 合法 W3C `traceparent`/`tracestate` 透传；
- [x] 请求、流、协议错误和 Store 指标；
- [x] Agent 与上游依赖 HealthIndicator；
- [x] `gateway-hello-world` 连接两个独立 `server-hello-world` Agent；
- [x] Sample 与各子项目只保留中文版 `README.md`。

## 5. 验收矩阵

| AC | 验收内容 | 当前证据 |
| --- | --- | --- |
| AC-01 | 未认证数据面请求被拒绝 | API Key Filter、真实 JWT WebFlux E2E、smoke 脚本 |
| AC-02 | JWT、租户和权限正确执行 | `GatewayJwtAuthenticationConverterTest`、双 Binding 权限矩阵 E2E |
| AC-03 | 目录与 Card 按租户投影 | Catalog Controller/WebFlux 测试 |
| AC-04 | 显式 Agent 路由 | Resolver 和数据面 E2E |
| AC-05 | 精确 Skill 路由 | Resolver 测试及 Sample smoke 脚本 |
| AC-06 | 默认 Agent 与歧义拒绝 | Resolver 测试 |
| AC-07 | 多实例负载选择 | Load Balancer 测试及双健康实例 E2E |
| AC-08 | bulkhead、熔断和半开恢复 | `WeightedLeastActiveLoadBalancerTest` |
| AC-09 | Gateway Task/Context ID 隔离 | Forwarder、Adapter 和 E2E |
| AC-10 | Get/Cancel 固定原实例和接口 | Forwarder 与双 Binding E2E |
| AC-11 | SSE 顺序、ID 改写和终态 | Codec、Streaming Forwarder 和 E2E |
| AC-12 | 200 并发流与客户端取消 | `GatewayHttpJsonDataPlaneE2eTest` |
| AC-13 | 幂等冲突与完成重放 | `InMemoryIdempotencyStoreTest`、Forwarder 测试 |
| AC-14 | 结果未知不自动重试 | Idempotency Store 和 Forwarder 语义 |
| AC-15 | 跨租户/主体任务隔离 | Authorization、Store 和 E2E |
| AC-16 | SSRF、DNS/IP/CIDR 拦截 | `AgentCardUrlPolicyTest`、Transport/Discovery 测试 |
| AC-17 | 非法或不可用上游稳定映射 | Forwarder 和 E2E |
| AC-18 | JSON-RPC/HTTP+JSON 双向转换 | 两个 Adapter、Interface Selector 和 E2E |
| AC-19 | 正文与 secret 不进入默认审计 | Security、Audit、Access Filter 测试及门禁脚本 |
| AC-20 | 单实例状态限制明确 | Architecture、Configuration、Runbook 和本文 |
| AC-21 | 非 1.0 版本明确拒绝 | Protocol contract、Adapter 和 E2E |
| AC-22 | 两种入站 Binding 的成功/错误/授权等价 | `GatewayHttpJsonDataPlaneE2eTest` |
| AC-23 | Push 和 Extended Card 能力门禁与 ID 映射 | Forwarder、Adapter、Controller 测试 |
| AC-24 | `returnImmediately` 与订阅首个 Task/Context 语义 | Core Server 专项测试及 Gateway Adapter 测试 |
| AC-25 | ListTasks 分页、过滤、快照与 Artifact append | Store、Forwarder 和 Streaming Forwarder 测试 |
| AC-26 | HTTP Access 与数据面审计共享 request ID | `GatewayAccessLogWebFilterTest`、`GatewayRequestIdResolverTest` |

## 6. 当前验证记录

### 6.1 2026-08-02 功能回归

本轮执行：

```powershell
.\mvnw.cmd test
```

结果：13 个 Reactor 模块全部 `SUCCESS`，合计 279 tests，0 failures，0 errors。关键模块为：

| 模块 | Tests |
| --- | ---: |
| `a2a4j-core` | 159 |
| `a2a4j-gateway-api` | 5 |
| `a2a4j-gateway-core` | 61 |
| `a2a4j-gateway-spring-boot-starter` | 40 |
| Server Starter、Server Sample、Client Starter | 14 |

Gateway 定向干净构建也已执行：

```powershell
.\mvnw.cmd -pl a2a4j-gateway-spring-boot-starter -am clean test
```

结果为 264 tests，0 failures，0 errors；Checkstyle 0 违规。测试日志中的 Jakarta Bean Validation provider 提示来自
轻量 WebFlux 测试上下文，不影响测试结果，也不是 Gateway 数据面异常。

本轮明确没有执行：

- `tools/g10-performance.ps1`；
- `tools/g10-osv-scan.ps1`；
- `tools/g9-smoke.ps1`；
- `tools/g10-release-gates.ps1`。

因此本轮结论是“功能回归通过”，不是新的性能、安全扫描或完整发布门禁证明。

### 6.2 2026-08-01 历史门禁摘要

仓库仍保留 `g9-smoke.ps1`、`g10-osv-scan.ps1`、`g10-performance.ps1` 和 `g10-release-gates.ps1`，可在正式
发布时复跑。2026-08-01 曾记录：多进程 smoke 成功、87 个运行时依赖 HIGH/CRITICAL 为 0，以及发布门禁通过。
这些是历史信息；对应临时 `target/release-gates` 报告和独立依赖扫描 Markdown 当前不在工作区，不能代替新版本证据。

### 6.3 历史性能参考（本轮未复测）

2026-08-01 的 Windows 开发机脚本快照如下，仅用于保存历史上下文：

| 项目 | 历史值 |
| --- | --- |
| 环境 | Windows 11；Java 21.0.11；本机 loopback |
| Payload | 69 bytes |
| 请求 | 80 sequential；40 concurrent；concurrency 8 |
| Gateway sequential p95/p99 | 4311 ms / 6649 ms |
| Gateway concurrent p95/p99 | 4232 ms / 4233 ms |
| Direct upstream p95/p99 | 4182 ms / 4249 ms |
| sequential p95 差值 | 129 ms，本机估算 |

该数据混合了示例 Agent 约 4 秒执行时间，不能视为 Gateway 容量、纯开销或生产 SLO。当前阶段不要求性能测试。
只有准备容量评估时，才应在目标硬件、固定 JVM、代表性 payload 和可控上游延迟下重新测试，并分别报告 Gateway
自身开销、吞吐、错误率、p95/p99、SSE 长连接和长期稳定性。

## 7. 发布门槛

| 门槛 | 当前状态 |
| --- | --- |
| 全仓编译、Checkstyle 和自动化测试 | 通过（2026-08-02） |
| A2A 1.0 Proto lock 和 Binding 契约 | 通过（2026-08-02） |
| Gateway 功能 AC-01～AC-26 | 自动化测试或可复跑脚本覆盖 |
| Sample package 和多进程 smoke | 正式发布时复跑 |
| OSV/secret/nonblocking 扫描 | 正式发布时复跑；本轮未执行 |
| 性能基线 | 当前不要求；需要容量承诺时在目标环境重跑 |
| 多副本恢复和持久状态 | 不属于当前 MVP，发布说明必须明确 |

不要因为历史门禁通过而自动标记新的提交可发布。每个发布候选应保存与提交版本对应的构建、扫描和 smoke 证据。

## 8. 已知限制

- Task Route、幂等、流配额和熔断状态均为进程内状态；
- Card 刷新是当前唯一独立主动 Agent 探测，没有通用上游 `/health` 探测；
- 默认只支持 `env://` Bearer 出站凭据；
- 没有 gRPC、动态注册、配置热更新、自动重试或调用方 deadline 协商；
- 没有自动创建 OpenTelemetry spans；
- 公开入口不支持路由标签；
- 多实例 Card 的完整 Skills/capabilities 一致性由部署方保证；
- 普通请求没有租户级并发配额，只有 SSE 流配额和实例 bulkhead。

## 9. MVP 后续 Backlog

1. Redis/JDBC Task Route 与 Idempotency Store，定义重启、滚动升级和多副本恢复语义；
2. 分布式 SSE 配额、事件游标和订阅恢复；
3. 可配置 bulkhead/熔断及更完整的 Agent/Route/Circuit 指标；
4. OTel spans、持久化审计和配置版本/审批/回滚；
5. Vault/KMS、mTLS、OBO 和外部策略引擎；
6. 动态 Agent 注册控制平面及 Card 签名/信任链；
7. A2A 1.0 gRPC Binding；
8. 在真实存量需求出现时提供隔离的 A2A 0.2.1 兼容模块；
9. 在目标环境建立可重复的容量、故障注入和长期 soak 测试。
