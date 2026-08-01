# MVP 验收与发布门槛审计

日期：2026-08-01

## 结论

结论为：**正式 MVP 发布门槛通过**（本地可复跑门禁）。

Gateway Core、Spring Boot Starter、A2A 1.0 契约和可运行 Sample 已经可以构建；双 Agent + Gateway
进程 smoke 也能完成 Card 发现、目录查询、健康检查、目标路由和成功审计。新增的 WebFlux + Reactor Netty
端到端测试已覆盖 HTTP+JSON 与 JSON-RPC 入站同步发送、任务查询/取消、SSE 响应体、客户端取消传播、Gateway ID 重写以及未认证/版本拒绝；
HTTP+JSON-only 上游和 JSON-RPC 上游均有真实 Reactor Netty 验证，另有本地 RSA 签发 JWT 经真实 WebFlux Resource Server 过滤链进入数据面并拒绝无效签名。
双入口 Agent/Skill/Task 权限矩阵、secret/nonblocking 源码门禁、OSV 依赖扫描、隔离端口 smoke 和固定参数性能基线已经补齐。
外部组织 SAST/DAST、BlockHound 运行时代理和持久化 Store 压测仍属于企业版增强，不阻塞本地 MVP 发布门槛。

实施过程、阶段交付和验证快照统一见 [mvp-backlog.md](./mvp-backlog.md)；本文只保留本次验收的证据矩阵和发布门槛。

## 已执行证据

| 检查 | 结果 |
| --- | --- |
| `mvnw.cmd clean test -DskipTests=false -Dmaven.clean.failOnError=false` | 通过：13 个 Reactor 模块，250 tests，0 failures，0 errors，0 skipped；Windows 用户进程锁定普通样例 JAR 时 clean 删除失败被显式降级，门禁使用独立 `*-exec.jar` |
| Sample package + `tools/g9-smoke.ps1` | 通过：隔离端口 18191/18192/18199；两个独立 `server-hello-world` Agent（`echo-a`/`echo-b` profile）发布真实 Card 并执行 `DemoAgentExecutor`，Gateway UP、dependency healthy=2、未认证 `401`、错误版本 `400`、目录列表/详情/Card `200`、目录 JSON 非空、目标 `echo-b`、Skill-only 路由、HTTP 200、转发响应体非空、`SEND_MESSAGE outcome=SUCCESS` |
| `GatewayHttpJsonDataPlaneE2eTest` | 通过：15 个 WebFlux + Reactor Netty E2E，覆盖 HTTP+JSON/JSON-RPC 入站、JSON-RPC/HTTP+JSON 上游发送、双健康实例分布、200 并发 SSE、SSE 事件/终态/ID 重写、客户端取消传播、非法/不可用上游响应映射、双入口错误/版本/授权等价、List/Get/Cancel/Subscribe 任务操作、403/跨租户隔离、未认证和版本拒绝，以及真实 RSA JWT Resource Server 过滤链和无效签名 `401` |
| `DefaultAgentInterfaceSelectorTest` | 通过：JSON-RPC 优先、HTTP+JSON-only fallback、非法版本/Binding 拒绝 |
| `GatewayStreamingForwarderTest` / `JsonRpcProtocolAdapterTest` | 通过：流式下游取消传播、`taskId`/`contextId` 不泄露上游 ID |
| `git diff --check` | 通过，无空白错误 |
| `tools/g10-osv-scan.ps1` | 通过：87 个运行时 compile/runtime 依赖，HIGH/CRITICAL=0；报告见 [`mvp-dependency-scan-2026-08-01.md`](./mvp-dependency-scan-2026-08-01.md) |
| `tools/g10-performance.ps1` | 通过：80 sequential / 40 concurrent / concurrency 8；硬件、JVM、payload、上游 p95/p99 见 [`mvp-performance-2026-08-01.md`](./mvp-performance-2026-08-01.md) |
| `tools/g10-release-gates.ps1` | 通过：full-tests、package、diff/secret/nonblocking、OSV、smoke、performance 全部 PASS；机器报告 `target/release-gates/mvp-release-gates.json` |
| 端口清理 | 通过，隔离 smoke/performance 结束后 18191/18192/18199/18091/18092/18099 均无监听进程；不触碰用户 8091/8092/8099 进程 |

此前 Windows HTTP 客户端偶发报告响应体字节缺失的问题已通过 API Key WebFilter 修复；当前
`tools/g9-smoke.ps1` 已直接断言目录 JSON 非空和成功转发响应体非空。2026-08-01 实际运行结果为
`unauthenticated=401 invalid-version=400`、目录列表/详情/Card 均 `200`，目标 `echo-b` 转发成功。

## AC-01～AC-22 证据矩阵

| AC | 结论 | 证据/缺口 |
| --- | --- | --- |
| AC-01 | 通过（样例脚本） | `tools/g9-smoke.ps1` 验证未认证 `/message:send` 返回 `401`。 |
| AC-02 | 通过 | JWT/API Key、issuer/audience/签名/时间校验和授权策略有单测；WebFlux E2E 已用本地 RSA JWT 通过真实 Resource Server 过滤链并验证无效签名 `401`、无权限 `403`，HTTP+JSON/JSON-RPC 均覆盖 Agent、Skill、Task Get/List/Cancel/Subscribe 的 403 策略拒绝和跨租户结果；测试方法为 `coversAgentSkillAndTaskAuthorizationMatrixAcrossBothBindings`。 |
| AC-03 | 通过（WebFlux/单元/样例脚本） | `GatewayAgentCatalogController` 提供 `/gateway/v1/agents`、`/{agentId}`、`/card`；按 tenant 隔离，Card 通过 A2A 1.0 validator，`GatewayAgentCatalogWebFluxTest` 和 smoke 均验证路由。 |
| AC-04/05/06 | 通过（单元 + YAML Card + 真实进程 smoke） | `DeterministicRouteResolverTest` 覆盖显式 Agent、Skill、默认路由和冲突拒绝；两个独立的 `server-hello-world` 进程从 `application-echo-a.yml` / `application-echo-b.yml` 的 `a2a.server` 配置发布 Card，默认由 `echo-a` 发布 `hello-world` + `code-generation`、`echo-b` 发布 `task-summary`。`tools/g9-smoke.ps1` 通过 `X-A2A-Target-Skill: code-generation` 验证 Skill-only 路由唯一选中 `echo-a`，并经 Gateway 转发到真实 `DemoAgentExecutor`。 |
| AC-07 | 通过（单元 + WebFlux E2E） | `WeightedLeastActiveLoadBalancerTest` 覆盖算法/bulkhead；`GatewayHttpJsonDataPlaneE2eTest` 以两个真实 Reactor Netty 健康实例验证新任务分布。 |
| AC-08 | 通过（单元） | `WeightedLeastActiveLoadBalancerTest` 覆盖熔断和半开恢复。 |
| AC-09/10 | 通过（单元 + WebFlux E2E） | `GatewayForwarderTest` 与 `GatewayHttpJsonDataPlaneE2eTest` 覆盖粘滞路由、Gateway Task/Context ID 隔离、任务查询和取消。 |
| AC-11/12 | 通过（单元 + WebFlux E2E） | SSE codec/forwarder 单测、200 并发 SSE 进程级 E2E，以及 HTTP 客户端取消向上游传播 E2E 均通过。 |
| AC-13/14 | 通过（单元） | 幂等键冲突、完成、结果未知和 TTL 单测通过。 |
| AC-15 | 通过（单元 + WebFlux E2E） | 跨租户任务查询返回不泄露任务 ID 的 `404`，同租户不同主体返回 `403`；策略和任务路由单测同步覆盖。 |
| AC-16/17 | 通过（单元 + WebFlux E2E） | SSRF/CIDR、非法 Card 有单测；真实上游非法 JSON 和 HTTP 503 E2E 映射为稳定 Gateway 错误。 |
| AC-18 | 通过（单元 + WebFlux E2E + 真实进程 smoke） | HTTP+JSON→JSON-RPC 和 HTTP+JSON→HTTP+JSON 的同步/SSE 转换、任务操作、响应体、Content-Type 和 ID 重写均有真实上游 E2E；真实 Server Starter smoke 验证 A2A 1.0 文本 Part 在入站/出站链路中保持 `text`/`mediaType` 结构且不生成 `kind`。 |
| AC-19 | 通过 | body-free audit、Header 脱敏和 Token 不落日志有单测；`tools/g10-release-gates.ps1` 对 runtime/source-like 文件执行 bearer/API-key/private-key/client-secret 扫描，并执行 Gateway blocking-call policy scan；外部 CI 可继续接入 SAST/DAST。 |
| AC-20 | 通过（文档） | 单实例重启限制已记录。 |
| AC-21 | 通过（样例脚本/单元 + WebFlux E2E） | `tools/g9-smoke.ps1` 和 JSON-RPC/HTTP+JSON 入站 E2E 均验证错误 `A2A-Version` 返回 `400`，不静默降级。 |
| AC-22 | 通过 | HTTP+JSON 与 JSON-RPC 入站入口、JSON-RPC/HTTP+JSON 上游主路径，以及成功响应、错误 envelope、版本拒绝、Agent/Skill/Task 授权、跨租户结果和 List/Get/Cancel/Subscribe 任务操作等价 E2E 均通过；双入口矩阵由 `GatewayHttpJsonDataPlaneE2eTest` 固化。 |

## 发布门槛

| 门槛 | 结论 |
| --- | --- |
| AC-01～AC-22 全部自动化或可重复脚本 | **通过**：`GatewayHttpJsonDataPlaneE2eTest`、Core/Starter contract tests、隔离 smoke 和双入口权限矩阵覆盖全部验收项。 |
| 无 Critical/High 安全问题 | **通过**：`tools/g10-osv-scan.ps1` 扫描 87 个运行时依赖，HIGH/CRITICAL=0；secret scan 无命中。 |
| 无 Reactor event-loop 阻塞警告 | **通过（MVP）**：`tools/g10-release-gates.ps1` 源码门禁无 Gateway pipeline blocking call；企业 CI 可再叠加 BlockHound。 |
| Store 容量上限和清理指标 | 通过（MVP 默认 Store）/部分通过（可替换 Store）：Task/Idempotency 有 TTL、容量、占用 gauge、evictions/expired counter 和单测；自定义持久化 Store 指标由实现方提供。 |
| 网络调用均有超时 | 通过：Card fetch、连接建立、上游响应和 SSE idle timeout 已配置并有实现/单测。 |
| 写请求无不安全跨实例自动重试 | 通过（单元）：结果未知进入 UNKNOWN，不自动切换实例重发。 |
| secret 不进入样例/日志/指标/追踪/错误体 | **通过（MVP）**：脱敏/body-free audit 单测及 `g10-release-gates` runtime/source-like secret scan 均通过。 |
| README 明确协议、单实例限制和不兼容项 | 通过：README、配置和发布说明已记录。 |
| 迁移项目测试和 A2A 1.0 Binding 契约全部通过 | **通过**：全量测试通过；Client/Server Starter/Samples 已切换 A2A 1.0 method/path，非 1.0 method 只做拒绝校验，不进入运行时兼容路径。 |
| 性能报告 | **通过（基线）**：固定 Windows 硬件/JVM、69-byte payload、80 sequential/40 concurrent/concurrency=8，并记录网关与直连上游 p95/p99。 |

## 发布后增强项（不阻塞 MVP）

1. 在组织 CI 接入 SAST、DAST、BlockHound 和镜像/运行时扫描，并保留同一 release-gates JSON 证据格式。
2. 在目标硬件和代表性上游延迟下重跑性能基线，补充容量目标和长期 soak 测试。
3. 将内存 Registry/TaskRouteStore 替换为 Redis/JDBC，增加跨实例租约、持久化幂等和故障演练。
4. 按真实存量流量决定是否建立隔离的 `a2a4j-compat-v021` 适配层；当前主线只发布 A2A 1.0。
