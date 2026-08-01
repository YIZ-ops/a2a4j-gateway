# A2A4J 协议基线

当前主线以 A2A `1.0.0` 官方 Proto 为规范基线：

- [`a2a.proto`](./a2a.proto) 是签入仓库的规范源文件；
- [`a2a-1.0.0.lock`](./a2a-1.0.0.lock) 记录官方发布地址和 SHA-256；
- [`specification.md`](./specification.md) 是当前 canonical 的 A2A `1.0.0` Markdown 快照，包含字段级描述和示例；

Core 的 1.0 契约测试会校验 Agent Card、JSON-RPC 操作样本和签入 Proto 的摘要，
避免手工模型与官方定义漂移。Gateway MVP 当前只接受 `1.0`，不会静默降级到旧版本。
