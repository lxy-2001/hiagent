# HiAgent 项目路线图

> 本文件规定项目最终要证明什么、V1 做到哪里、按什么 Feature 顺序实施。
> 它不是某个 Feature 的任务清单；具体需求与设计写入各 Feature 规格；代码任务只存在于当前 Feature 的
> `spec.md`、`plan.md` 和 `tasks.md` 中。

## 1. 项目定位

HiAgent 是一个面向学习、面试讲解和简历展示的 Java Agent 后端项目。

项目不以“堆出完整企业平台”为目标，而以以下能力为核心：

1. 自己实现并能解释 Agent 的关键运行机制。
2. 用清晰的模块边界把核心算法与模型、Spring、存储和协议适配解耦。
3. 提供可运行、可测试、可观测、可对比的真实演示链路。
4. 形成一套其他 Spring Boot 应用可以复用的 Runtime、适配器和 Starter。
5. 对安全、失败、预算和外部内容保持真实、受控的行为。

最终简历应能基于代码和数据证明：

- 理解并实现了 Agent 决策循环，而不是只调用一次大模型接口。
- 实现了结构化 Tool Calling、上下文预算、记忆、RAG 引用、MCP、审批和评测。
- 能说明哪些能力自研、哪些基础设施复用，以及为什么这样划分。
- 能通过自动化测试、运行轨迹和评测结果证明系统行为。

## 2. V1 完成形态

V1 完成后，演示应用应能完成如下闭环：

1. 接收一个 Java 后端研发问题或任务。
2. 建立 Run，逐步产出可观察的 Agent 事件。
3. 模型在受限循环内选择回答、调用本地 Tool、查询知识库或调用 MCP Tool。
4. Runtime 校验工具参数、执行调用、记录结果，并继续下一轮决策。
5. 上下文装配器在 Token 预算内组合系统规则、会话历史、受控记忆和标记来源的不可信检索数据。
6. 高风险调用进入暂停状态，经批准或拒绝后继续或终止。
7. 最终答案携带真实可验证的引用；失败、取消、超时和预算耗尽有明确终止原因。
8. SSE 或等价的最小接口能展示步骤、工具调用、引用和最终状态。
9. 一组离线评测能比较不同提示词、模型适配器或策略的正确性、成本和延迟。
10. 另一个 Spring Boot 应用可以通过 Starter 和自定义 Bean 使用或覆盖 HiAgent 能力。

V1 允许单进程运行；允许为适合的能力使用内存、本地文件、MySQL 或 Qdrant。选型由具体
Feature 的验收要求决定，而不是为了技术栈数量强行引入。

## 3. 明确的自研与复用边界

### 3.1 必须由本项目实现

下列内容是项目的核心学习价值和简历证据，不得整体委托给第三方 Agent 框架：

- Agent 决策循环、状态转换和终止判定。
- 模型决策、工具调用和最终回答的内部统一结构。
- Tool 注册、查找、参数 Schema、校验、执行和结果归一化。
- 最大轮数、超时、Token/输出等预算与取消传播。
- Run、Step、Event 的语义和关联关系。
- 上下文选择、裁剪、优先级和信任边界。
- 短期记忆及受控的结构化记忆策略。
- RAG 的检索组合、引用绑定和引用校验策略。
- MCP Tool 到内部 Tool 模型的映射与风险策略。
- Agent 质量评测指标、数据集执行和结果比较逻辑。

### 3.2 应优先复用

以下内容属于通用基础设施，可使用成熟库或服务：

- HTTP、SSE、JSON、线程池和 Spring Boot 生命周期。
- 模型厂商 SDK 或 Spring AI 的底层模型连接能力。
- JDBC/JPA、数据库驱动和迁移工具。
- Qdrant 等向量数据库及其客户端。
- 标准加密、日志、指标和 Trace 传输。
- MCP 的底层传输或 JSON-RPC 解析辅助库，但内部语义映射仍由项目负责。

每个核心 Feature 的 `plan.md` 必须列出本次的自研部分、复用部分、与框架直接方案的
差异，以及用于证明实现有效的代码路径、演示和测量结果。

## 4. V1 不做什么

以下内容不是 V1 验收条件：

- Redis Streams 分布式任务队列。
- 多 Worker 抢占、分布式锁、租约、故障转移和跨节点恢复。
- 完整 JWT 平台、复杂 RBAC、组织架构、多租户计费。
- 微服务拆分、服务治理、Kubernetes、高可用和 SLA 工程。
- 完整 APM 或企业级运维平台。
- 产品级前端、后台管理系统和复杂交互设计不属于001–008原V1验收；2026-09-26用户将其中的Agent工作台前端明确提升为独立V1.1 Feature009。后台管理平台仍不包含。
- 不受限的 Shell、文件系统或网络执行。
- 通用工作流引擎或大型多 Agent 编排平台。
- 覆盖 MCP 的所有协议能力。

这些能力可以进入 V1.1 候选区，但不得在实现 V1 Feature 时顺手加入。

## 5. 全局架构边界

`agent-core`
: 纯 Java。包含 Runtime、Agent 领域模型、策略和端口，不依赖 Spring 或具体基础设施。

适配器模块
: `agent-llm`、`agent-tool`、`agent-rag` 及后续协议/存储适配模块，实现核心端口或提供
  可替换能力。

Spring Boot 集成
: 精确的条件自动配置负责把适配器注册为 Bean；Starter 只聚合必要依赖和配置入口，
  不承载 Agent 算法，也不强迫用户引入所有可选实现。

`agent-web`
: 对外暴露最小 Run、事件、取消和审批接口，负责传输模型转换，不实现 Agent 决策。

`agent-demo`
: 真实的组合根和演示应用，只通过公开 API 使用轮子模块，不承载可复用业务实现。

全局不变量：

- 所有运行都有明确完成、失败、取消、超时或预算耗尽结果。
- Tool 输入在执行前校验；高风险操作不得绕过策略。
- 模型、Tool 和检索内容都不是系统指令来源。
- 引用必须能定位到真实检索片段，不能由模型凭空生成。
- 测试 Fixture、`Noop` 或降级输出不能伪装成生产成功。
- 默认测试不访问真实付费模型和不可控外部服务。
- 应用自定义 Bean 可以覆盖默认适配器；没有有效实现时应明确失败。
- 可复用模块不得通过扩大根包扫描被偶然加载。

## 6. Feature 总览

| 顺序 | Feature | 唯一结果 | 依赖 | 状态 |
| --- | --- | --- | --- | --- |
| 001 | 工程基线与 Starter 边界 | 构建、测试和装配边界可信 | 无 | VERIFIED |
| 002 | 自研 Agent Runtime 与本地 Tool | 受限 ReAct 循环真实运行 | 001 | VERIFIED |
| 003 | Run 生命周期与流式事件 | 可创建、观察、取消并重放单机 Run | 002 | VERIFIED |
| 004 | 上下文、会话与记忆 | 在 Token 预算内保持受控上下文 | 003 | VERIFIED |
| 005 | 可信 RAG 与引用 | 回答引用真实片段且可校验 | 004 | VERIFIED |
| 006 | MCP 与人工审批 | 外部 Tool 受控接入并可暂停审批 | 005 | VERIFIED |
| 007 | Agent 评测与可观测性 | 用数据比较正确性、成本和延迟 | 004、005、006 | VERIFIED |
| 008 | Starter、演示与简历发布 | 第三方应用可复用且演示可复现 | 007 | VERIFIED |
| 009 | 产品化 Agent 工作台（V1.1） | 以完整、美观、可访问的前端使用现有Agent能力 | 008 | VERIFIED |
| 010 / H01 | 上下文与会话历史升级（第二阶段） | 连续会话可恢复、容量触发压缩、大内容可查且有保留边界 | 002、003、004、008 | VERIFIED |

状态只能使用：

- `PLANNED`：边界已进入路线图，尚不是下一项。
- `NEXT`：依赖满足，下一步应创建或完善其 Feature 规格。
- `ACTIVE`：存在唯一激活 Feature，正在走 SDD 或实施。
- `VERIFIED`：全部验收、全仓验证和收尾门禁通过。
- `DEFERRED`：明确推迟，不属于当前 V1 交付路径。

单人开发默认同时最多一个 `ACTIVE` Feature。

## 7. Feature 详细规划

### Feature 001：工程基线与 Starter 边界

**唯一结果**

任何开发者都能用统一命令验证仓库；Demo 能通过显式自动配置加载所需 Web 和 Runtime
Bean；模块、Starter 与 Demo 的责任有自动化测试保护。

**范围**

- 增加或校正 Maven Wrapper，统一 Java 和 Maven 使用方式。
- 建立全仓构建、模块测试和最小 CI。
- 修复并测试 `agent-web` 等模块的自动配置发现。
- 增加完整应用启动测试和关键 Bean 装配测试。
- 明确 Runtime、适配器、自动配置、Starter 和 Demo 的依赖方向。
- 记录 Spring Boot、Spring AI、模型适配方式的兼容性 ADR。
- 校正公开 API 契约与当前实际入口；区分公开接口和临时 Demo 接口。
- 禁止生产路径静默使用 `Noop` 或测试 Fixture。

**不包含**

- 新 Agent 循环能力。
- 身份平台、分布式任务、生产部署。
- 为尚未实现的所有适配器一次性创建 Starter。

**演示与证据**

- `./mvnw clean verify` 在无真实模型 Key 的测试环境通过。
- Demo ApplicationContext 含预期 Controller、Runtime 和适配器 Bean。
- 替换一个应用 Bean 后，`@ConditionalOnMissingBean` 默认实现不再创建。
- 依赖图或测试证明 `agent-core` 不依赖 Spring。

**完成后**

创建 Feature 002 规格；不得继续在 001 中实现 Runtime 新行为。

---

### Feature 002：自研 Agent Runtime 与本地 Tool

**唯一结果**

一个纯 Java Runtime 能用确定性模型 Fixture 完成“思考/选择 Tool/接收结果/继续/结束”的
受限循环，并能通过真实模型适配器运行同一协议。

**范围**

- 定义统一的模型消息、模型决策、Tool Call、Tool Result 和最终回答结构。
- 实现显式 Runtime 状态机与循环，不由 Spring AI 的 Agent 抽象接管。
- 实现 Tool Registry、Tool Executor、参数 Schema 和输入校验。
- 实现最大轮数、总超时、Token/输出预算和取消信号。
- 定义 Agent Step、Agent Event、终止原因和错误分类。
- 提供至少两个安全的本地 Tool，用于证明多轮调用与参数校验。
- 模型厂商类型只在 `agent-llm` 中转换为核心模型。
- 对正常、未知 Tool、非法参数、Tool 异常、模型异常、超时、取消和预算耗尽进行测试。

**不包含**

- HTTP 异步任务管理。
- RAG、MCP、长期记忆和分布式执行。
- 复杂 Planner 或多 Agent。

**演示与证据**

- 一个测试场景至少经历两次 Tool 调用后得到最终答案。
- Step 记录能还原每轮输入类型、决策、Tool 结果和终止原因。
- 同一 Runtime 测试既能接确定性 Fixture，也能由 Demo 选择真实模型适配器。
- 核心循环单元测试无需启动 Spring。

---

### Feature 003：Run 生命周期与流式事件

**唯一结果**

客户端可以创建 Run、观察从开始到结束的事件、取消运行，并在晚订阅或重连后补齐单机
范围内仍保留的事件。

**范围**

- 定义 Run ID、状态、开始/结束时间、终止原因和事件序号。
- 提供单进程异步 Run Executor 和有界资源控制。
- 定义创建、查询、取消、订阅事件的最小公开 API。
- SSE 事件包含单调序号和终态，支持 `Last-Event-ID` 或等价游标。
- 提供有界的内存事件缓冲或简单 RunStore 端口，明确容量和过期行为。
- 处理先提交后订阅、多客户端订阅、断线重连、慢客户端和资源清理。
- Runtime 事件通过端口发布，`agent-core` 不依赖 SSE。

**不包含**

- Redis Streams、分布式消费者和租约。
- 进程崩溃后的强恢复保证。
- 新的多用户所有权、RBAC 或权限系统（仅复用现有认证身份做最小资源归属校验）。

**演示与证据**

- 任务开始后再订阅仍能收到缓冲内的早期事件和后续实时事件。
- 两个订阅者观察同一 Run，彼此不影响。
- 取消后 Runtime 收到取消信号并只产生一个明确终态。
- 重连、完成、失败、超时和清理均有集成测试。

---

### Feature 004：上下文、会话与记忆

**唯一结果**

同一会话可延续多个Run，每次模型调用都使用有界且来源明确的上下文，并使用用户显式保存的技术偏好。

**范围**

- 定义版本化系统提示词和 Prompt 组件。
- 实现 Context Assembler、来源优先级、Token 估算和裁剪策略。
- 从已提交成功Run派生完整会话轮次；Run准备时只读一次有限快照，每轮模型调用前连同本Run确认的Tool链重新装配，不增加跨Run缓存或重复消息表。
- 同会话最多一个在途Run，序号与创建同事务，名额随003真实释放条件回收。
- 记忆只接受所有者显式确认的两个会话内键（编程语言偏好、项目技术栈），类型/来源由服务端确定；用版本冲突及删除墓碑控制更新，不用置信度数值冒充正确概率，不自动保存模型文本。
- 上下文条目标记来源和信任级别，外部内容不能覆盖系统规则。
- 核心装配结果给出有限来源选择原因，公开Step只含无正文的计数、版本、预算和裁剪摘要。
- 测试超长历史、冲突记忆、重复内容、恶意外部文本和预算边界。

**不包含**

- 企业用户画像平台。
- 无限制长期记忆和自动保存所有对话。
- 跨租户隔离、隐私合规平台和复杂记忆图谱。

**演示与证据**

- 超长会话仍不超过设定预算。
- 关键项目事实跨轮次保留，低优先级旧内容被可解释地裁剪。
- 恶意检索或 Tool 文本不能提升消息角色、修改工具权限或执行预算；不把此边界等同于模型文本绝不受影响。
- 对比“无记忆”和“有策略记忆”场景的任务完成效果。

---

### Feature 005：可信 RAG 与引用

**唯一结果**

Agent通过统一Tool链检索公开项目/Java后端资料，正式引用仅绑定本Run最终模型请求中实际送达的片段，Run结果能核对当时原文及版本。

**范围**

- 离线完整快照导入UTF-8 Markdown/TXT，确定性清洗/分块/身份和原子激活；停止服务导入，重启后使用新版本。
- Qdrant语义候选+本地关键词，以有界RRF融合；显式开关控制关键词降级，空结果与故障分开。
- knowledge.search沿现有Registry/Executor执行，传播取消/剩余时限，不绕过Runtime。
- 类型化证据由Runtime登记和编号，遵守004完整当前Tool链与窗口预算。
- requireEvidence显式请求；无效引用在成功前失败，不自动再调用模型修复。
- 最终实际引用的片段快照与Run终态同事务保存，语料更新不替换旧回答证据。
- 替换Demo旧自动导入/静默RAG路径；保留旧表数据，旧临时reload返回410，新算法在agent-rag。

**不包含**

上传/知识库后台、网页/PDF导入、在线更新、多用户私有语料、永久全量索引归档、LLM改写/reranker及MCP/审批。

**演示与证据**

固定≥20文档/≥20题；分开测实际关键词和固定向量融合Recall@5；引用有效/拒绝率与真实语义支持率分开。真实Qdrant和真实模型实验分别记录；未执行不称已验证。

完整设计与任务：[005 spec](005-grounded-rag-citations/spec.md)、[plan](005-grounded-rag-citations/plan.md)、[tasks](005-grounded-rag-citations/tasks.md)。

---

### Feature 006：MCP 与人工审批

**唯一结果**

外部 MCP Tool 能映射为内部 `AgentTool` 并受同一校验、预算和事件体系约束；高风险调用
可暂停并由人工批准或拒绝。

**范围**

- 支持一个明确的 MCP 子集：初始化、Tool 列表和 Tool 调用。
- 固定 Streamable HTTP / MCP 2025-11-25与2025-06-18协商 / Java SDK 1.1.4；锁定版本的编译和传输探针为006 Phase1门禁，其他传输延期。
- MCP Tool Schema 映射为内部 Tool 描述和参数 Schema。
- 实现 Server/Tool allowlist、超时、结果大小限制和错误归一化。
- 对 Tool 建立风险等级和执行策略。
- 高风险调用生成审批请求，Run 进入 `WAITING_APPROVAL`。
- 提供批准、拒绝和审批超时的最小接口与事件。
- 审批在同一同步 Runtime 调用栈有界等待，占用已有执行槽；批准后继续同一已校验调用，不重启 Run 或重置预算。
- 使用本地测试 MCP Server 覆盖协议和失败路径；保留不可重放的审批/调用审计，明确网络结果UNKNOWN。
- 完整设计见[006 spec](006-mcp-tool-approval/spec.md)、[plan](006-mcp-tool-approval/plan.md)、[tasks](006-mcp-tool-approval/tasks.md)，跨Feature衔接见[REMAINING-FLOW](REMAINING-FLOW.md)。

**不包含**

- 完整实现所有 MCP 能力、传输和扩展。
- 进程重启后审批状态的分布式恢复。
- 通用工作流审批平台和复杂角色矩阵。

**演示与证据**

- 一个低风险 MCP Tool 自动执行并进入后续模型决策。
- 一个高风险 Tool 在执行前暂停，批准后继续、拒绝后以 APPROVAL_REJECTED 终止，不再回到模型自动尝试。
- 未允许 Server、Schema 不兼容、超时和过大结果均被阻止并记录。
- Runtime 对本地 Tool 与 MCP Tool 使用一致的 Tool Call 语义。

---

### Feature 007：Agent 评测与可观测性

**唯一结果**

项目能离线运行一组可重复任务，量化并比较 Runtime 或策略版本的正确性、Tool 使用、
引用、记忆、成本和延迟。

**范围**

- 定义版本化评测数据集、输入、期望行为和评分结果。
- 实现确定性或受控的评测 Runner。
- 覆盖最终答案、Tool 选择、参数正确性、引用正确性、记忆使用、MCP 风险和失败处理。
- 记录每个 Run 的 Step、事件、终止原因、耗时、模型调用次数和 Token 使用。
- 提供按场景和版本汇总的结果对比。
- Agent 语义和评分逻辑由项目实现；Micrometer/OpenTelemetry 等只负责通用传输。
- 默认日志和 Trace 对密钥、敏感参数和大段模型内容做脱敏或摘要。
- 为关键策略建立回归阈值，防止“测试通过但 Agent 能力退化”。

**不包含**

- 企业级 APM 平台搭建。
- 大规模在线 A/B 平台和人工标注系统。
- 用单一主观 Judge 分数替代可验证规则。

**演示与证据**

- 一条命令运行固定评测集并生成机器可读和人可读结果。
- 能比较两个 Prompt、模型或策略版本并解释差异来源。
- 至少包含成功、Tool 错误、RAG 无证据、取消、超时和审批拒绝场景。
- 评测结果可关联回 Run 和 Step，而不是只有最终总分。

---

### Feature 008：Starter、演示与简历发布

**唯一结果**

一个独立 Spring Boot 应用能按需引入 HiAgent Starter 和适配器运行完整演示；项目文档、
架构图、测试和评测结果可被面试者复现和讲解。

**范围**

- 整理核心 Starter 与可选模型、RAG、MCP 等适配器依赖边界。
- 用 `AutoConfiguration.imports` 和条件注解提供可覆盖的默认装配。
- Demo 只依赖公开模块 API，证明它不是靠根包扫描偶然工作。
- 提供最小 HTTP/SSE 演示和轻量页面或命令行客户端。
- 提供需要时才启动的本地依赖 Compose，不做生产部署编排。
- 完成架构文档、核心时序图、扩展指南、故障语义和安全说明；不重做 001 工程基线和 002 Runtime。
- 固化快速开始、真实模型配置、离线测试和评测命令。
- 汇总简历描述、量化结果、技术取舍和可现场演示脚本。
- 验证应用自定义 Model、Tool、RAG Bean 覆盖默认实现。

**不包含**

- 产品级前端和后台管理系统。
- Kubernetes、微服务发布、高可用与 SLA。
- 为发布而新增无演示价值的中间件。

**演示与证据**

- 全新示例应用可以只选择需要的 Starter/适配器，不被迫引入 Qdrant、Redis 等。
- 无真实模型 Key 时离线测试通过；启动需要真实模型的路径时明确报告缺失配置。
- README 的快速开始、完整 Agent 场景和评测步骤可从干净环境复现。
- `./mvnw clean verify`、Feature 检查清单、`speckit-analyze` 和
  `speckit-converge` 全部通过。

## 8. 推荐依赖路径

`001 工程基线`
→ `002 Runtime 与 Tool`
→ `003 Run 与事件`
→ `004 上下文与记忆`
→ `005 RAG 与引用`
→ `006 MCP 与审批`
→ `007 评测与可观测性`
→ `008 Starter 与发布`

这条顺序是推荐交付顺序，不表示所有能力有直接代码依赖：006 的 MCP/审批直接依赖 002–004 的契约，按顺序在 005 后实施。每个 Feature 应提供独立演示，
且不得预先实现后续 Feature 的产品行为。

## 9. V1.1 候选区

V1 验证后，再根据求职方向和实际需求从以下候选中选择，不默认全部实施：

1. 更复杂的 RBAC/多租户权限平台（既有JWT与资源所有者校验已在V1基线，不能再列为未实现）。
2. Redis Streams、分布式 Worker、租约与跨节点故障恢复（已有MySQL单进程Run持久化继续保留）。
3. 更长期的自动记忆、遗忘、冲突合并和隐私控制。
4. 可跨重启恢复执行的审批、企业审计与更完整风险策略（006包含不可重放的基础审批记录）。
5. Planner、工作流或多 Agent 协作。
6. 生产部署、容器编排、指标告警和容量验证。
7. 产品级 Web 界面。
8. 更多模型、向量库和 MCP 传输适配器。

进入 V1.1 的能力必须先成为新的路线图 Feature，再走完整 SDD，不得作为 V1 收尾时的
“顺手优化”。

## 10. Feature 大小与验收规则

一个合适的 Feature 应满足：

- 只有一个可独立表述的结果。
- 通常可在数天到两周内完成，而不是持续数月。
- 可以跨 Maven 模块，但不能同时包含多个独立产品目标。
- 有正常、失败和边界场景。
- 有不依赖真实付费模型的自动化证据。
- 有一条适合演示和面试讲解的主路径。
- 能明确说出未做什么以及为什么。

Feature 完成必须同时满足：

1. `tasks.md` 全部完成。
2. 每个 Phase 完成后通过相应模块验证并形成阶段提交。
3. 公开契约、文档和演示与实现一致。
4. 全仓构建与测试通过。
5. `speckit-analyze` 无未解决的 CRITICAL 或 HIGH 问题。
6. `speckit-converge` 无剩余未实现任务。
7. 路线图状态更新为 `VERIFIED`。
8. Feature 分支完成推送；是否合并由用户另行决定。

## 11. 历史蓝图说明

`specs/_project/complete-agent-backend-v1/` 保存了早期“大而全后端”设计，仅用于理解历史
取舍和提取候选需求。它不是当前 V1 的实施规格，也不能覆盖本路线图或当前 Feature。

如需采用其中的身份、分布式任务、生产部署等设计，必须先将相应能力提升为新的路线图
Feature，并重新完成 `specify → clarify → plan → tasks → analyze`。

## 12. 2026-09-25 当前交接

001–006 VERIFIED。006含Phase7双版本兼容已按用户授权合入并推送main@d5a9e67，Feature与兼容分支保留。005合并证据见[005 verification](005-grounded-rag-citations/verification.md)，006交付证据见[006 verification](006-mcp-tool-approval/verification.md)。

| Feature | 当前状态 | 当前入口 |
|---|---|---|
| 003 | VERIFIED | [003 spec](003-run-lifecycle-events/spec.md) |
| 004 | VERIFIED | [004 verification](004-context-session-memory/verification.md) |
| 005 | VERIFIED / 已合main | [005 verification](005-grounded-rag-citations/verification.md) |
| 006 | VERIFIED / 已实现与全量验收 / 已合main | [spec](006-mcp-tool-approval/spec.md)、[plan](006-mcp-tool-approval/plan.md)、[tasks](006-mcp-tool-approval/tasks.md)、[analysis](006-mcp-tool-approval/analysis.md) |
| 007 | VERIFIED / Phase1–5及78次基线通过 | [spec](007-agent-evaluation/spec.md)、[plan](007-agent-evaluation/plan.md)、[tasks](007-agent-evaluation/tasks.md)、[analysis](007-agent-evaluation/analysis.md) |
| 008 | VERIFIED / Phase1–4及独立交付验收通过 | [spec](008-starter-demo-delivery/spec.md)、[plan](008-starter-demo-delivery/plan.md)、[tasks](008-starter-demo-delivery/tasks.md)、[analysis](008-starter-demo-delivery/analysis.md) |

006七阶段已验证并合入main。按用户指令，本轮007完整设计、008完整预设计；实现顺序仍007→008，008开始前复核007实际契约。后续允许按当前规格必要修改旧代码，001/002历史完成记录不回写。

SDD仍是本地忽略资料；006的实际阶段提交与分支推送见verification，不自动合main。

> 007/008已完成全量验收，分支交付不代表合入main。真实付费模型/第三方服务未在本轮补测，外部发布NOT_PUBLISHED。


## 13. Feature009：产品化 Agent 工作台（2026-09-26）

用户明确要求正式产品级的前端视觉和完整交互，不实际部署。依据宪法V的独立Feature例外，将此能力从候选提升为V1.1已选范围；001–008历史验收与既有Agent语义保持。

- 唯一结果：用户能在统一工作台中登录、连续对话、恢复运行、查看引用/步骤、审批工具、管理两项确认记忆，桌面和手机均可完整使用。
- 范围：品牌化界面、双主题、响应式、无障碍、真实后端接线、安全排版、完整异常状态、可复现浏览器/视觉验收。为恢复和最近历史设计最小读取扩展，无数据库迁移或Runtime算法变化。
- 不包含：管理后台、注册/找回密码、在线密钥/模型配置、上传知识库、工具市场、成本报表、业务工具扩展、实际部署。
- 状态：VERIFIED；Phase1–8完成，共62项任务及全量门禁通过。该状态依据既有009验收记录；2026-10-06唯一SDD指针转到010，008仍VERIFIED。
- 文档：[spec](009-agent-workbench/spec.md)、[plan](009-agent-workbench/plan.md)、[design](009-agent-workbench/design.md)、[tasks](009-agent-workbench/tasks.md)、[analysis](009-agent-workbench/analysis.md)。
- 代码基线：当前008完整分支fb3de11，main尚不含007/008。未来009需从包含该实现的已验证基线分支创建，不自动合main。
- 009代码在独立分支codex/009-agent-workbench实施并阶段提交，最终验收通过，推送回执见009 verification；没有合入main或部署。specs/.specify仍被Git忽略，只是本地SDD。

## 14. Feature010 / H01：上下文与会话历史升级（2026-10-06建立，2026-10-07验证）

- 状态：VERIFIED；4个Phase、59/59任务完成，实际实现及全仓验收通过，analyze无未解决HIGH/CRITICAL、converge无剩余工作。
- 唯一结果：跨 Run 保存和恢复实际交互，维护持续追加的有界上下文，按容量压缩旧前缀；工具输出附件可按需读取，空间充足时保留，达到会话/全局限额后按最近使用顺序回收。
- 已确定：Windows 本地存储、Responses API、可配置的 gpt-6.1-sol、272,000 工作窗口；默认80%触发压缩，摘要与尾部目标≤原历史20%。持久化有限重试后停止新动作、不重放已执行工具；无旧数据迁移。具体默认参数见 spec，复用现有 Runtime。
- 澄清状态：Q1–Q3均已关闭。附件采用每会话512 MiB/目标384 MiB、全局5 GiB/目标4 GiB，按闲置会话最近使用时间淘汰并保护活动内容；已实现H2文件库、可配置Token估算和Responses适配，实际集成证据见verification各Phase记录。
- 不包含：H02计划/循环控制、H03文件工具、H04真实终端、桌面壳、安装能力、通用完成裁判。009不是后端验收前置，也不在本轮合并其代码。
- 文档：[spec](010-context-history-compaction/spec.md)、[plan](010-context-history-compaction/plan.md)、[tasks](010-context-history-compaction/tasks.md)、[verification](010-context-history-compaction/verification.md)及[需求质量检查](010-context-history-compaction/checklists/requirements.md)；总体讨论见[第二阶段](stage-02-harness/README.md)。
- 交付：四个阶段提交已完成；全仓clean verify为1,019项测试、0失败、0错误、1项既有Qdrant可选跳过，主线用量断言另行通过。最终状态文档随`codex/010-context-history-compaction`分支交付，SHA及远端回执由完成报告确认。原工作区/mvnw保留，无旧库迁移或删除、无真实付费模型调用，不合并main。010文档纳入分支，其余历史SDD仍按原忽略规则管理。
