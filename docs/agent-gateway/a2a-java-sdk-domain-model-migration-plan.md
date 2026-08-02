# A2A Java SDK 领域模型迁移计划

## 1. 文档状态

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 制定日期 | 2026-08-01 |
| 目标协议 | A2A 1.0 |
| SDK 基线 | `org.a2aproject.sdk` `1.1.0.Final`，实施前再次确认 |
| 迁移策略 | 分批替换、兼容层隔离、每批可独立回滚，最终删除遗留实现 |
| 首要范围 | A2A 标准领域模型及其序列化契约 |

本文档定义把 A2A4J 自有的 A2A 1.0 标准领域模型逐步迁移到官方
[A2A Java SDK](https://github.com/a2aproject/a2a-java) 的实施方案。迁移只接管标准协议数据语义，
不重写 Gateway 路由、鉴权、传输和治理管线。

## 2. 目标

1. 使用官方 SDK 模型作为 A2A 1.0 标准对象的事实来源，减少规范漂移和重复维护。
2. 保持 Gateway 的公开协议行为、路由语义、Task ID 映射和安全边界不变。
3. 保持 Spring WebFlux、Reactor Netty 和现有 Gateway SPI 不变。
4. 通过已有 JSON fixture、契约测试和 E2E 测试证明迁移前后的线协议兼容。
5. 为旧 `io.github.a2ap.core.model` 使用者提供明确的弃用和迁移窗口。
6. 保证每个迁移批次均可通过依赖或实现切换快速回滚。
7. 兼容窗口结束后删除旧标准模型、兼容 mapper 和 legacy/shadow 执行路径，最终只保留官方 SDK 模型。

## 3. 非目标

本计划不包含：

- 使用官方 Quarkus reference server 替换 Spring Boot Starter；
- 使用官方 Client 一次性替换 `ReactorNettyAgentTransport`；
- 重写 `GatewayForwarder`、路由、负载均衡、鉴权、幂等或 Task Route；
- 在本次迁移中增加 gRPC；
- 同时支持新的协议版本；
- 删除整个 `a2a4j-core` artifact；本计划最终只删除其中已被官方 SDK 替代的旧标准模型和兼容代码；
- 在同一批次修改 Gateway 公共 SPI 和线协议。

## 4. 核心边界

### 4.1 迁移到官方 SDK 的标准模型

按风险从低到高分三组：

| 批次 | 模型 | 说明 |
| --- | --- | --- |
| A | `AgentCard`、官方 `AgentInterface`、`AgentSkill`、`AgentCapabilities` | Agent 发现和能力声明 |
| B | `Message`、`Part`、`TextPart`、`FilePart`、`DataPart` | 消息和内容载荷 |
| C | `Task`、`TaskStatus`、`TaskState`、`Artifact` | Task 生命周期和流式事件 |
| D | `MessageSendParams`、`MessageSendConfiguration`、`TaskQueryParams`、`TaskIdParams`、`PushNotificationConfig`、`JSONRPCError` | 操作参数和协议错误 |

最终使用的官方类名、构造器和 artifact 必须以锁定版本的 Javadoc 和编译结果为准，不能只按当前自有类名做机械替换。

### 4.2 必须保留的 Gateway 模型

以下模型表达的是 Gateway 治理语义，不得用官方 SDK 模型替换：

- `GatewayCommand`、`GatewayEvent`、`GatewayResult`；
- `AgentDefinition`、`AgentInstance`；
- `RouteDecision`、`RoutingContext`、`TargetHint`；
- `TaskRoute`、`IdempotencyRecord`；
- `PrincipalContext`、`AuthorizationDecision`；
- `OutboundRequest`、`OutboundResponse`、`OutboundCredentials`；
- `ProtocolDescriptor`。

### 4.3 `AgentInterface` 同名模型处理

项目内 `io.github.a2ap.gateway.api.model.AgentInterface` 包含 `interfaceKey`、归一化 endpoint、
binding 和版本等 Gateway 选择信息，是路由模型；官方 SDK 的 `AgentInterface` 是 Agent Card 的标准声明模型。
两者职责不同：

```text
官方 AgentCard.AgentInterface
          ↓ AgentCard SDK mapper/normalizer
Gateway AgentInterface
          ↓ 路由、实例选择、Task Route
```

迁移时不得直接删除或改名 Gateway `AgentInterface`。实现代码必须使用完整 import 或显式命名，避免误用同名类型。

## 5. 目标架构

```text
外部 A2A JSON
      ↓
官方 SDK 标准模型与序列化契约
      ↓
SdkA2AModelMapper / AgentCardNormalizer
      ↓
GatewayCommand、GatewayEvent、Gateway AgentInterface
      ↓
现有鉴权、路由、负载均衡、Task Route、幂等
      ↓
现有 ProtocolAdapter 与 ReactorNettyAgentTransport
      ↓
上游 Agent
```

过渡期允许在协议边界把官方类型转换为现有 `Map<String, Object>`，但该转换只能存在于适配层；
不得让 SDK 类型扩散到路由、Store 和安全 SPI。

长期可在下一个主版本把 `GatewayCommand.message`、`configuration` 从 Map 升级为类型化 payload，
但这不属于本计划的首轮实施范围。

## 6. 依赖策略

### 6.1 版本管理

在根 `pom.xml` 的 `dependencyManagement` 中导入官方 SDK BOM，并使用单一属性锁定版本：

```xml
<properties>
    <a2a-java-sdk.version>1.1.0.Final</a2a-java-sdk.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.a2aproject.sdk</groupId>
            <artifactId>a2a-java-sdk-bom</artifactId>
            <version>${a2a-java-sdk.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

实施前应确认 `1.1.0.Final` 仍是批准采用的版本，并记录：

- Maven Central 坐标和校验和；
- Java 17 兼容性；
- SDK 使用的 JSON 库、Protobuf、Jakarta/CDI 等传递依赖；
- 与当前 Jackson、Spring Boot、Netty 版本的冲突；
- SDK artifact 版本与 A2A 协议版本不是同一概念。

### 6.2 模块放置

第一阶段优先在 `a2a4j-core` 中引入最小 SDK `spec` 依赖，避免 Gateway API 直接暴露 SDK。
若依赖隔离或兼容映射明显复杂，则新增：

```text
a2a4j-protocol-sdk-adapter
```

该模块只负责：

- 官方模型与遗留模型的转换；
- JSON fixture 兼容验证；
- SDK 异常到内部错误的转换；
- 迁移期开关和诊断信息。

禁止为了使用领域模型而引入 `a2a-java-sdk-reference-jsonrpc`、
`a2a-java-sdk-reference-rest` 等 Quarkus reference server artifact。

## 7. 实施阶段

### P0：依赖和 API 探针

目标：证明 SDK 可以安全进入当前 Maven 依赖图，不改变生产行为。

任务：

1. 导入 SDK BOM 和最小 `spec` artifact。
2. 运行 `mvn dependency:tree`，审计 JSON、Jakarta、Protobuf 和日志依赖冲突。
3. 创建仅测试使用的 SDK 模型构造及序列化 smoke test。
4. 使用现有 `agent-card.json`、`jsonrpc-send-message.json`、streaming fixture 做读取实验。
5. 确认未知字段、`null`、空集合、枚举值、二进制文件内容和 metadata 的行为。
6. 记录官方模型与当前模型的字段差异表。

退出标准：

- 全仓库原有测试不变且通过；
- 依赖扫描没有未处理的高危冲突；
- SDK 能读取代表性 A2A 1.0 fixture；
- 已确认各目标模型的准确包名和 API。

回滚：移除 BOM、SDK 依赖和探针测试，不影响生产代码。

### P1：Agent Card 模型

范围：

- 官方 `AgentCard`；
- 官方 `AgentInterface`；
- `AgentSkill`；
- `AgentCapabilities`。

主要影响点：

- `ReactorNettyAgentCardFetcher`；
- `AgentCardNormalizer`；
- Agent Card validator/fixture；
- Server Starter 自身 Card；
- Hello World Server 和相关文档示例。

实施方式：

1. 拉取层保留现有 SSRF、超时、响应大小和重定向策略。
2. 将响应 body 解析为官方 `AgentCard`。
3. 新增显式 mapper，把官方 Card 转换为 Gateway `AgentDefinition`、`AgentSkillDefinition` 和 Gateway `AgentInterface`。
4. 保留原 JsonNode 路径作为可切换的 legacy 实现，直到 P1 验收结束。
5. 对公开 Card 进行字段级、新旧 JSON 级对比。

重点验证：

- `supportedInterfaces` 的 binding、URL、tenant、protocolVersion；
- public 与 authenticated extended Agent Card；
- security schemes 和 requirements；
- skills、input/output modes、capabilities；
- 未知扩展字段；
- Card endpoint 仍经过相同 URL 安全策略。

退出标准：

- 所有现有 Agent Card fixture 双向兼容；
- Agent 目录 API 和健康探针 E2E 行为不变；
- 路由选择结果与迁移前一致。

### P2：Message 和 Part 模型

范围：

- `Message`；
- `Part`、`TextPart`、`FilePart`、`DataPart`。

实施方式：

1. 在协议 adapter 内解析为官方 `Message`。
2. 新增 `SdkMessageMapper`，过渡期转换到 `GatewayCommand.message` 的 Map 表示。
3. 出站时从 Gateway 表示构造官方 `Message`，再生成兼容 JSON。
4. 不修改 `GatewayCommand` 的公开 record 签名。
5. 旧 `io.github.a2ap.core.model.Message/Part` 暂时保留，不在本阶段删除。

重点验证：

- `messageId`、`contextId`、`taskId` 和 role；
- Part 多态判定及列表顺序；
- File URI 与 base64 bytes；
- DataPart 任意 JSON 数据；
- metadata 和 extensions；
- 空 Parts、未知 Part 和非法组合的错误行为；
- JSON-RPC 与 HTTP+JSON 两种 binding 的等价性。

退出标准：

- 现有 send-message fixture 和协议 contract test 全部通过；
- 新旧实现生成的规范化 JSON 等价；
- Gateway 路由、鉴权和幂等哈希结果没有非预期变化。

### P3：Task、Status 和 Artifact 模型

范围：

- `Task`、`TaskStatus`、`TaskState`；
- `Artifact`；
- 与 Task 相关的 status/artifact streaming event。

实施方式：

1. 先迁移非流式 Task 响应。
2. 再迁移 SSE status update 和 artifact update。
3. SDK Task ID 只表示上游 ID；对外 Gateway ID 仍由 `TaskRouteStore` 和转发层重写。
4. Gateway Event 类型和 Task 终态判断保留在 Gateway 内部。

重点验证：

- 全部 A2A 1.0 TaskState 值及未知值行为；
- Task history、status message、timestamp；
- Artifact append/lastChunk 语义；
- SSE 事件顺序、结束条件和错误事件；
- Gateway Task ID、upstream Task ID、contextId 不泄漏或串用；
- `GetTask`、`ListTasks`、`CancelTask`、`SubscribeToTask` 等价性；
- 客户端取消仍能传播到底层 Reactor Netty 连接。

退出标准：

- Task 操作、SSE、双实例粘滞和租户隔离 E2E 全部通过；
- 新旧事件序列在规范化后等价；
- 负载和取消测试无回退。

### P4：请求参数、Push Notification 和错误

范围：

- `MessageSendParams`、`MessageSendConfiguration`；
- `TaskQueryParams`、`TaskIdParams`；
- `PushNotificationConfig` 及相关请求参数；
- `JSONRPCError`。

实施方式：

1. 用官方参数模型替换协议 adapter 内的手工字段读取。
2. Gateway 头部、租户、target hint、idempotency key 和 trace 信息继续单独处理。
3. 官方错误必须先映射为 Gateway 内部错误，再由当前入口输出 JSON-RPC 或 HTTP+JSON 错误。
4. 禁止让 SDK 异常堆栈、内部 URL 或凭据直接进入响应。
5. 检查当前 `gatewayTaskId` 等非标准字段，迁移到内部状态、metadata 或明确 extension，不能继续依赖宽松反序列化。

退出标准：

- JSON-RPC code/message/data 和 HTTP status 映射保持兼容；
- Push Notification 全部操作通过 contract test；
- 非法参数、未知方法、版本错误、Task not found、授权错误均通过负向测试。

### P5：遗留模型弃用和迁移窗口

任务：

1. 将已替代的 `io.github.a2ap.core.model` 类型标记为 deprecated。
2. 在迁移指南中给出旧类型到官方类型的对应关系和代码示例。
3. 更新 `README`、`README_CN`、`API_REFERENCE` 和 Starter 文档。
4. 检查公开方法签名的源兼容和二进制兼容；必要时保留 deprecated overload/facade。
5. 在发布说明中明确下一主版本将删除旧模型、兼容 mapper 和 legacy/shadow 开关。

退出标准：

- 主代码不再新建遗留标准模型；
- 遗留类型只存在于兼容层和迁移测试；
- 发布说明明确弃用周期；
- 全仓库文档示例默认使用官方模型。

P5 只是有期限的兼容阶段，不是本计划的最终完成状态。

### P6：删除遗留模型和兼容层

前置条件：

- P1-P5 已完成并稳定运行至少一个约定的兼容发布周期；
- 发布说明已经提前告知破坏性变更；
- 主代码、示例和文档已经不再使用遗留模型；
- SDK 路径已经成为唯一生产路径，且完整门禁持续通过。

删除范围：

1. 删除已由官方 SDK 替代的 `io.github.a2ap.core.model` 标准模型类。
2. 删除旧 JSON-RPC 标准 envelope/error 模型中已被 SDK 替代的部分。
3. 删除 legacy ↔ SDK 的双向兼容 mapper、deprecated facade 和 overload。
4. 删除 `legacy`、`shadow` 模型执行路径及 `model-engine` 配置开关。
5. 删除只服务于新旧差异比较的 fixture adapter 和测试工具。
6. 删除 BOM、README、API 文档中对旧模型的导出、示例和兼容说明。
7. 保留迁移指南作为历史升级文档，但明确旧 API 已不可用。

不得删除：

- Gateway 自有领域模型和 SPI；
- `a2a4j-core` 中仍承载非重复能力且经架构确认需要保留的组件；
- 官方 SDK 未覆盖的 Gateway 安全、路由、Task Route 和传输治理实现。

退出标准：

- `rg "io.github.a2ap.core.model"` 在生产源码中无命中；
- 仓库内不存在 legacy/SDK 模型转换 mapper 和运行时开关；
- Maven 公共 API 检查确认破坏性变更只发生在已公告的主版本；
- 全仓库测试、release gates、互操作测试和依赖扫描全部通过；
- 最终运行时只使用官方 SDK A2A 标准模型。

## 8. 兼容与发布策略

### 8.1 开关

需要并行实现时，使用显式配置，不依赖 classpath 猜测：

```yaml
a2a:
  gateway:
    model-engine: legacy # legacy | sdk | shadow
```

- `legacy`：只执行现有模型路径；
- `sdk`：执行 SDK 模型路径；
- `shadow`：legacy 返回结果，SDK 同步做兼容比较，不产生第二次网络请求和外部副作用。

Shadow 只允许对同一份已接收字节做本地解析、构造和规范化比较，不能重复发送消息、创建 Task 或注册回调。

### 8.2 比较规则

比较规范化 JSON，而不是原始字符串：

- 忽略对象属性顺序；
- 保留数组顺序；
- 明确区分缺失字段、`null` 和空集合；
- metadata 与 DataPart 做深度比较；
- base64 先验证合法性，再比较原始值或解码字节；
- timestamp 按协议格式比较；
- 任何被忽略的字段必须在测试中显式列出理由。

### 8.3 发布

建议每个阶段独立提交和发布候选版本：

```text
P0 dependency probe
P1 agent-card models
P2 message/part models
P3 task/artifact models
P4 params/error models
P5 deprecation and migration window
P6 remove legacy models and compatibility layer
```

本次执行决策：采用最终目标要求的直接破坏性切换，P5 有期限的弃用窗口明确跳过；JSON-RPC envelope 仅保留在协议边界实现中，不作为 Core 公共领域模型。

禁止将多个阶段压缩为一个无法局部回滚的大提交。

## 9. 测试矩阵

| 测试层 | 必须覆盖 |
| --- | --- |
| 模型单测 | 构造、必填字段、equals/hash、enum、metadata、未知字段 |
| Fixture contract | 当前 `src/test/resources/a2a/v1` 全部 JSON fixture |
| 差异测试 | legacy 与 SDK 的 parse/serialize 规范化结果 |
| Adapter contract | JSON-RPC 与 HTTP+JSON decode/encode/error |
| Gateway E2E | send、stream、get、list、cancel、subscribe、push config |
| 安全测试 | SSRF、凭据不透传、租户隔离、授权、响应大小 |
| 流式测试 | 事件分片、顺序、终态、取消、断连、超时、超大事件 |
| 互操作 | 官方 Java/Python A2A client/server 双向通信 |
| 回归 | 全仓库 `clean test` 和现有 release gates |

每一批至少执行：

```powershell
.\mvnw.cmd clean test
.\tools\g10-release-gates.ps1
```

若完整 release gates 耗时过长，可以在开发提交中先运行受影响模块测试，但合并前必须运行完整门禁。

## 10. 风险与控制

| 风险 | 影响 | 控制措施 |
| --- | --- | --- |
| SDK 模型 API 在后续版本变化 | 大面积源码修改 | BOM 精确锁版本；所有 SDK 转换集中在 mapper |
| 官方模型与 Jackson 配置不一致 | 序列化差异 | 使用 SDK 推荐 codec；fixture 差异测试；不默认复用全局 ObjectMapper |
| SDK 传递依赖与 Spring Boot 冲突 | 启动或运行错误 | P0 dependency tree；只引入最小 spec artifact |
| Gateway `AgentInterface` 与官方同名 | 错误 import 和语义污染 | 边界 mapper；保留 Gateway 类型；代码审查检查 |
| `null`、空集合和未知字段行为变化 | 线协议不兼容 | 规范化差异测试和负向 fixture |
| Task ID 使用官方模型后被直接返回 | 租户隔离或粘滞失效 | ID 重写保持在 Gateway；增加泄漏测试 |
| SSE 类型或终态解释改变 | 流提前结束或不结束 | 单独 P3；事件序列和取消 E2E |
| 官方错误直接穿透 | 信息泄漏、错误码变化 | SDK error → Gateway error → binding response 三段映射 |
| 一次性替换公开类型 | 下游二进制不兼容 | 本次执行明确接受破坏性切换，不提供 deprecated facade/overload；下游需按官方 SDK 类型迁移 |

## 11. 完成定义

领域模型迁移完成必须同时满足：

1. A2A 标准模型的生产路径以官方 SDK 类型为事实来源。
2. Gateway 治理模型没有被 SDK 类型替代或污染。
3. JSON-RPC 与 HTTP+JSON 的外部行为和迁移前兼容。
4. 所有 Task ID、contextId、tenant 和 principal 边界保持不变。
5. Reactor Netty 的 SSRF、超时、大小限制和取消行为保持不变。
6. 全仓库测试、release gates 和互操作测试通过。
7. 已完成兼容发布周期，并删除旧标准模型、兼容 mapper、deprecated facade 和 overload。
8. 已删除 legacy/shadow 运行路径及其配置开关，SDK 是唯一生产模型路径。
9. 仓库生产源码不再引用 `io.github.a2ap.core.model` 中已被 SDK 替代的类型。

## 12. 建议的首个实施 PR

首个 PR 只做 P0 和 P1，不迁移 Message、Task 或错误：

1. 导入并锁定 SDK BOM 和最小模型依赖。
2. 添加 SDK 模型 smoke/fixture tests。
3. 新增官方 Agent Card 到 Gateway 注册模型的 mapper。
4. 为 Agent Card 添加 `legacy/sdk/shadow` 本地解析开关。
5. 保留现有网络 Fetcher、SSRF 策略、Gateway `AgentInterface` 和路由实现。
6. 更新依赖扫描结果和本迁移计划的执行状态。

该 PR 的价值是用最短链路验证 SDK、依赖和序列化策略；即使失败，也可以不触及消息转发和 Task 数据面直接回滚。

## 13. 执行记录

| 阶段 | 状态 | PR/Commit | 验证证据 | 备注 |
| --- | --- | --- | --- | --- |
| P0 依赖和 API 探针 | Completed | 工作树变更 | `OfficialSdkSpecSmokeTest`、`OfficialSdkModelContractTest`；`mvnw -pl a2a4j-core test` | BOM 锁定 `1.1.0.Final`，使用官方 SDK codec 和 fixture 探针。 |
| P1 Agent Card | Completed | 工作树变更 | `AgentCardNormalizerTest`、Starter/Sample tests；全仓 `clean test` | 官方 `AgentCard`/`AgentInterface`/`AgentSkill`/`AgentCapabilities` 已接入，Gateway 自有接口模型保留在边界内。 |
| P2 Message/Part | Completed | 工作树变更 | 官方 Part round-trip contract；JSON-RPC/HTTP+JSON adapter tests；Gateway E2E | `Message`、`TextPart`、`FilePart`、`DataPart` 均由官方 codec 解析/编码，标准 `messageId` 必填校验生效。 |
| P3 Task/Artifact | Completed | 工作树变更 | Task/event contract；`GatewayHttpJsonDataPlaneE2eTest` 15 项；全仓 `clean test` | Task、状态、Artifact、SSE 事件、Task ID 重写和取消路径均已迁移。 |
| P4 Params/Error | Completed | 工作树变更 | 参数/Push Notification contract；Gateway adapter tests；全仓 `clean test` | 官方 params/push 模型用于协议边界验证，错误仍经 Gateway 内部错误 envelope 输出。 |
| P5 弃用和迁移窗口 | Skipped | 工作树变更 | 本文档、Starter、Samples、测试和源码引用审计 | 本次明确采用破坏性切换，未提供 deprecated facade/overload；该阶段不是本次执行的完成项。 |
| P6 删除遗留模型和兼容层 | Completed | 工作树变更 | `rg "io.github.a2ap.core.model"`、旧 JSON-RPC DTO/Error 引用审计；无 `model-engine` 配置；release gates | `a2a4j-core` 旧标准模型、旧 JSON-RPC envelope 和自定义错误已删除；`SdkA2aProtocolCodec`/`SdkModelCodec` 是长期协议边界，不是兼容层。 |

执行记录（2026-08-02）：P0-P4 与 P6 在同一工作树完成；P5 按执行决策明确跳过；未创建或修改 commit。最终门禁命令为 `mvnw clean test`、`mvnw package -DskipTests` 和 `tools/g10-release-gates.ps1`，其逐项结果以门禁报告为准。

复核修正（2026-08-02）：P4 在本次补丁中完成官方 SDK 错误层级到 Core/协议 envelope 的映射；P5 明确跳过有期限的弃用窗口，采用计划最终目标要求的破坏性切换；P6 在删除旧 JSON-RPC DTO 和 `core.exception.A2AError` 后完成。`Dispatcher` 只暴露 map-based 协议 envelope，JSON-RPC DTO 不再属于 `a2a4j-core` 公共模型；Gateway 的 `SdkA2aProtocolCodec` 负责官方 SDK 模型与 Gateway map 的长期协议校验/编解码。
