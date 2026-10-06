# Implementation Plan: 010 / H01 上下文与会话历史升级

**Branch**: codex/010-context-history-compaction；隔离工作树实施，原工作区保持。
**Date**: 2026-10-06
**Spec**: [spec.md](spec.md)
**Input**: 已澄清的31项FR、5个用户故事与8项成功标准。
**Status**: Phase 1–3 实现与阶段门禁已通过；Phase 4 全仓验证与交付进行中。

## Summary

在现有DefaultAgentRuntime内接入可靠消息记录和有界工作上下文：跨Run恢复实际交互，按容量生成并提交摘要检查点，大内容存为受配额管理的本地附件。仍由模型选择工具或结束，不建立第二套Runtime，不混入H02计划控制、H03工程文件工具或H04终端。

实际基线为fb3de112eb220a743232fdad1af710c092826540。009完成记录不等于其代码已合入；本Feature基于当前008源码。实施前使用独立分支，保留用户dirty文件，不顺手更新mvnw、合并009或搬运用户改动。

## Technical Context

- **Language/Version**：Java 17、Maven 3.9.x、Spring Boot 4.1.1，沿用根POM。
- **Primary Dependencies**：现有Spring MVC/JPA、Jackson 3、Flyway；H2 2.4.240由现有Boot BOM管理。新增JTokkit 1.1.0放agent-llm，用于可配置o200k_base估算；不升级既有依赖。
- **Storage**：H2嵌入式文件库 + 应用托管文件。默认%LOCALAPPDATA%/HiAgent，可配置；单进程独占，不启用AUTO_SERVER、TCP数据库或MySQL自动迁移。
- **Testing**：JUnit、现有Spring测试、临时H2文件库、可控时钟与模型/工具Fixture、HTTP Mock。默认无付费模型调用。
- **Target Platform**：Windows本地后端；桌面壳不在H01。
- **Project Type**：纯Java核心 + 既有适配模块与Demo组合根；不新增Maven模块。
- **Performance Goals**：热循环不查询全量历史；恢复每页最多128条并受字节预算约束；缓存最多4会话/64 MiB，单会话工作集最多16 MiB。满时释放闲置缓存或明确拒绝，不能先全量读库再裁剪。不承诺未测的延迟。
- **Constraints**：用户输入保留现有8000字符HTTP边界；272,000 Token工作窗口，80%触发压缩；有界补写、不重放工具。容量、累计预算和附件配额独立。
- **Scale/Scope**：沿用同会话单Run和有界执行器；附件64 MiB/件、512 MiB/会话、5 GiB/全局。附件配额不包含数据库，另检查真实磁盘空间。
- **Self-Built Agent Components**：有序历史、工作序列、检查点、摘要策略、工具内容表示、故障停止及配额保护。
- **Reused Commodity Infrastructure**：JPA/H2/Flyway、HTTP/JSON、JTokkit、文件I/O和现有线程池。
- **Framework Comparison/Boundary**：不让Spring AI接管ReAct与上下文算法。Core只见项目类型和存储/摘要端口；Spring、数据库、分词库及Responses JSON留在适配器。
- **Portfolio Evidence**：跨Run恢复、两次压缩、附件范围读取/LRU清理、保存失败不重复执行；记录加载范围、压缩前后Token、实际执行次数及存储状态。

## Constitution Check

| Gate | 研究前 | 设计后与落实位置 |
|---|---|---|
| 单Feature范围 | PASS | 只落地H01；spec范围、tasks故事 |
| 自研Core | PASS | DefaultAgentRuntime、ContextSession、ContextCompactor |
| 自研/复用分工 | PASS | Technical Context、research |
| 模块与装配 | PASS | 精确条件Bean；Starter不聚合Web/数据库 |
| TDD及离线验证 | PASS | 各行为Phase先写失败测试、确认失败原因 |
| 真实状态与安全 | PASS | 持久化故障、未知操作、权限、引用契约 |
| 简历项目边界 | PASS | 无分布式租约、RAG重构、语义Judge、桌面产品化 |

PASS仅指设计符合约束，不代表实现或测试已通过。

## Project Structure

### Documentation

plan.md、research.md、data-model.md、quickstart.md、tasks.md、verification.md；contracts/包含history-runtime.md、responses-adapter.md、artifact-storage.md、configuration.md及openapi.yaml；prompts/context-compaction-v1.md与Core资源同版本的摘要Prompt。

### Source Code（既有模块；当前实现）

| 模块 | 主要路径与职责 |
|---|---|
| agent-core | runtime/DefaultAgentRuntime、AgentExecutionContext、AgentRunOptions；context/HistoryStore、ContextSession、WorkingContext、ContextCompactor、CompactionModel；artifact/ArtifactStore；model/AssistantTurnDecision |
| agent-llm | OpenAiResponsesAgentModelClient、ResponsesDecisionMapper、ResponsesCompactionModel、JtokkitTokenEstimator、ResponsesContinuationStore；保留chat/embedding能力 |
| agent-tool | ReadArtifactTool及精确装配，复用现有ToolRegistry |
| agent-web | context/JpaHistoryStore、ContextSessionService、BoundedPersistenceRetry、ContextHistoryController；artifact/LocalArtifactStore、ArtifactMaintenance、ArtifactController；既有RunCoordinator/RunPersistence |
| agent-demo | H2默认配置、V7正向迁移、端到端Fixture与验证入口 |
| agent-spring-boot-starter | 装配Core服务；无算法、无强制数据库依赖 |
| agent-eval | 仅兼容模型/用量和上下文变化，不扩大评测范围 |

完整包前缀为com.agentflow；contracts与tasks给出具体文件路径。

### 实施阶段

1. 本地文件库与基线：T001–T004，提交a90ef97。
2. 纯Java契约与计数适配：T005–T009，提交b208bb0。
3. 完整上下文管线：T010–T053，按US1 → US3 → US2 → US5 → US4依赖次序完成；覆盖可靠历史、附件、压缩、有限补写及Responses装配。
4. 一条端到端验收与交付门禁：T054–T059。

共59项。2026-10-07将原Phase 3–7合并为一个集成提交边界，原因是共享Runtime、迁移和恢复/装配文件；范围、任务编号、故事顺序和验收标准不变。最终完整装配在提交前统一验证，端到端测试独立归入Phase 4。

## Design Decisions

### Project-Owned Mechanisms

1. **创建与恢复**：createQueued事务同时确认用户历史记录，以runId构造幂等键，覆盖排队后取消。取得唯一调用权后打开owner/session/run绑定的ContextSession，加载最新检查点及未覆盖尾部。H01不再走仅取成功问答的PersistentContextSource；旧路径仅保留给未启用Harness的库消费者。
2. **追加与执行边界**：模型文本和Tool Call同批保存；确认后才更新工作序列、启动工具。真实工具结果保存确认后才进入下一轮。Step/Event仍是独立观察出口，不是恢复来源。
3. **最小模型协议扩展**：ModelDecision增加AssistantTurnDecision，携带有序可见文本、至多一个工具调用、FINAL/CONTINUE及用量。兼容旧两个decision及构造器。CONTINUE仅表达协议未结束，不增加H02阶段判断。多工具响应整体拒绝且不执行任一工具。
4. **压缩**：在已完成调用组边界处理旧前缀；摘要+尾部目标≤原有效历史20%。摘要≤8,192、尾部≤32,768 Token且不是保底。程序确定覆盖序号，检查点保存并CAS切换后才替换窗口。当前规则、Run用户输入与确认记忆从权威来源提供；不存在合法切分时明确失败。
5. **压缩失败**：一次容量处理最多一次摘要请求和一次修正请求，不执行工具。摘要失败但旧窗口满足硬容量时可沿用，并抑制本Run相同失败输入再次压缩；随后达到硬上限则结束。持久化重试耗尽优先停止。
6. **大内容**：先校验Tool Result归属、状态及RAG完整性，再按H01策略外置并形成预览/引用；处理Normalizer与Runtime两处8192字符拒绝条件。实际执行参数不能截短。旧失败工具导致终止的语义保留，恢复性工具失败策略属于H02。
7. **不重放**：工具意图先保存，执行后保存结果。进程在两者之间中断，只能记录UNKNOWN，不能推断未执行或成功。后续用户主动开启Run可看到不确定性；恢复本身不执行历史工具。
8. **终态**：最终模型消息先持久化；RunPersistence.complete在原终态事务内写幂等终止历史标记，再发布RUN_TERMINATED。失败沿用pending/503和补提交，不重启Runtime。新增历史故障专门映射，不落入INVALID_INPUT或笼统INTERNAL_ERROR。
9. **清理**：两级配额预留、顺序淘汰及活动保护；正文删除后引用保留墓碑。会话删除是显式用户接口，LRU不删除会话消息；并发及崩溃顺序见附件契约。

### Adapter and Integration Decisions

- **H2**：V1–V6不可变；6份SQL已通过临时文件库探针，新增V7为正向扩展，无旧数据搬运。探针不代替Flyway/JPA联调；Phase 1验证文件模式迁移、实体、锁与重启。旧MySQL可保留显式兼容profile，不作为新默认或自动fallback。
- **本地Web依赖**：现有JWT撤销、限流和登录刷新提示写入依赖Redis。Harness模式用H2保存撤销记录、有界内存保存限流计数，refresh token继续以原数据库表为准；保留JWT/所有权/退出语义及120次每分钟限制，不以关闭过滤器解决依赖。旧非Harness路径保留Redis适配。
- **Responses**：新默认走Responses；旧ChatModelClient/EmbeddingClient及其agentflow.model配置保持；Harness使用独立agentflow.harness.model配置。发送完整有效窗口，store=false，不用previous_response_id重新带回已压缩历史。必要opaque续接项由适配器保存，Core仅传引用、不读取内部推理。
- **分词**：JTokkit留agent-llm，编码显式配置。估算非计费事实；内容按hash/编码版本缓存计数，规则、工具JSON及长参数均计入。保留安全余量并报告估算来源，不偷偷退回旧64消息/262144字节硬限制。
- **预算**：替换Harness路径中RunCoordinator硬编码4096/2048累计限额，单次输出取32,768与累计剩余额度较小值。默认Run输入3,000,000、输出524,288 Token，10分钟，沿用8次决策；摘要计入同一时间/用量；摘要可见目标与总生成额度分开，总生成额度同时受摘要请求剩余窗口限制，H02再调整循环策略。旧Core静态默认保留；Harness不再被Web旧120秒上限截断。
- **装配**：Harness启用时必须有持久HistoryStore、ArtifactStore、CompactionModel和TokenEstimator，缺失则启动失败。显式瞬态库模式可以运行但不能声称持久化；精确自动配置、自定义Bean退让，不扩大扫描。
- **兼容**：任务/步骤/SSE/审批和认证保持，新增消息分页、上下文诊断、附件读取、会话touch和删除接口。新终止原因同步Core/Web枚举及消费者，旧值不改名。回归RAG、确认记忆和审批，不重构其功能。

### Validation Strategy

按个人简历项目规模验证，重点是关键行为正确，避免重复建立测试设施。保留行为TDD和现有回归；新增测试文件从27个合并为13个职责位置，不以文件/用例数或覆盖率作为完成目标。

| 主验证位置 | 责任与复用边界 |
|---|---|
| LocalStorageFileTest + JpaHistoryStoreTest | 文件库/Flyway/JPA、历史批次、检查点及必要续接的持久化；不为每张表单建DAO测试 |
| HarnessRuntimeTest | 历史确认与模型/工具执行的顺序、有界恢复、零重放，以及附件/压缩的最小接入检查 |
| ContextCompactorTest + JtokkitTokenEstimatorTest | 容量和压缩算法、配对与失败分支；Prompt只查结构/信任边界，不断言全文或保证语义无损 |
| LocalArtifactStoreTest + ReadArtifactToolTest | 正文范围、限额/LRU、活动保护及工具归属；只保留一个短并发场景 |
| ContextHistoryApiTest + LocalWebDependencyTest | 公开接口及本地真实认证/撤销/限流，不把这些边界再次搬进E2E |
| BoundedPersistenceRetryTest + 原Run事务/恢复测试 | 有限补写、提交确认丢失、终态通知顺序；原有取消/SSE/资源释放测试继续复用 |
| Responses适配测试 + LocalStorageFileTest/既有自动配置测试 | HTTP Mock协议、usage缺失/子项、精确装配/退让；不复制旧chat/RAG/MCP套件 |
| HarnessContextFlowTest | 唯一完整主线：跨Run→大输出→两次压缩→关闭重开→有效上下文及引用；不重复故障矩阵 |

文件持久化用临时H2文件库和临时内容目录，重启以关闭重开数据源/重建服务验证；故障在存储边界注入。容量测试使用小额可配置配额，默认GiB数值只检查配置和long边界。不进行压力/浸泡、真实磁盘写满、硬件断电、逐指令杀进程测试；不从这些离线测试推断真实模型摘要质量。

每项实现先运行目标测试；每Phase完成时运行tasks指定的受影响模块及依赖门禁，最终运行一次全仓clean verify。同一代码状态已通过且覆盖相同范围的结果可复用；仅因新修改、失败或未决问题重跑相应项，不再机械执行一遍quickstart命令表。详细分工见[quickstart.md](quickstart.md)，实际证据写入[verification.md](verification.md)。全部任务、全仓verify、analyze及converge通过后才可标VERIFIED与推送。

## Complexity Tracking

无宪法例外。端口对应真实替换边界：历史、附件I/O、摘要模型及provider续接存储；不建通用工作流引擎、消息总线或一类一接口。实现路径见上表；未完成验证的状态以verification和tasks为准。
