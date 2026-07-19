# 模块设计说明

## 1. 模块总览

AgentFlow-Java 由 6 个 Maven 模块组成，每个模块职责单一，通过接口解耦。

| 模块 | 包名 | 职责 | 文件数 |
|------|------|------|--------|
| agent-core | `com.agentflow.core` | 纯接口定义 | 28 |
| agent-llm | `com.agentflow.llm` | LLM 通信 | 3 |
| agent-tool | `com.agentflow.tool` | 工具框架 | 3 |
| agent-rag | `com.agentflow.rag` | RAG 检索 | 2 |
| agent-web | `com.agentflow.web` | 运行时组装 | 32 |
| agent-demo | `com.agentflow.demo` | 演示应用 | 16 |

## 2. agent-core 模块

### 2.1 模块定位

核心抽象层，定义所有领域接口和值对象，**零外部依赖**。

### 2.2 接口清单

#### 2.2.1 Agent 运行时

```java
// 文件：agent-core/.../AgentRuntime.java
public interface AgentRuntime {
    AgentResult run(AgentRequest request, AgentEventSink eventSink);
}
```

**职责**：Agent 执行的入口接口，接收任务请求，返回执行结果。

#### 2.2.2 事件发布

```java
// 文件：agent-core/.../AgentEventSink.java
public interface AgentEventSink {
    AgentEventSink NOOP = event -> {};
    void publish(AgentEvent event);
}
```

**职责**：发布 Agent 执行过程中的事件，支持 NOOP 空实现。

#### 2.2.3 聊天模型客户端

```java
// 文件：agent-core/.../chat/ChatModelClient.java
public interface ChatModelClient {
    ChatCompletionResponse complete(ChatCompletionRequest request);
    ChatCompletionResponse stream(ChatCompletionRequest request, Consumer<String> deltaConsumer);
}
```

**职责**：与 LLM 进行对话，支持同步和流式两种模式。

#### 2.2.4 Agent 模型客户端

```java
// 文件：agent-core/.../model/AgentModelClient.java
public interface AgentModelClient {
    String generate(ModelPrompt prompt);
}
```

**职责**：Agent 专用的模型调用接口，简化版。

#### 2.2.5 Embedding 客户端

```java
// 文件：agent-core/.../model/EmbeddingClient.java
public interface EmbeddingClient {
    List<Double> embed(String text);
}
```

**职责**：将文本转换为向量表示。

#### 2.2.6 任务规划器

```java
// 文件：agent-core/.../planner/TaskPlanner.java
public interface TaskPlanner {
    Plan plan(String userInput, Set<String> enabledTools);
}
```

**职责**：根据用户输入和可用工具，生成执行计划。

#### 2.2.7 RAG 检索器

```java
// 文件：agent-core/.../rag/RagRetriever.java
public interface RagRetriever {
    List<RagDocument> retrieve(String query, int limit);
}
```

**职责**：从知识库中检索与查询相关的文档。

#### 2.2.8 短期记忆

```java
// 文件：agent-core/.../memory/ShortTermMemory.java
public interface ShortTermMemory {
    void appendUserMessage(String sessionId, String message);
    void appendAssistantMessage(String sessionId, String message);
    List<String> recentMessages(String sessionId, int limit);
}
```

**职责**：管理会话的短期对话历史。

#### 2.2.9 步骤记录器

```java
// 文件：agent-core/.../step/StepRecorder.java
public interface StepRecorder {
    void record(AgentStepRecord step);
}
```

**职责**：记录 Agent 执行的每一步。

#### 2.2.10 工具接口

```java
// 文件：agent-core/.../tool/AgentTool.java
public interface AgentTool {
    String name();
    String description();
    RiskLevel riskLevel();
    ToolResult execute(String input, ToolContext context);
}
```

**职责**：定义工具的统一接口。

#### 2.2.11 工具注册表

```java
// 文件：agent-core/.../tool/ToolRegistry.java
public interface ToolRegistry {
    void register(AgentTool tool);
    Optional<AgentTool> findEnabled(String name);
    Set<String> enabledToolNames();
}
```

**职责**：管理工具的注册和查找。

#### 2.2.12 工具提供者

```java
// 文件：agent-core/.../tool/ToolProvider.java
public interface ToolProvider {
    Collection<AgentTool> tools();
}
```

**职责**：提供工具集合的抽象。

### 2.3 值对象（Records）

| Record | 字段 | 用途 |
|--------|------|------|
| `AgentRequest` | taskId, sessionId, userId, input | Agent 任务请求 |
| `AgentResult` | taskId, finalAnswer, steps | Agent 执行结果 |
| `AgentEvent` | taskId, type, name, content, occurredAt | Agent 事件 |
| `AgentStepRecord` | taskId, stepNo, stepType, toolName, input, output, status, latencyMs, ... | 步骤记录 |
| `ModelPrompt` | system, user | 模型提示词 |
| `Plan` | reasoning, toolNames | 执行计划 |
| `RagDocument` | id, title, content, score | RAG 文档 |
| `ToolContext` | taskId, sessionId, userId, knowledge | 工具上下文 |
| `ToolResult` | toolName, output | 工具结果 |
| `ChatMessage` | role, content | 聊天消息 |
| `ChatCompletionRequest` | messages, model, temperature, maxTokens | 聊天请求 |
| `ChatCompletionResponse` | provider, model, content, usage, mocked | 聊天响应 |
| `TokenUsage` | promptTokens, completionTokens, totalTokens | Token 用量 |

### 2.4 枚举定义

| 枚举 | 值 | 用途 |
|------|-----|------|
| `AgentStepType` | PLANNER, MEMORY, RAG, LLM, TOOL, HUMAN_APPROVAL, FINAL | 步骤类型 |
| `AgentStepStatus` | RUNNING, SUCCESS, FAILED, SKIPPED | 步骤状态 |
| `AgentTaskStatus` | PENDING, RUNNING, SUCCEEDED, FAILED, WAITING_FOR_HUMAN | 任务状态 |
| `RiskLevel` | LOW, MEDIUM, HIGH | 风险等级 |

## 3. agent-llm 模块

### 3.1 模块定位

LLM 通信适配器层，提供 OpenAI 兼容的模型客户端。

### 3.2 核心类

#### 3.2.1 AgentFlowProperties

```java
// 文件：agent-llm/.../AgentFlowProperties.java
@ConfigurationProperties(prefix = "agentflow")
public record AgentFlowProperties(
    Model model,
    Tools tools,
    Security security,
    Mcp mcp
) {
    public record Model(
        String provider, String baseUrl, String apiKey,
        String chatModel, String embeddingModel,
        int embeddingDimensions, Duration timeout
    ) {}
    public record Tools(int maxSteps) {}
    public record Security(
        Duration refreshTokenTtl,
        Jwt jwt
    ) {
        public record Jwt(String secret, Duration accessTokenTtl) {}
    }
    public record Mcp(boolean enabled) {}
}
```

**职责**：绑定 `agentflow.*` 配置项。

#### 3.2.2 OpenAiCompatibleModelClient

```java
// 文件：agent-llm/.../OpenAiCompatibleModelClient.java
public class OpenAiCompatibleModelClient
    implements AgentModelClient, EmbeddingClient, ChatModelClient {

    // 同步对话
    public ChatCompletionResponse complete(ChatCompletionRequest request) { ... }

    // 流式对话
    public ChatCompletionResponse stream(ChatCompletionRequest request,
                                         Consumer<String> deltaConsumer) { ... }

    // 文本向量化
    public List<Double> embed(String text) { ... }

    // Agent 专用生成
    public String generate(ModelPrompt prompt) { ... }
}
```

**职责**：通过 Spring RestClient 调用 OpenAI 兼容 API。

**关键实现**：
- 使用 `RestClient` 发送 HTTP 请求
- 手动解析 SSE 流（`data:` 行，`[DONE]` 结束标记）
- 无 API Key 时返回本地兜底回答
- 支持 DeepSeek 和 OpenAI 两种 provider

#### 3.2.3 AgentLlmAutoConfiguration

```java
// 文件：agent-llm/.../AgentLlmAutoConfiguration.java
@AutoConfiguration
public class AgentLlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAiCompatibleModelClient openAiCompatibleModelClient(
            AgentFlowProperties properties, RestClient.Builder builder) {
        return new OpenAiCompatibleModelClient(properties, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AgentModelClient.class)
    public AgentModelClient agentModelClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient embeddingClient(OpenAiCompatibleModelClient client) {
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelClient.class)
    public ChatModelClient chatModelClient(OpenAiCompatibleModelClient client) {
        return client;
    }
}
```

**职责**：自动注册 LLM 相关 Bean。

## 4. agent-tool 模块

### 4.1 模块定位

工具框架适配器层，提供工具注册和管理。

### 4.2 核心类

#### 4.2.1 InMemoryToolRegistry

```java
// 文件：agent-tool/.../InMemoryToolRegistry.java
public class InMemoryToolRegistry implements ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public void register(AgentTool tool) { ... }
    public Optional<AgentTool> findEnabled(String name) { ... }
    public Set<String> enabledToolNames() { ... }
}
```

**职责**：基于 LinkedHashMap 的内存工具注册表。

#### 4.2.2 McpToolProvider

```java
// 文件：agent-tool/.../McpToolProvider.java
public class McpToolProvider implements ToolProvider {
    public Collection<AgentTool> tools() {
        return List.of();  // 占位实现
    }
}
```

**职责**：MCP 协议工具提供者（当前为空实现）。

#### 4.2.3 AgentToolAutoConfiguration

```java
// 文件：agent-tool/.../AgentToolAutoConfiguration.java
@AutoConfiguration
public class AgentToolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ObjectProvider<AgentTool> tools) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        tools.orderedStream().forEach(registry::register);
        return registry;
    }
}
```

**职责**：自动注册工具注册表，注入所有 AgentTool Bean。

## 5. agent-rag 模块

### 5.1 模块定位

RAG 检索适配器层，提供默认的空实现。

### 5.2 核心类

#### 5.2.1 NoopRagRetriever

```java
// 文件：agent-rag/.../NoopRagRetriever.java
public class NoopRagRetriever implements RagRetriever {
    public List<RagDocument> retrieve(String query, int limit) {
        return List.of();
    }
}
```

**职责**：空实现，作为默认兜底。

#### 5.2.2 AgentRagAutoConfiguration

```java
// 文件：agent-rag/.../AgentRagAutoConfiguration.java
@AutoConfiguration
public class AgentRagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RagRetriever ragRetriever() {
        return new NoopRagRetriever();
    }
}
```

**职责**：自动注册默认的 RAG 检索器。

## 6. agent-web 模块

### 6.1 模块定位

运行时组装层，包含所有 Spring Web 相关的实现。

### 6.2 核心组件

#### 6.2.1 DefaultAgentRuntime

```java
// 文件：agent-web/.../DefaultAgentRuntime.java
public class DefaultAgentRuntime implements AgentRuntime {

    public AgentResult run(AgentRequest request, AgentEventSink eventSink) {
        // Step 1: 规划
        Plan plan = taskPlanner.plan(request.input(), toolRegistry.enabledToolNames());

        // Step 2: RAG 检索
        List<RagDocument> documents = ragRetriever.retrieve(request.input(), 5);

        // Step 3: 工具执行
        for (String toolName : plan.toolNames()) {
            AgentTool tool = toolRegistry.findEnabled(toolName);
            ToolResult result = tool.execute(request.input(), toolContext);
        }

        // Step 4: LLM 合成
        String answer = modelClient.generate(buildFinalPrompt(...));

        // Step 5: 记录步骤
        stepRecorder.record(step);

        return new AgentResult(taskId, answer, steps);
    }
}
```

**职责**：Agent 执行的核心循环。

#### 6.2.2 认证组件

| 类 | 职责 |
|-----|------|
| `AuthService` | 登录、刷新、登出逻辑 |
| `JwtService` | JWT 签发 |
| `JwtBlacklistFilter` | JWT 黑名单检查 |
| `AuthController` | 认证 REST 端点 |
| `SysUser` | 用户 JPA 实体 |
| `AuthRefreshToken` | 刷新令牌 JPA 实体 |

#### 6.2.3 Agent 组件

| 类 | 职责 |
|-----|------|
| `AgentTaskService` | 任务创建和执行 |
| `AgentController` | Agent REST 端点 |
| `TaskEventPublisher` | 事件发布（Redis + SSE） |
| `JpaStepRecorder` | 步骤持久化 |
| `RedisShortTermMemory` | Redis 短期记忆 |

#### 6.2.4 Chat 组件

| 类 | 职责 |
|-----|------|
| `ChatService` | 聊天逻辑 |
| `ChatController` | 聊天 REST 端点 |

#### 6.2.5 配置组件

| 类 | 职责 |
|-----|------|
| `SecurityConfig` | Spring Security 配置 |
| `RateLimitFilter` | Redis 限流过滤器 |

#### 6.2.6 自动装配

```java
// 文件：agent-web/.../autoconfigure/AgentWebAutoConfiguration.java
@AutoConfiguration
public class AgentWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TaskPlanner taskPlanner() {
        return new SimpleTaskPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShortTermMemory shortTermMemory() {
        return new InMemoryShortTermMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    public StepRecorder stepRecorder() {
        return new NoopStepRecorder();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(...) {
        return new DefaultAgentRuntime(...);
    }
}
```

## 7. agent-demo 模块

### 7.1 模块定位

演示应用层，包含 Spring Boot 入口和业务实现。

### 7.2 核心组件

#### 7.2.1 应用入口

```java
// 文件：agent-demo/.../AgentFlowDemoApplication.java
@SpringBootApplication
@Import(SecurityConfig.class)
public class AgentFlowDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentFlowDemoApplication.class, args);
    }
}
```

#### 7.2.2 演示工具

| 工具类 | name() | 功能 |
|--------|--------|------|
| `InterfaceDraftTool` | `interface-draft` | 生成 REST API 接口草稿 |
| `SqlDraftTool` | `sql-draft` | 生成 MySQL DDL 草稿 |
| `CodeDraftTool` | `code-draft` | 生成 Controller/Service 代码草稿 |

**代码依据**：`agent-demo/.../tool/` 目录

#### 7.2.3 知识库组件

| 类 | 职责 |
|-----|------|
| `KnowledgeService` | 知识加载、分块、向量化 |
| `KnowledgeRagRetriever` | 向量检索 + 关键词降级 |
| `QdrantClient` | Qdrant REST 客户端 |
| `KnowledgeController` | 知识管理 REST 端点 |
| `KnowledgeDocumentEntity` | 文档 JPA 实体 |
| `KnowledgeChunkEntity` | 分块 JPA 实体 |

#### 7.2.4 初始化配置

| 类 | 职责 |
|-----|------|
| `InitialDataConfig` | 初始化管理员账户和工具目录 |
| `KnowledgeBootstrapConfig` | 启动时加载内置知识 |

**代码依据**：
- `InitialDataConfig.java`：`CommandLineRunner` 创建 admin 用户
- `KnowledgeBootstrapConfig.java`：`CommandLineRunner` 调用 `reloadBuiltInKnowledge()`

## 8. 待确认项

| # | 项目 | 状态 | 说明 |
|---|------|------|------|
| 1 | MCP 协议集成 | 待确认 | `McpToolProvider` 为空实现，何时实现？ |
| 2 | 工具热加载 | 待确认 | 是否支持运行时动态注册工具？ |
| 3 | 多租户支持 | 待确认 | 当前 userId 是否支持租户隔离？ |
| 4 | 插件机制 | 待确认 | 是否计划支持插件式工具扩展？ |
