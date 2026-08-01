# A2A4J - Agent2Agent Protocol Java Implementation

A2A4J 是 Agent2Agent (A2A) 协议的 Java 实现，提供了完整的服务器端和客户端支持。

📖 **[English Documentation](README.md)**

## 功能特性

- ✅ **完整的 A2A 协议支持** - Agent2Agent 规范的完整实现
- ✅ **JSON-RPC 2.0 通信** - 基于标准的请求/响应消息传递
- ✅ **Server-Sent Events 流式处理** - 实时任务更新和流式响应
- ✅ **任务生命周期管理** - 全面的任务状态管理和监控
- ✅ **Spring Boot 集成** - 与 Spring Boot 应用程序轻松集成
- ✅ **响应式编程支持** - 基于 Reactor 构建，可扩展的非阻塞操作
- ✅ **多种内容类型** - 支持文本、文件和结构化数据交换
- ⚪️ **推送通知配置** - 通过 webhooks 进行异步任务更新
- ⚪️ **Agent Card 发现机制** - 动态能力发现机制
- ⚪️ **企业级安全** - 身份验证和授权支持

## 快速开始

### 环境要求

- Java 17+
- Spring Boot 3.2+
- Maven 3.6+

### 运行服务器

```bash
mvn spring-boot:run
```

服务器将在 `http://localhost:8089` 启动。

### Agent Card 访问

```bash
curl http://localhost:8089/.well-known/agent-card.json
```

### 发送消息

```bash
curl -X POST http://localhost:8089/a2a/server \
  -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "user",
        "parts": [
          {
            "kind": "text",
            "text": "Hello, A2A!"
          }
        ],
        "messageId": "9229e770-767c-417b-a0b0-f0741243c589"
      }
    },
    "id": "1"
  }'
```

## 支持的JSON-RPC方法

### 核心方法

- `SendMessage` - 发送消息并创建任务
- `SendStreamingMessage` - 发送消息并订阅流式更新

### 任务管理

- `GetTask` - 获取任务状态
- `ListTasks` - 获取当前调用方可见的任务
- `CancelTask` - 取消任务
- `SubscribeToTask` - 重新订阅任务更新

### 推送通知

- `CreateTaskPushNotificationConfig` - 设置推送通知配置
- `GetTaskPushNotificationConfig` - 获取推送通知配置
- `DeleteTaskPushNotificationConfig` - 删除推送通知配置

## 客户端使用示例

```java
A2AClient client = new DefaultA2AClient(new HttpCardResolver("http://localhost:8089"));

// 发送消息
TextPart textPart = new TextPart();
textPart.setText("Hello from Java client!");

Message message = Message.builder()
    .role("user")
    .parts(List.of(textPart))
    .build();

MessageSendParams params = MessageSendParams.builder()
    .message(message)
    .build();

SendMessageResponse result = client.sendMessage(params);
```

## 架构设计

```
a2a4j-core/
├── src/main/java/io/github/a2ap/core/
│   ├── model/          # 数据模型
│   ├── server/         # 服务器端实现
│   ├── client/         # 客户端实现
│   ├── jsonrpc/        # JSON-RPC支持
│   └── exception/      # 异常处理
└── src/test/java/      # 测试代码
```

## 测试

```bash
mvn test
```

## 详细文档

要了解更多详细信息，包括配置、安全、性能优化等，请参考：

- 📖 **[完整英文文档](README.md)** - 包含详细的使用指南、架构说明和最佳实践

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个实现。

## 许可证

Apache-2.0 license  
