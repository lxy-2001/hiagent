# AgentFlow 中文入门指南

本文根据 Understand Anything 生成的仓库知识图谱整理，目标是帮助第一次接触
AgentFlow 的开发者快速建立项目全局认识，并按照由主到次的顺序阅读代码。

> 当前知识图谱与 Git 提交
> `e2babfa9cce34f14061b2e26f38cc89ff97da4ae` 一致。后续代码发生较大变化时，
> 建议重新运行增量扫描并更新本文。

## 1. 项目概览

AgentFlow 是一个基于 Java 17、Spring Boot 和 Spring AI 的模块化 Agent Runtime
示例项目。

它目前的核心执行方式如下：

```text
HTTP 请求
  → AgentTaskService 创建任务
  → DefaultAgentRuntime 执行
  → 读取记忆
  → 任务规划
  → RAG 检索
  → 调用工具
  → 调用大模型生成答案
  → 保存执行步骤
  → SSE 推送事件
```

需要注意：它现在更接近“可扩展的固定 Agent 工作流”，还不是由模型自主决定下一步、
循环调用工具的 ReAct 类型 Agent。这也是后续完善项目时很有价值的升级方向。

主要技术：

- Java 17、Maven 多模块
- Spring Boot 3.5.14、Spring AI 1.1.6
- MySQL：任务、步骤、用户和知识文档
- Redis：短期记忆、事件和令牌状态
- Qdrant：向量检索
- JWT：身份认证
- SSE：任务执行事件推送

项目总览入口：[`README.md`](../README.md)

## 2. 架构分层

| 层次 | 作用 | 学习优先级 |
| --- | --- | --- |
| 核心领域与端口层 | 定义 Agent 的稳定接口和数据模型 | 最高 |
| 模型、工具和 RAG 适配层 | 对接模型和工具实现 | 高 |
| 数据持久化层 | MySQL、Redis 和 Qdrant | 中 |
| Web Runtime 与 API 层 | 编排 Agent 执行并提供接口 | 最高 |
| Demo 与 UI 层 | 示例工具、知识库接口和页面 | 中 |
| 构建与配置层 | 自动配置、依赖和参数 | 中 |
| 部署基础设施层 | Docker Compose | 中 |
| 测试层 | 验证设计和调用方式 | 高 |
| 文档层 | 架构、接口、部署说明 | 高 |

项目整体采用“端口与适配器”思路：

```text
agent-core：定义规则和接口
     ↑
agent-llm / agent-tool / agent-rag：提供具体能力
     ↑
agent-web：组合并执行这些能力
     ↑
agent-demo：形成可运行应用
```

## 3. 核心概念

### 3.1 核心接口与实现分离

`agent-core` 只定义 Agent 需要什么能力，例如：

- `ModelClient`
- `RagRetriever`
- `AgentTool`
- `ToolRegistry`
- `TaskPlanner`
- `ShortTermMemory`
- `StepRecorder`

核心层不关心这些能力具体通过 OpenAI、Qdrant、Redis 还是内存实现。因此后续替换
模型、工具或存储方案时，不需要大规模修改 Runtime。

### 3.2 Agent Runtime

[`AgentRuntime.java`](../agent-core/src/main/java/com/agentflow/core/AgentRuntime.java)
是执行入口协议。

[`DefaultAgentRuntime.java`](../agent-web/src/main/java/com/agentflow/web/DefaultAgentRuntime.java)
是最关键的实现，它把规划、RAG、工具、记忆和模型调用串联起来。这个文件是理解整个
项目的主干。

### 3.3 请求与结果模型

先认识：

- [`AgentRequest.java`](../agent-core/src/main/java/com/agentflow/core/AgentRequest.java)
- [`AgentResult.java`](../agent-core/src/main/java/com/agentflow/core/AgentResult.java)

重点观察：

- 一次 Agent 执行需要哪些输入
- session、任务和用户如何关联
- 最终答案和执行状态如何表示

### 3.4 工具系统

工具通过 `AgentTool` 抽象，由 `ToolRegistry` 管理。

现有实现：

- [`InMemoryToolRegistry.java`](../agent-tool/src/main/java/com/agentflow/tool/InMemoryToolRegistry.java)
- [`CodeDraftTool.java`](../agent-demo/src/main/java/com/agentflow/demo/tool/CodeDraftTool.java)

目前 Runtime 选择并执行工具的逻辑相对固定，后续可以升级成模型动态选择工具、校验
参数、循环执行和错误恢复。

### 3.5 RAG 知识库

主要调用链：

```text
KnowledgeService
  → 文档切片
  → Embedding
  → Qdrant 保存向量
  → KnowledgeRagRetriever 检索
  → 检索结果加入模型上下文
```

关键文件：

- [`KnowledgeService.java`](../agent-demo/src/main/java/com/agentflow/demo/knowledge/KnowledgeService.java)
- [`KnowledgeRagRetriever.java`](../agent-demo/src/main/java/com/agentflow/demo/knowledge/KnowledgeRagRetriever.java)

### 3.6 执行记录与事件

系统会记录 Agent 的中间步骤，并通过 SSE 推送进度。

相关文件：

- [`AgentTaskService.java`](../agent-web/src/main/java/com/agentflow/web/agent/AgentTaskService.java)
- [`TaskEventPublisher.java`](../agent-web/src/main/java/com/agentflow/web/agent/TaskEventPublisher.java)

这部分很适合展示 Agent 的可观测性和执行审计能力。

### 3.7 自动配置

[`AgentWebAutoConfiguration.java`](../agent-web/src/main/java/com/agentflow/web/autoconfigure/AgentWebAutoConfiguration.java)
负责把各模块的接口实现组装成可运行系统。

学习这个文件可以理解：

- Spring 如何发现 Bean
- 默认实现如何注册
- 用户如何替换某个适配器
- 模块之间如何保持低耦合

## 4. 推荐学习路线

不要一次读完整个仓库。推荐按照下面的顺序学习。

### 第一阶段：理解主链路

依次阅读：

1. [`README.md`](../README.md)
2. [`module-design.md`](module-design.md)
3. [`AgentRuntime.java`](../agent-core/src/main/java/com/agentflow/core/AgentRuntime.java)
4. [`AgentRequest.java`](../agent-core/src/main/java/com/agentflow/core/AgentRequest.java)
5. [`DefaultAgentRuntime.java`](../agent-web/src/main/java/com/agentflow/web/DefaultAgentRuntime.java)
6. [`DefaultAgentRuntimeTest.java`](../agent-web/src/test/java/com/agentflow/web/autoconfigure/DefaultAgentRuntimeTest.java)

第一遍不用弄清楚每一行，只需要回答四个问题：

- Agent 请求从哪里进入？
- Runtime 依赖了哪些接口？
- 工具、RAG 和模型的执行顺序是什么？
- 执行结果和步骤记录到哪里？

完成这些内容，就已经掌握了项目最重要的主干。

### 第二阶段：追踪一次完整请求

按照下面的方向阅读：

```text
AgentController
  → AgentTaskService
  → DefaultAgentRuntime
  → SimpleTaskPlanner
  → KnowledgeRagRetriever
  → ToolRegistry
  → ModelClient
  → StepRecorder / TaskEventPublisher
```

重点不是背代码，而是在纸上画出一次请求经过的对象和方法。

### 第三阶段：理解扩展机制

阅读：

- [`SimpleTaskPlanner.java`](../agent-web/src/main/java/com/agentflow/web/planner/SimpleTaskPlanner.java)
- [`InMemoryToolRegistry.java`](../agent-tool/src/main/java/com/agentflow/tool/InMemoryToolRegistry.java)
- [`CodeDraftTool.java`](../agent-demo/src/main/java/com/agentflow/demo/tool/CodeDraftTool.java)
- [`OpenAiCompatibleModelClient.java`](../agent-llm/src/main/java/com/agentflow/llm/OpenAiCompatibleModelClient.java)

学习目标是弄明白：如果增加一个新工具、新模型或新规划器，需要修改哪些地方。

### 第四阶段：学习 RAG 和状态管理

接着阅读知识库、Redis 记忆、数据库实体和任务步骤。

数据库结构入口：

[`V1__init_schema.sql`](../agent-demo/src/main/resources/db/migration/V1__init_schema.sql)

### 第五阶段：学习安全、配置和页面

这些内容很重要，但不适合作为第一入口：

- [`SecurityConfig.java`](../agent-web/src/main/java/com/agentflow/web/config/SecurityConfig.java)
- [`application.yml`](../agent-demo/src/main/resources/application.yml)
- [`docker-compose.yml`](../docker-compose.yml)
- `agent-demo/src/main/resources/static/index.html`

## 5. 文件地图

- `agent-core`：稳定的领域模型和扩展接口。
- `agent-llm`：OpenAI 兼容模型客户端。
- `agent-tool`：工具注册、发现和 MCP 预留接口。
- `agent-rag`：RAG 抽象和空实现。
- `agent-data`：JPA、Redis、任务和步骤持久化。
- `agent-web`：Runtime、API、安全、事件和自动配置。
- `agent-demo`：应用启动、示例工具、知识库和页面。
- `docs`：现有架构、接口、配置、部署和测试说明。
- `.ua`：Understand Anything 生成的知识图谱数据，不是普通学习文档。

建议优先阅读的项目文档：

- [`architecture.md`](architecture.md)
- [`module-design.md`](module-design.md)
- [`business-flow.md`](business-flow.md)
- [`rest-api.md`](api/rest-api.md)

## 6. 复杂度热点

### 现在就要理解

- `DefaultAgentRuntime`：项目核心执行链。
- `AgentTaskService`：Web 请求如何转化成 Agent 任务。
- `AgentWebAutoConfiguration`：模块如何被组装。

### 第二轮再深入

- `KnowledgeService`
- `KnowledgeRagRetriever`
- `TaskEventPublisher`
- `SecurityConfig`

这些内容涉及数据库、向量库、Redis、异步事件或安全机制。

### 最后再看

- `OpenAiCompatibleModelClient`
- 前端 `index.html`
- JWT 刷新、黑名单和限流细节
- MCP 预留实现

它们的代码细节较多，但不会阻碍理解 Agent 主体。

## 7. 如何配合 Understand Anything 学习

本文负责提供学习顺序，后续可以使用两个配套 Skill。

### 深入讲解单个文件

使用：

```text
$understand-anything:understand-explain
```

建议第一个讲解目标：

```text
/root/hiagent/agent-web/src/main/java/com/agentflow/web/DefaultAgentRuntime.java
```

### 围绕整个项目提问

使用：

```text
$understand-anything:understand-chat
```

推荐的第一个问题：

```text
从 AgentController 开始，解释一次任务如何经过 Runtime、RAG、工具和模型，
并指出每一步对应的类和方法。
```

知识图谱网页只是辅助可视化，不是学习项目的必要条件。更高效的方式是按照本文的主
链路阅读代码，然后对不理解的类使用 `understand-explain`。

## 8. 第一次学习的完成标准

完成第一轮学习后，应当能够不看代码回答：

1. `agent-core` 为什么不直接依赖 OpenAI、Redis 或 Qdrant？
2. `DefaultAgentRuntime` 是怎样组合规划器、RAG、工具和模型的？
3. 新增一个 `AgentTool` 需要实现什么接口、在哪里注册？
4. Agent 的中间步骤如何保存并推送给前端？
5. 当前实现与真正的自主循环 Agent 还存在哪些差距？

能够清楚回答这五个问题后，再开始修改或扩展项目，会比直接从具体功能入手更稳妥。
