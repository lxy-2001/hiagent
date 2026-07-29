  ## 推荐学习顺序

  ### 第 1 步：认识项目边界

  先看：

  1. README.md
  2. docs/module-design.md
  3. pom.xml
  4. agent-demo/src/main/java/com/agentflow/demo/AgentFlowDemoApplication.java

  目标：能说清楚 agent-core、agent-web、agent-tool、agent-llm、agent-rag、agent-data、agent-demo 分别负责什么。

  暂时不要深入数据库、安全和前端。

  ### 第 2 步：学习核心接口

  依次看：

  1. agent-core/src/main/java/com/agentflow/core/AgentRequest.java
  2. agent-core/src/main/java/com/agentflow/core/AgentResult.java
  3. agent-core/src/main/java/com/agentflow/core/AgentRuntime.java
  4. TaskPlanner
  5. RagRetriever
  6. ToolRegistry
  7. AgentModelClient
  8. ShortTermMemory
  9. StepRecorder

  目标：用一句话解释每个接口的责任。

  这一阶段要理解的核心是：agent-core 只定义能力，不提供具体技术实现。

  ### 第 3 步：吃透最重要的执行类

  重点学习：

  agent-web/src/main/java/com/agentflow/web/DefaultAgentRuntime.java

  只关注四部分：

  - 构造函数：Runtime 依赖了哪些组件
  - run()：完整执行顺序
  - buildFinalPrompt()：RAG 和工具结果如何进入模型上下文
  - recordStep()：执行过程如何被记录

  读完后，自己写出类似伪代码：

  读取历史记忆
  → 生成计划
  → 检索知识
  → 选择并执行工具
  → 构建最终 Prompt
  → 调用模型
  → 保存记忆和步骤
  → 返回结果

  这是整个项目最关键的一步。掌握它之后，你就理解了项目的 Agent 主体。

  可以直接使用：

  $understand-anything:understand-explain /root/hiagent/agent-web/src/main/java/com/agentflow/web/DefaultAgentRuntime.java --language zh

  ### 第 4 步：从 HTTP 接口追踪到 Runtime

  依次看：

  1. AgentController
  2. agent-web/src/main/java/com/agentflow/web/agent/AgentTaskService.java
  3. agent-web/src/main/java/com/agentflow/web/agent/TaskEventPublisher.java
  4. JpaStepRecorder

  重点追踪：

  Controller
  → AgentTaskService.create()
  → AgentTaskService.execute()
  → AgentRuntime.run()
  → TaskEventPublisher
  → SSE 返回前端

  目标：能够解释一个 Agent 任务如何创建、异步执行、查询步骤和订阅事件。

  学完这一步，你已经掌握项目约 60% 的核心结构。

  ### 第 5 步：学习具体适配器

  按照接口和实现一一对应地看：

   核心接口            实现
  ━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   TaskPlanner         SimpleTaskPlanner
  ──────────────────  ─────────────────────────────
   ToolRegistry        InMemoryToolRegistry
  ──────────────────  ─────────────────────────────
   AgentTool           CodeDraftTool 等
  ──────────────────  ─────────────────────────────
   AgentModelClient    OpenAiCompatibleModelClient
  ──────────────────  ─────────────────────────────
   RagRetriever        KnowledgeRagRetriever
  ──────────────────  ─────────────────────────────
   StepRecorder        JpaStepRecorder

  目标：回答“如果新增一个工具、模型客户端或 RAG 实现，需要改哪些文件”。

  建议优先拿 CodeDraftTool 做例子，因为它比模型和 RAG 简单。

  ### 第 6 步：学习 RAG 和数据存储

  依次看：

  1. agent-demo/src/main/java/com/agentflow/demo/knowledge/KnowledgeService.java
  2. agent-demo/src/main/java/com/agentflow/demo/knowledge/KnowledgeRagRetriever.java
  3. QdrantClient
  4. agent-demo/src/main/resources/db/migration/V1__init_schema.sql
  5. Redis Memory 实现
  6. JPA Entity 和 Repository

  画出两条数据流：

  文档导入 → 切片 → 向量化 → Qdrant

  用户问题 → 向量化 → 检索 → RagDocument → Prompt

  目标：分清楚 MySQL、Redis、Qdrant 各自保存什么。

  ### 第 7 步：学习 Spring 组装与配置

  重点看：

  1. agent-web/src/main/java/com/agentflow/web/autoconfigure/AgentWebAutoConfiguration.java
  2. AgentFlowProperties
  3. agent-demo/src/main/resources/application.yml
  4. docker-compose.yml

  目标：理解 Spring 是如何把 Runtime、Planner、ToolRegistry、RAG、ModelClient 组装起来的，以及没有配置某项能力时使用什么默认实现。

  ### 第 8 步：最后学习外围能力

  最后再看：

  - SecurityConfig
  - AuthService
  - JWT 与 Refresh Token
  - ChatController
  - ChatService
  - 限流
  - 前端 index.html

  这些属于完整应用能力，不属于 Agent 的核心原理，所以放在最后。

  ### 第 9 步：用测试反向巩固

  重点阅读：

  - DefaultAgentRuntimeTest
  - SimpleTaskPlannerTest
  - InMemoryToolRegistryTest
  - OpenAiCompatibleModelClientTest
  - ChatServiceTest
  - ChatControllerSecurityTest

  看测试时重点观察：

  - 输入是什么
  - Mock 了哪些组件
  - 预期输出是什么
  - 测试实际上定义了什么行为契约

  ## 每学习一个文件，都执行这个循环

  1. 自己快速读一遍
  2. 用 understand-explain 讲解
  3. 回到代码验证讲解
  4. 画出它的上游和下游
  5. 用自己的话总结责任
  6. 阅读对应测试

  不要只是连续阅读文档，否则很容易产生“好像看懂了，但说不出来”的感觉。

  ## 你现在应该做什么

  今天只完成前三步，不要继续往后扩展：

  1. 看懂模块划分。
  2. 认识核心接口。
  3. 吃透 DefaultAgentRuntime.run()。

  完成后，你应该能独立画出：

  AgentRequest
    → Planner
    → Memory
    → RAG
    → Tools
    → Model
    → AgentResult

  下一步最合适的操作，就是用 understand-explain 深入讲解 DefaultAgentRuntime.java。等主链路真正理解后，再学习 Controller、数据库和安全。

  后续如果生成更详细的 PROJECT_DESIGN.md，也应该严格按照这九个阶段组织，并加入模块图、组件图、时序图和数据流图；那样它才真正能充当你的项目教材。