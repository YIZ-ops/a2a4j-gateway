# A2A4J Agent Gateway 规划文档

本文档集用于指导在 A2A4J 当前代码库上建设 Agent Gateway。

## 文档导航

- [MVP 实施过程与 Backlog](./mvp-backlog.md)：G0-G9 唯一过程入口，集中维护阶段交付、验证记录、验收场景和发布门槛。
- [架构与详细设计](./architecture.md)：目标、模块边界、接口、数据模型、安全、可观测性和企业级演进设计基线。
- [Gateway 配置指南](./configuration.md)：启动、静态 Agent 注册、路由、安全、超时、健康指标和 MVP 限制。
- [Gateway API 参考](./api-reference.md)：完整北向 API 清单，包含目录、HTTP+JSON、JSON-RPC、SSE、任务、错误和 Actuator 端点。
- [故障排查 Runbook](./runbook.md)：按 requestId/traceId、健康状态、路由和上游错误定位问题。
- [JWT 本地测试](./jwt-local-test.md)：JWT Resource Server 的本地验证步骤。
- [MVP 验收审计](./mvp-acceptance-2026-08-01.md)：当前发布门槛的证据矩阵与缺口。
- [性能基线](./mvp-performance-2026-08-01.md)：固定 Windows 开发机上的非流式 p95/p99 基线。
- [依赖漏洞扫描](./mvp-dependency-scan-2026-08-01.md)：OSV Maven runtime dependency 扫描结果。

## 当前状态（2026-08-01）

MVP G0-G9 的实施过程、阶段状态和验证命令统一记录在
[mvp-backlog.md](./mvp-backlog.md)，本文档不再复制逐阶段进度。当前功能基线已通过：全仓库
`clean test`（247 tests）、HTTP+JSON/JSON-RPC 双入口、200 并发 SSE、客户端取消传播、双实例分布、双上游 Binding、故障/版本/授权/任务操作等价与租户隔离数据面 E2E、真实 RSA JWT Resource Server 过滤链、Sample smoke、目录 API 和默认 Store 指标均有证据；`tools/g10-release-gates.ps1` 已通过完整权限矩阵、OSV/secret/nonblocking 扫描、隔离 smoke 和性能基线，正式 MVP 门槛通过。外部 CI SAST/DAST、BlockHound 和持久化 Store 压测属于企业版增强。

## 已确定的关键决策

1. 网关作为新模块建设，不把多 Agent 路由、安全和治理逻辑写入 `a2a4j-core`。
2. 当前项目处于 `0.0.1` 阶段，Gateway MVP 和 A2A4J 主线直接升级到 A2A
   `1.0`，不以本地旧规范 `0.2.1` 作为新架构基线。
3. MVP 原生支持 A2A `1.0` JSON-RPC 和 HTTP+JSON，并通过统一内部命令完成两种
   Binding 的相互转换；gRPC 放入后续版本。
4. MVP 的自动路由只做确定性规则：显式 Agent、精确 Skill、标签规则。暂不引入 LLM 语义路由。
5. 新任务可负载均衡；已创建任务必须依据任务路由映射回到原 Agent 实例。
6. 入站身份和出站 Agent 凭据严格分离，默认不透传调用者令牌。
7. MVP 可单实例运行，但状态、策略、协议和凭据均通过 SPI 隔离，后续可替换为企业级实现。
8. `0.2.1` 不属于 MVP；只有确认存在真实存量调用方时，才新增隔离的
   `a2a4j-compat-v021` 模块，不污染 1.0 核心模型。

## 建议的首个发布形态

```text
a2a4j-core                         # 直接升级为 A2A 1.0 模型与抽象操作
a2a4j-gateway-api
a2a4j-gateway-core
a2a4j-gateway-spring-boot-starter
a2a4j-samples/gateway
```

首个可演示版本至少连接两个升级后的 A2A `1.0` Agent，完成发现、路由、
JSON-RPC/HTTP+JSON 转换、同步转发、SSE 转发、任务查询和取消，并能够展示鉴权、
故障摘除和链路追踪。

规划假设是当前尚无必须兼容的生产级 `0.2.1` 调用方。如果该假设不成立，只调整兼容
模块的优先级，不改变 Gateway 以 A2A `1.0` 为核心的方向。

## 当前实现边界

已落地的 Spring WebFlux 入口包括：

- `GET /gateway/v1/agents`、`GET /gateway/v1/agents/{agentId}`、`GET /gateway/v1/agents/{agentId}/card`；
- `POST /message:send`、`POST /message:stream`；
- `POST /a2a`、`POST /gateway/v1/a2a`（A2A 1.0 JSON-RPC，同步与 SSE）；
- `GET /tasks/{id}`、`GET /tasks`；
- `POST /tasks/{id}:cancel`、`POST /tasks/{id}:subscribe`；
- 等价的 `/gateway/v1/...` 路径以及带 `/agents/{agentId}` 的显式路由路径。

入口从 Spring Security 的 `GatewayAuthenticationToken` 获取不可变 `PrincipalContext`，路径 Agent ID
只作为受控路由提示，不能覆盖租户和授权策略；错误统一映射为 HTTP+JSON
`{"error":{"code","message"}}`，并覆盖 400/401/403/404/409/429/502/503/413。请求体默认上限为
`a2a.gateway.max-request-bytes: 1MB`。

默认上游优先选择 JSON-RPC 1.0；当 Agent 仅提供 HTTP+JSON 时自动 fallback 到 HTTP+JSON，JSON-RPC 与 HTTP+JSON 均可作为入站入口，且任务续订固定原始
Binding。两个 adapter 均提供双向编码能力，跨实例事件回放和完整等价性验证属于后续企业版扩展。Starter 在存在 Micrometer `MeterRegistry` 时自动
注册网关指标；应用可按需配置 Actuator 探针：

```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
  endpoint.health.group.readiness.include: gatewayAgentHealthIndicator,gatewayDependencyHealthIndicator
  endpoint.health.group.liveness.include: ping
```

Gateway 运行限制、完整端点说明和验收缺口分别见 [configuration.md](./configuration.md)、
[api-reference.md](./api-reference.md)、[mvp-backlog.md](./mvp-backlog.md) 和
[mvp-acceptance-2026-08-01.md](./mvp-acceptance-2026-08-01.md)。
