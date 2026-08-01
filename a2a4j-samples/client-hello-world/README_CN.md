# A2A4J Client Hello World 示例（客户端）

这是一个 A2A（Agent2Agent）协议客户端实现示例，演示如何使用 A2A4J 框架与 A2A 服务器进行交互。

## 功能特性

- ✅ A2A 协议客户端实现
- ✅ 支持 JSON-RPC 2.0 同步与流式通信
- ✅ 演示如何向 A2A 服务器发送消息
- ✅ 实时状态更新与进度跟踪
- ✅ 详细日志输出

## 快速开始

> **在运行客户端前，请确保已启动 `server-hello-world` 模块，并监听在 http://localhost:8089。**

### 前置条件

- Java 17 及以上
- Maven 3.6 及以上

### 构建项目

```bash
# 克隆仓库（如尚未克隆）
git clone https://github.com/a2ap/a2a4j.git
cd a2a4j

# 构建整个项目
mvn clean install

# 进入示例目录
cd a2a4j-samples/client-hello-world
```

### 运行客户端

```bash
# 使用Maven运行
mvn spring-boot:run

# 或运行已编译的JAR包
mvn clean package
java -jar target/client-hello-world-*.jar
```

### 示例用法

启动客户端只会启动它自己的 HTTP 应用，不会自动发送消息。请调用下面的客户端入口；首次调用时客户端会
发现服务端 Card 并发送请求。默认服务端地址为 `http://localhost:8089`，可在配置文件中修改。

当前客户端使用 A2A 1.0 协议：先请求 `/.well-known/agent-card.json`，再自动发送
`A2A-Version: 1.0` 和 JSON-RPC 方法
`SendMessage` 或 `SendStreamingMessage`。客户端自身提供以下测试入口：

```text
GET /a2a/client/send?message=hello
GET /a2a/client/stream/send?message=hello
```

第二个入口返回 `text/event-stream`，需要按 SSE 事件读取。旧版 `message/send` 和 `message/stream` 方法名不再适用于当前 1.0 客户端/服务端组合。

---

更多细节请参考源码及注释。
