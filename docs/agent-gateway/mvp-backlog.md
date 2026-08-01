# Agent Gateway MVP 实施清单

## 1. 交付策略

按纵向可运行增量实施。每个增量都必须包含代码、自动化测试、示例配置和最小文档，
不先堆完所有接口再集成。

## 1.1 当前实施进度（2026-08-01）

| 增量 | 状态 | 交付物与验证 |
| --- | --- | --- |
| G0.1 官方协议锁定 | 已完成 | `specification/a2a.proto`、`a2a-1.0.0.lock`、SHA-256 锁定 |
| G0.2 1.0 契约基线 | 已完成 | `A2AProtocolV1`、字段级 Agent Card/JSON-RPC 校验器、7 个 golden fixtures |
| G0.3 Gateway 模块骨架 | 已完成 | API/Core/Spring Boot Starter，父 POM 与 BOM 已纳入 |
| G0.4 Gateway 命名契约 | 已完成 | Header、metadata、A2A 1.0 错误码和协议注册 SPI |
| G0.5 主线 Wire 迁移 | 已完成 | `DefaultA2AClient`、`DefaultDispatcher`、Server Starter、Samples 已切换到 A2A 1.0 方法名/Agent Card 路径；`DefaultA2AClientProtocolV1Test` 和 legacy method rejection contract 覆盖 |
| G0.6 规范文档迁移 | 已完成 | canonical `specification.md` 已切换为 A2A 1.0.0，旧快照保留为 `specification-0.2.1.md`，Proto lock 和兼容入口已落地 |
| G1 领域模型与 SPI | 已完成 | 不可变模型、异步 SPI、TCK 风格契约测试已落地 |
| G2 注册、发现和健康 | 已完成 | YAML 配置校验、Card 拉取/规范化/hash、原子注册快照、主动/被动健康、URL/DNS/IP/CIDR/响应大小策略、Actuator 指标、租户隔离目录 API及自动化测试 |
| G3 鉴权、租户与策略 | 已完成 | WebFlux JWT Resource Server、issuer/audience/签名/时间校验、可信 Claim 映射、Agent/Skill/Task 授权、默认关闭 API Key、`env://` 凭据 provider、统一脱敏及自动化测试 |
| G4 路由与负载均衡 | 已完成 | 任务粘滞、显式 Agent/Skill/标签/租户默认路由、冲突候选错误、加权最少在途、实例 bulkhead、熔断半开、双实例真实分布和可审计 `RouteDecision` |
| G5 非流式转发 | 已完成 | JSON-RPC 1.0 转发、Task/Context ID 重写、粘滞路由、幂等、超时/错误分类、有界 Store |
| G6 SSE 与任务订阅 | 已完成 | 逐事件桥接、取消传播、续订、空闲/事件大小/租户并发限制；200 并发和客户端取消 E2E 已补 |
| G7 HTTP+JSON 数据面 | 已完成 | HTTP+JSON/JSON-RPC 双入口、任务操作、目录 API、Binding 转换、统一错误 envelope、WebFlux 路由、双上游 selector、真实上游 E2E、真实 JWT Resource Server 过滤链和双入口权限等价矩阵 |
| G8 可观测性与运维 | 已完成 | Micrometer、审计、Trace Context、健康检查、Runbook、默认 Store 容量/占用/淘汰/过期指标 |
| G9 发布与兼容性 | 已完成 | Sample、配置/JWT/smoke、发布说明、A2A 1.0 Wire 迁移、OSV/secret/nonblocking 门禁和性能基线 |

当前验证基线：`tools/g10-release-gates.ps1` 已通过，覆盖全量测试、package、diff/secret/nonblocking 扫描、OSV
依赖漏洞扫描、隔离端口 smoke 和 80/40/8 性能基线；报告为 `target/release-gates/mvp-release-gates.json`，可复跑证据见
[`mvp-performance-2026-08-01.md`](./mvp-performance-2026-08-01.md) 与 [`mvp-dependency-scan-2026-08-01.md`](./mvp-dependency-scan-2026-08-01.md)。

### 文档一致性修订（2026-08-01）

- 已同步 `docs/agent-gateway/configuration.md`、`architecture.md` 与当前 Gateway/Card 实现：修正 echo-a 上游端点、JSON-RPC Binding、Skill 归属、A2A-Version 缺省行为、错误码映射及 SSE 多事件说明。
- 已同步 server/client sample 的中英文 README：修正客户端不会自动发送消息的描述，补充 Card `supportedInterfaces`、JSONRPC-only 配置要求及实际调用入口。

## 2. Epic 与任务

### G0：基线与契约

- [x] 固定 A2A `1.0` 官方规范、`a2a.proto` 发布版本和文件校验值；
- [x] 将本地 `specification` 快照升级到 A2A `1.0`：canonical `specification.md`、`a2a.proto` 和 `a2a-1.0.0.lock` 对齐；
- [x] 依据官方 Proto 逐字段校验 `a2a4j-core` 1.0 Wire 契约；
- [x] 为 `SendMessage`、`SendStreamingMessage`、`GetTask`、`ListTasks`、
  `CancelTask`、`SubscribeToTask` 建立 1.0 golden tests；
- [x] 迁移 Client、Server Starter 和 Samples 到 A2A 1.0 method/path；legacy 0.2.1 method 仅保留 rejection contract，不作为运行时 Wire；
- [x] 增加 Gateway 模块到父 POM 和 BOM；
- [x] 建立 Gateway 错误码、Header、metadata 命名常量；
- [x] 根构建同时运行 Core、Client、Server 和 Gateway 测试（CI 工作流沿用根构建命令）。

退出条件：A2A4J 主线可按 1.0 模型构建，升级后的 Samples 可互通，空 Gateway
模块可构建，协议样本可重复验证。

### G1：领域模型与 SPI

- [x] 实现不可变 `AgentDefinition`、`AgentInstance`；
- [x] 实现 `GatewayCommand`、`GatewayEvent`、`RoutingContext`；
- [x] 定义 `AgentRegistry`、`RouteResolver`、`AgentLoadBalancer`、
  `AgentInterfaceSelector`；
- [x] 定义 `ProtocolAdapter`、`AgentTransport`；
- [x] 定义 `TaskRouteStore`、`CredentialProvider`、`AuthorizationPolicy`；
- [x] 为 SPI 编写 TCK 风格的共享契约测试。

退出条件：核心模块不依赖 Spring，所有网络和存储边界均可替换。

### G2：注册、发现和健康

- [x] 实现 YAML Agent 配置绑定和启动校验；
- [x] 实现 A2A `1.0` Agent Card 拉取与规范化；
- [x] 校验 `supportedInterfaces`、Binding、`protocolVersion` 和
  请求头 `A2A-Version: 1.0`；
- [x] 实现 Card hash、定时刷新和原子快照；
- [x] 实现主动健康检查和被动失败记录；
- [x] 实现受控 URL、DNS/IP、CIDR 和响应大小校验；
- [x] 提供 tenant-scoped `AgentRegistry` 列表、详情和 Skill 查询 SPI；
- [x] 暴露 Agent 健康 Actuator 信息，并通过带鉴权的 G3/G7 入口提供租户隔离目录查询。

退出条件：两个逻辑 Agent、每个两个实例可被发现，故障实例不会接收新任务。

### G3：鉴权、租户与策略

- [x] 集成 Spring Security WebFlux Resource Server；
- [x] 校验 issuer、audience、签名和时间 Claim；
- [x] 从可信 Claim 生成 `tenantId` 和权限；
- [x] 实现 Agent/Skill/Task 基础授权；
- [x] 实现开发模式 API Key，并确保默认关闭；
- [x] 实现凭据引用和环境变量 provider；
- [x] 对 Header、日志和异常统一脱敏。

退出条件：安全过滤链和策略 SPI 已可复用；跨租户发现、调用、任务查询和取消的拒绝规则有单元测试，
下游凭据与入站 Token 隔离。实际 HTTP 资源门面随 G7 数据面入口落地。

### G4：路由与负载均衡

- [x] 实现显式 URL Agent 路由；
- [x] 实现 `X-A2A-Target-Agent` 和 `X-A2A-Target-Skill`；
- [x] 实现精确 Skill、标签约束和租户默认 Agent；
- [x] 实现冲突检测和候选错误；
- [x] 实现加权最少在途负载均衡；
- [x] 实现实例 bulkhead 和熔断状态；
- [x] 为每次决策生成可审计 `RouteDecision`。

退出条件：相同输入和配置得到可解释结果；`HEALTHY` 之外的实例、打开熔断的实例和未授权
Agent 永不进入新任务候选集；已有 Task 保留原实例粘滞，不自动迁移。

### G5：A2A 非流式转发

- [x] 实现可注入 Reactor Netty `AgentTransport`；
- [x] 实现连接池、分阶段超时和出站凭据；
- [x] 实现 A2A `1.0` JSON-RPC 编解码和版本协商；
- [x] 实现 `SendMessage` 转发；
- [x] 实现 Gateway Task/Context ID 生成和响应重写；
- [x] 实现有界、带 TTL 的内存 `TaskRouteStore`；
- [x] 实现 `GetTask`、`ListTasks` 和 `CancelTask` 粘滞转发；
- [x] 实现幂等键的处理中、成功、结果未知状态；
- [x] 实现协议和网络错误映射。

退出条件：任务可通过 Gateway ID 查询和取消，任何上游 ID 都不作为路由依据暴露。

### G6：SSE 与任务订阅

- [x] 实现 `SendStreamingMessage` 逐事件桥接；
- [x] 首个 Task 事件到达时原子保存路由；
- [x] 实现 Task/Context ID 的流内重写；
- [x] 实现客户端取消向上游传播；
- [x] 实现事件大小、流空闲和租户并发限制；
- [x] 实现 `SubscribeToTask`；
- [x] 验证事件顺序、背压和错误终止；
- [x] 完成至少 200 个并发 SSE 的稳定性测试。

退出条件：流式任务断开后，在下游支持时可凭 Gateway Task ID 重订阅。

### G7：A2A 1.0 HTTP+JSON 与 Binding 转换

- [x] 实现 `POST /message:send` 和 `POST /message:stream`；
- [x] 实现 `GET /tasks/{id}`、`GET /tasks`、`POST /tasks/{id}:cancel` 和
  `POST /tasks/{id}:subscribe`；
- [x] 把 HTTP+JSON 请求转换为统一 `GatewayCommand`；
- [x] 支持 JSON 和 SSE 响应；
- [x] 实现 HTTP+JSON 入站到 JSON-RPC 上游，以及 HTTP+JSON outbound adapter 编码；
- [x] 实现 JSON-RPC 优先、HTTP+JSON fallback 的 Agent interface selector，并固定任务续订的 Binding；
- [x] 验证 HTTP+JSON-only Agent 的同步/SSE 上游转发和 Content-Type 保真；
- [x] 增加 JSON-RPC 入站 Controller（同步/SSE）并复用同一 Forwarder、鉴权、任务路由和错误 envelope；
- [x] 验证两个 Binding 的功能、错误、授权和事件语义等价；`coversAgentSkillAndTaskAuthorizationMatrixAcrossBothBindings` 覆盖 Agent/Skill/Task Get/List/Cancel/Subscribe 的 403 策略拒绝；
- [ ] 定义有损转换拒绝规则和指标；
- [x] 增加 JSON-RPC/HTTP+JSON 转换、版本拒绝和 ID 重写测试；
- [x] 错误映射为 HTTP+JSON `error.code/message` envelope。

G7 MVP 退出条件：HTTP+JSON 或 JSON-RPC 调用方可通过 Gateway 调用 JSON-RPC 1.0 Agent 或 HTTP+JSON-only Agent，且同步、
SSE、任务标识、授权和错误语义保持一致。双入口主路径已落地；完整双向等价性和转换指标留给后续版本。

### G8：可观测性与运维

- [x] 接入 Micrometer 指标（GatewayMetrics SPI + counter/timer/gauge）；
- [x] 接入 W3C Trace Context（traceparent/tracestate 校验、上下文提取和下游透传）；
- [x] 实现结构化访问日志和安全审计事件（默认无正文、Token、secret）；
- [x] 增加 readiness、liveness 和 dependency health 接入示例；
- [x] 增加 Agent、Operation、错误类型指标/看板字段样例；
- [x] 增加熔断、无健康实例、Task Store 容量告警建议；
- [x] 编写故障排查 Runbook；
- [ ] 企业版接入 OpenTelemetry SDK spans 和跨实例/持久化观测后端。

退出条件：一次调用可以由 requestId 或 traceId 定位到认证、路由和上游结果。

### G9：发布与兼容性

G9 功能基线已完成，配置和 JWT 操作参考见 [configuration.md](./configuration.md) 和
[jwt-local-test.md](./jwt-local-test.md)；正式 RC 结论见
[mvp-acceptance-2026-08-01.md](./mvp-acceptance-2026-08-01.md)。

- [x] 提供连接两个示例 Agent 的可运行 Gateway Sample；
- [x] 提供 JWT 本地测试方式，示例 secret 不进入仓库；
- [x] 执行单元、契约、模块集成和样例进程 smoke 测试，并保留审计成功事件证据；
- [x] 补齐可复跑 secret/nonblocking 源码扫描、OSV 依赖漏洞扫描、隔离端口 smoke 和 80/40/8 非流式性能报告；外部组织 SAST/DAST 仍可在 CI 加强；
- [x] 验证新 Gateway/Sample 边界符合 A2A 1.0，并完成 Client、Server Starter 和 Samples 的主线 Wire 迁移；0.2.1 仅保留拒绝兼容性 contract；
- [x] 生成配置参考和 API 文档；
- [x] 明确 MVP 单实例状态限制和升级路径；
- [x] 发布说明明确 Java API 和 Wire 格式的破坏性升级；
- [x] 仅在确认真实需求后建立 `a2a4j-compat-v021` Epic（当前不创建兼容模块）。

退出条件：新用户按 README 可在本地启动两个 Agent 和一个 Gateway，并跑通验收脚本。

## 3. 最小验收场景

| ID | 场景 | 预期 |
| --- | --- | --- |
| AC-01 | 未认证访问业务入口 | HTTP `401` |
| AC-02 | 已认证但无 Agent 权限 | HTTP `403` 或协议策略拒绝 |
| AC-03 | 查询 Agent 列表 | 只返回当前租户可见 Agent |
| AC-04 | 显式 Agent 同步发送 | 请求到达指定逻辑 Agent |
| AC-05 | 精确 Skill 发送 | 唯一 Agent 被选中 |
| AC-06 | Skill 匹配多个 Agent | 返回路由冲突，不随机调用 |
| AC-07 | 逻辑 Agent 有两个健康实例 | 新任务按权重分布 |
| AC-08 | 一个实例连续失败 | 熔断后新任务不再进入 |
| AC-09 | 查询或取消已有任务 | 始终回到创建任务的实例 |
| AC-10 | 两个 Agent 返回相同上游 Task ID | Gateway Task ID 不冲突 |
| AC-11 | SSE 正常执行 | 事件有序、ID 已重写、终态正常 |
| AC-12 | 客户端中断 SSE | 上游连接和本地资源被释放 |
| AC-13 | 相同幂等键重复请求 | 不产生不可控的重复任务 |
| AC-14 | 上游超时且结果未知 | 不自动切换实例重发 |
| AC-15 | 跨租户读取 Gateway Task ID | 拒绝且不泄露任务存在性 |
| AC-16 | 恶意 Card URL | SSRF 策略拒绝 |
| AC-17 | 上游返回非法 A2A payload | 返回规范化协议错误并记录指标 |
| AC-18 | HTTP+JSON 调用 JSON-RPC Agent | 同步和 SSE 均正确转换 |
| AC-19 | 日志与追踪检查 | 无 Token、secret 和消息正文 |
| AC-20 | 重启内存版 Gateway | 文档明确旧任务路由不可恢复 |
| AC-21 | 缺失或错误的 `A2A-Version` | 明确拒绝，不静默降级 |
| AC-22 | JSON-RPC 与 HTTP+JSON 等价请求 | 结果、错误和授权语义等价 |

## 4. 测试分层

```text
Unit
  路由、选择、ID 重写、错误映射、配置校验
Contract
  Registry、TaskRouteStore、ProtocolAdapter、CredentialProvider SPI
Golden
  A2A 1.0 JSON-RPC 与 HTTP+JSON 请求/响应/SSE 样本
Integration
  Gateway + 两个测试 Agent + 模拟 IdP
Fault
  超时、断流、非法响应、熔断、Store 失败、凭据失败
Security
  JWT、租户越权、SSRF、Header 注入、日志脱敏、payload 限制
Load
  非流式延迟、连接池、200 SSE、慢消费者、资源回收
Regression
  升级后的 a2a4j-core、Client、Server Starter 和 Samples
```

## 5. 发布门槛

发布 MVP 前必须全部满足：

2026-08-01 发布门禁已通过：`tools/g10-release-gates.ps1` 统一执行构建、测试、扫描、smoke 和性能基线；逐项证据见
`docs/agent-gateway/mvp-acceptance-2026-08-01.md`。外部组织 SAST/DAST、BlockHound 和持久化 Store 压测仍属于企业版增强，不阻塞本地 MVP 门禁。

- [x] 所有 AC-01 至 AC-22 自动化或形成可重复验收脚本；
- [x] 没有 Critical/High 安全问题（OSV 报告 HIGH/CRITICAL=0）；
- [x] 没有 Reactor event-loop 阻塞警告（源码门禁；企业 CI 可加 BlockHound）；
- [x] 默认任务和幂等 Store 有容量上限及占用清理指标；持久化 Store 的容量/清理指标由企业版实现负责；
- [x] 所有网络调用有超时；
- [x] 写请求没有不安全的跨实例自动重试；
- [x] secret 不在配置样例、日志、指标、追踪和错误体中（源码/runtime-like secret scan）；
- [x] README 明确协议版本、单实例限制和不兼容项；
- [x] 迁移后的项目测试和 A2A 1.0 Binding 契约测试全部通过；
- [x] 性能测试报告包含硬件、JVM、payload、并发和上游延迟说明。

## 6. MVP 实施过程记录

本节集中记录 G0-G9 的实施过程和收口状态。README 只做导航，architecture 只保留设计基线，
配置、Runbook、验收和发布说明只作为配套参考，不再复制阶段日志。

| 阶段 | 关键交付 | 验证与配套文档 | 当前结论 |
| --- | --- | --- | --- |
| G0 | 锁定 A2A `1.0.0` Proto/校验值，建立 Agent Card、JSON-RPC golden fixtures、Gateway 模块和命名契约 | Core 契约测试；架构与 README 基线；Client/Server/Samples 1.0 method/path contract | 完成；legacy 0.2.1 method 仅保留拒绝测试 |
| G1 | 不可变领域模型、异步 SPI、凭据脱敏和 TCK 风格共享契约 | Gateway API/Core 契约测试 | 完成 |
| G2 | YAML 配置、Card 拉取/规范化/hash、原子注册快照、主动/被动健康、SSRF 策略、tenant-scoped 目录 API | Registry/探测测试、目录 WebFlux 测试、`g9-smoke.ps1` | 完成 |
| G3 | JWT/API Key 入站认证、Claim 到 `PrincipalContext`、Agent/Skill/Task 授权、`env://` 出站凭据和脱敏 | Security/Core 测试、目录未认证/跨租户验证、本地 RSA JWT 经真实 WebFlux Resource Server 过滤链的入站 E2E | 完成 |
| G4 | 确定性路由、任务粘滞、加权最少在途、bulkhead、熔断和可审计 `RouteDecision` | Resolver/Load Balancer 测试、双健康实例 WebFlux E2E 分布验证 | 完成 |
| G5 | JSON-RPC 非流式转发、Task/Context ID 重写、幂等、超时/错误分类、有界 TTL Store | Forwarder/Transport/Store 测试 | 完成 |
| G6 | SSE 逐事件桥接、取消传播、续订、终态保存、空闲/事件大小/租户并发限制 | SSE/流式编排测试、HTTP 客户端取消向上游传播 E2E、200 并发 SSE E2E | 完成 |
| G7 | HTTP+JSON/JSON-RPC 双入口、任务操作、目录 API、Binding 转换、统一错误 envelope 和 WebFlux 边界 | Adapter/Starter WebFlux 测试、`GatewayHttpJsonDataPlaneE2eTest`（含双入口 Agent/Skill/Task 权限矩阵）、`GatewayJsonRpcController`、HTTP+JSON-only selector、真实 JWT 过滤链、故障/版本/授权/任务操作等价和 smoke | 完成 |
| G8 | Micrometer 指标、Trace Context、body-free 审计、健康检查、Runbook、Store 容量/占用/淘汰/过期指标 | 指标/审计/健康测试、配置与 Runbook | 功能完成 |
| G9 | 双 Agent Sample、配置/JWT 本地测试、smoke、发布说明和 0.2.1 隔离策略 | package、`tools/g9-smoke.ps1`、`tools/g10-release-gates.ps1`、`tools/g10-osv-scan.ps1`、性能报告和验收审计 | 正式 MVP 门禁通过 |

### G9 交付与兼容边界

- `gateway-hello-world` 的 `*-exec.jar` 只启动 Gateway。`echo-a` 和 `echo-b` 是两个独立的
  `server-hello-world` 进程，分别通过 `echo-a`/`echo-b` Spring profile 启动；二者由 Server Starter
  发布 `/.well-known/agent-card.json`，并真实执行
  `DemoAgentExecutor`。示例 Card 仅声明实际实现的 A2A 1.0 JSON-RPC binding。
- Gateway 默认使用开发 API Key；生产环境切换 JWT Resource Server，并通过环境变量或外部 Secret
  Provider 注入密钥。`tools/g9-smoke.ps1` 覆盖双 Agent 健康、目标路由、目录 API、未认证/版本拒绝和成功审计。
- API Key 入口显式关闭 HTTP Basic/Form Login，并使用普通 `401` entry point；未携带 key 不再返回
  `WWW-Authenticate: Basic`，浏览器不会弹出用户名/密码框；该回归由 smoke 脚本的响应头断言覆盖。
- 修复 API Key WebFilter 将正常完成的 `Mono<Void>` 误判为空、在响应写出后覆盖为 `401` 的问题；目录
  列表/详情/Card 及数据面响应现在能正常完成，smoke 额外校验目录 JSON 和转发响应体非空。
- 新 Gateway、Client、Server Starter 和 Samples 边界均以 A2A 1.0 为基线；旧 0.2.1 method 不作为运行时 Wire，
  仅保留明确拒绝 contract。只有出现可验证的存量流量时，才建立隔离的 `a2a4j-compat-v021` Epic。
- 注册表、Card 快照和 Task Route Store 为单实例内存实现，重启后旧任务不可恢复；企业版先替换为
  Redis/JDBC，再增加跨实例幂等、租约、分布式限流和 OpenTelemetry 后端，升级时保持
  `A2A-Version: 1.0` 显式协商。
- 配置与 API 参考已分别整理：`configuration.md` 记录静态注册、Card 刷新/入库、完整配置项、鉴权和 MVP/企业版边界；
  `api-reference.md` 记录目录、HTTP+JSON、JSON-RPC、SSE、Task、Actuator、请求 Header 与错误映射。

实施规则：每个阶段按“代码 → 自动化测试 → 示例配置/运行方式 → 文档 → 根回归”收口；
阶段状态只追加到本节，缺口保留在验收矩阵中，不通过复制新文档拆分过程。

当前验证快照（2026-08-01）：

```text
mvnw.cmd clean test -DskipTests=false -Dmaven.clean.failOnError=false -> 13 个 Reactor 模块，246 tests，0 failures/errors/skipped
mvnw.cmd package -DskipTests           -> 通过（Gateway 与 Server sample 分别产出 `gateway-hello-world-0.0.1-exec.jar`、`server-hello-world-0.0.1-exec.jar`；避免运行中的普通 jar 被 Windows 锁定）
tools/g9-smoke.ps1 (18191/18192/18199) -> 通过（两个独立 Server Starter Agent、health、401、版本拒绝、目录、显式 Agent 路由、Skill-only 路由、真实 HTTP+JSON/JSON-RPC SendMessage 与 SendStreamingMessage→GetTask Route、审计）
tools/g10-osv-scan.ps1                 -> 通过（87 runtime dependencies，HIGH/CRITICAL=0）
tools/g10-performance.ps1              -> 通过（80 sequential/40 concurrent/concurrency=8，报告含 p95/p99）
tools/g10-release-gates.ps1            -> 通过（target/release-gates/mvp-release-gates.json）
GatewayHttpJsonDataPlaneE2eTest        -> 15 个 WebFlux + Reactor Netty HTTP+JSON/JSON-RPC 入站、200 并发 SSE/客户端取消/双实例分布/双上游/故障与版本/授权/任务操作等价/租户隔离/真实 RSA JWT 过滤链 E2E 通过
DefaultAgentInterfaceSelectorTest      -> Binding 优先级、fallback 和拒绝规则通过
GatewayStreamingForwarderTest          -> 下游取消传播单测通过
git diff --check                        -> 通过
```

正式发布门槛以 [`mvp-acceptance-2026-08-01.md`](./mvp-acceptance-2026-08-01.md) 为准；外部 CI SAST/DAST、BlockHound 和持久化 Store 压测属于企业版增强项。

### 6.1 本轮问题修正（2026-08-01）

- [x] 确认 Skill 能力来源：Agent Card `skills[]` 为 MVP 的唯一声明入口；目录、路由和 `skill:invoke:{skillId}` 授权均复用该快照，暂不提供运行时 Skill 注册 API。
- [x] 将 Gateway sample 的两个 Agent 迁移为真实 `server-hello-world` 进程：Card 与 Skill 由 `application-echo-a.yml` 和 `application-echo-b.yml` 的 `a2a.server` 配置驱动；默认 `echo-a` 发布 `hello-world` + `code-generation`，`echo-b` 发布 `task-summary`。
- [x] 增加 Skill-only 路由回归：`X-A2A-Target-Skill: code-generation` 在默认样例中唯一选中 `echo-a`；多个 Agent 声明同一 Skill 时仍按冲突策略拒绝歧义请求。
- [x] 示例流式能力改由真实 `DemoAgentExecutor` 的 `RequestContext`/`EventQueue` 生命周期提供：首帧 `TASK_STATE_WORKING/final:false`，中间包含状态和 Artifact 事件，终帧 `TASK_STATE_COMPLETED/final:true`，所有事件共享同一 Task ID。
- [x] 删除 Gateway 进程内 `SampleAgentController` HTTP fixture；smoke 通过 Gateway 发现并转发至两个独立 Server Starter Agent，验证真实 Card、Skill 和执行链路。
- [x] 修正 HTTP+JSON 入站到 JSON-RPC 上游的 Part 结构：文本 Part 直接使用 A2A 1.0 的 `text`/`mediaType` 字段，Gateway 不生成 A2A v0.3 的 `kind` 判别字段；`JsonRpcProtocolAdapterTest` 和真实 smoke 均覆盖。
- [x] 更新配置/API 参考，补充多 Skill Card 示例、Skill 路由/权限和 SSE 客户端读取说明。
- [x] 将配置与接口契约拆分：`configuration.md` 只保留启动、注册、安全和运行限制；新增 `api-reference.md`，按当前 Controller/Adapter 核对并完整列出目录、HTTP+JSON、JSON-RPC（含 Push/Card 方法）、SSE、任务、错误和 sample Actuator 端点。
- [x] 补充 JSON-RPC 任务调用示例，并更正 Push 边界：Push 方法目前仅被 Adapter 识别，MVP 尚未实现 Gateway Task ID 到上游 Task ID 的映射、配置持久化或回调代理；文档明确要求需要 Push 时直接调用支持该能力的 Agent。
- [x] 修正 JSON-RPC 文本 Part 示例并加入真实 smoke：JSON-RPC 入站和出站均使用不带 `kind` 的 A2A 1.0 `text`/`mediaType` Part。smoke 现覆盖真实 JSON-RPC `SendMessage→GetTask`。
- [x] 固化 A2A 1.0 流式事件 wrapper：JSON-RPC SSE `result` 使用 `statusUpdate`/`artifactUpdate`，Gateway 按 oneof wrapper 提取并改写 `taskId`/`contextId`，真实 `SendStreamingMessage→GetTask` smoke 固化回归。
- [x] 回归验证：Gateway sample 测试、全仓 Maven 测试、`package -DskipTests` 和隔离端口 `g9-smoke.ps1` 均通过。
- [x] 同步 `server-hello-world` 与 `client-hello-world` 的 `README.md` 和 `README_CN.md`：使用 A2A 1.0 方法名、Agent Card 主路径、`A2A-Version: 1.0` 和当前 Client 本地入口。

## 7. MVP 之后的第一个 Backlog

建议紧接 MVP 处理：

1. Redis/JDBC 任务路由和幂等存储；
2. A2A `1.0` gRPC Binding；
3. Agent Card 签名验证和完整 Push Notification 代理；
4. Gateway 多副本与滚动升级测试；
5. 分布式限流和策略热更新；
6. 独立控制平面和动态注册审批；
7. 有真实存量需求时提供隔离的 `a2a4j-compat-v021`。
