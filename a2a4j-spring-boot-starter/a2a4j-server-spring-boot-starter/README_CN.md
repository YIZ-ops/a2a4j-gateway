# A2A4J Server Spring Boot Starter

这个 Spring Boot Starter 为 A2A (Agent2Agent) 协议服务器提供自动配置，使得在 Spring Boot 应用程序中集成 A2A 功能变得简单。

📖 **[完整英文文档](README.md)**

## 特性

- ✅ **零配置**: 开箱即用，自动配置所有 A2A 服务器组件
- ✅ **灵活覆盖**: 可以用自定义实现覆盖任何组件
- ✅ **属性配置**: 通过应用程序属性配置智能体元数据和功能
- ✅ **生产就绪**: 提供合理的默认值和自定义选项
- ✅ **Spring Boot 集成**: 与 Spring Boot 配置系统无缝集成

## 快速开始

### 1. 添加依赖

在你的 Spring Boot 项目中添加启动器：

```xml
<dependency>
    <groupId>io.github.a2ap</groupId>
    <artifactId>a2a4j-server-spring-boot-starter</artifactId>
    <version>${a2a4j.version}</version>
</dependency>
```

### 2. 配置属性

在 `application.yml` 中配置你的智能体：

```yaml
a2a:
  server:
    name: "我的 A2A 智能体"
    description: "一个强大的 A2A 智能体"
    version: "1.0.0"
    url: "https://my-agent.example.com"
    capabilities:
      streaming: true
      push-notifications: false
      state-transition-history: true
```

### 3. 实现代理逻辑

创建自定义的 `AgentExecutor` 来定义你的智能体行为：

```java
@Component
public class MyAgentExecutor implements AgentExecutor {
    
    @Override
    public Mono<Void> execute(RequestContext context, EventQueue eventQueue) {
        return Mono.fromRunnable(() -> {
            // 从上下文中提取消息
            Message message = context.getMessage();
            
            // 处理消息并生成响应
            String responseText = "你好！你说: " + 
                extractTextFromMessage(message);
            
            // 发布响应工件
            TaskArtifactUpdateEvent artifactEvent = TaskArtifactUpdateEvent.builder()
                .taskId(context.getTaskId())
                .artifact(Artifact.builder()
                    .type("text")
                    .content(responseText)
                    .build())
                .build();
            eventQueue.enqueueEvent(artifactEvent);
            
            // 关闭事件队列
            eventQueue.close();
        });
    }
    
    @Override
    public Mono<Void> cancel(String taskId) {
        return Mono.empty();
    }
}
```

### 4. 运行应用程序

```java
@SpringBootApplication
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

你的 A2A 服务器将在配置的 URL 上可用，提供以下端点：

- `GET /.well-known/agent-card.json` - Agent Card 发现
- `POST /a2a/server` - 同步和流式请求的 JSON-RPC 端点

## 配置属性

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `a2a.server.enabled` | `boolean` | `true` | 是否启用 A2A 服务器 |
| `a2a.server.name` | `string` | | 智能体的名称 |
| `a2a.server.description` | `string` | | 智能体的描述 |
| `a2a.server.version` | `string` | | 智能体的版本 |
| `a2a.server.url` | `string` | | 智能体可访问的基础 URL |
| `a2a.server.capabilities.streaming` | `boolean` | `true` | 智能体是否支持流式响应 |
| `a2a.server.capabilities.push-notifications` | `boolean` | `false` | 智能体是否支持推送通知 |
| `a2a.server.capabilities.state-transition-history` | `boolean` | `true` | 智能体是否维护状态转换历史 |

## 自动配置的组件

Starter 自动配置以下 Bean（都可以被覆盖）：

### 核心组件

- **`QueueManager`**: 管理任务的事件队列（默认：`InMemoryQueueManager`）
- **`TaskStore`**: 存储任务数据和历史（默认：`InMemoryTaskStore`）
- **`TaskManager`**: 管理任务生命周期（默认：`InMemoryTaskManager`）
- **`AgentExecutor`**: 执行代理逻辑（默认：无操作实现）
- **`Dispatcher`**: 路由 JSON-RPC 请求（默认：`DefaultDispatcher`）
- **`A2AServer`**: 主服务器实现（默认：`DefaultA2AServer`）

### 支持组件

- **`ObjectMapper`**: 用于 JSON 序列化/反序列化
- **`AgentCard`**: 基于配置属性的智能体元数据

## 示例项目

查看 `example/` 目录中的完整示例：

- [EchoAgentApplication.java](example/EchoAgentApplication.java) - 简单的回声智能体实现
- [application.yml](example/application.yml) - 示例配置

## 详细文档

更多详细信息请参考：

- 📖 **[完整英文文档](README.md)** - 包含详细的配置、自定义、测试和生产部署指南
- 🔧 [自定义配置](README.md#customization)
- 🏭 [生产环境配置](README.md#advanced-usage)
- 🧪 [测试指南](README.md#testing)
- 📚 [示例代码](README.md#examples)

## 问题排查

常见问题：

1. **Agent Card 无法访问**: 确保 `url` 属性配置正确
2. **自定义执行器未生效**: 确保你的执行器使用 `@Component` 注解
3. **配置未应用**: 检查属性是否在 `a2a.server` 前缀下

## 许可证

Apache-2.0 license 
