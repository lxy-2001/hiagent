# Tasks: 010 / H01 上下文与会话历史升级

**日期**：2026-10-06  
**状态**：VERIFIED；2026-10-07完成Phase 1–4，59/59任务已验证，四个阶段提交已完成；最终Feature交付见verification.md。按用户要求收紧新增测试，保留原功能范围和验收标准。
**Input**：[spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[contracts/](contracts/)、[quickstart.md](quickstart.md)。

## 执行规则

- 行为变化仍先写最小失败测试、确认目标失败，再实现；失败证据写在对应测试任务中，不单列“跑红并记录”的形式任务。已有测试不删、不跳、不弱化。
- 每条重要行为指定一个主验证点；其他层只检查连接是否正确，不重复相同边界。公开接口仍保留HTTP正常/失败/权限检查。测试矩阵见quickstart。
- 新增测试文件规划从27个合并为13个；这是职责安排，不是必须凑够的数量。优先扩展已有测试，只有实际新行为/回归缺口才追加场景，不考核覆盖率百分比。
- 任务阶段只跑目标测试；Phase结束跑受影响模块及依赖；最终一次全仓verify。同一代码状态已经通过的结果直接复用，不把quickstart命令表再全跑一遍；新修改、失败或未决问题才重跑相关项。
- 以临时H2、临时内容目录、可控时钟和HTTP Mock验证；重启采用关闭重开数据源/重建服务，故障在存储边界注入。保留短小的必要并发检查，不做压力/浸泡/真实磁盘写满/硬件断电/逐指令崩溃矩阵。
- `[P]`只标注本Phase内互不改同一文件的测试编写机会；共用Runtime、迁移、配置与Fixture的实现顺序执行，不自动委派代理。
- 实施前复核SDD指针、独立分支和analyze结果，保留用户改动。只在H01范围内工作，不加入H02–H04、桌面、通用测试裁判或企业平台能力。

## Phase 1: Setup — 本地文件库与实施基线

**目标**：本地H2承接迁移/JPA，保留用户工作区。

**主验证点**：LocalStorageFileTest：一次文件库迁移/启动、提交回滚、关闭重开和短锁冲突；不做多进程/断电测试。

- [X] T001 实施时先核对实际分支/dirty文件和009未合入的事实，建立独立 `codex/010-context-history-compaction` 分支；保留用户改动，以明确路径追踪本Feature文档并将基线与隔离方式记入 `specs/010-context-history-compaction/verification.md`，不修改忽略规则或自动合并009。
- [X] T002 在 `agent-demo/src/test/java/com/agentflow/demo/LocalStorageFileTest.java` 先写并运行本地配置的失败用例；用一个临时H2文件库验证Flyway/JPA启动、提交/回滚和关闭重开。数据根可配置；只做一次短行锁冲突检查，不另建多进程/异常断电测试设施。
- [X] T003 在 `agent-demo/pom.xml` 将BOM管理的H2加入运行依赖，在 `agent-demo/src/main/resources/application-local-harness.yml` 和 `agent-web/src/main/java/com/agentflow/web/context/HarnessStorageProperties.java` 实现本地数据根与文件库配置；保留V1–V6不变、禁止自动迁移/删旧库，不提前设为Demo默认。
- [X] T004 运行本阶段定向及agent-demo依赖模块测试，把Flyway真实迁移、Hibernate validate和持久锁证据记入 `specs/010-context-history-compaction/verification.md`；检查 `agent-spring-boot-starter/pom.xml` 未引入H2或Web依赖，按阶段门禁审查并提交。

**阶段门禁**：`./mvnw.cmd -pl agent-demo -am test`。执行和证据复用遵循文首规则，Phase 4使用最终同一次全仓验证。

**阶段检查点**：任务完成、验证通过、diff归属核对后，仅暂存明确路径并创建 `build(010): complete phase 1 - local storage baseline`。

## Phase 2: Foundation — 消息与计数契约

**目标**：复用既有纯Java契约测试，补足新决策和窗口计数边界。

**主验证点**：ModelDecisionContractTest、ContextContractTest及JtokkitTokenEstimatorTest；不为数据record的getter/构造器新建测试。

- [X] T005 [P] 扩展已有 `agent-core/src/test/java/com/agentflow/core/model/ModelDecisionContractTest.java` 和 `agent-core/src/test/java/com/agentflow/core/context/ContextContractTest.java`，覆盖同响应文本与调用保序、拒绝多调用、真实归属边界；旧构造器由已有回归覆盖，不为每个record字段或getter新建测试。先运行确认目标失败再实现，并记录到 `specs/010-context-history-compaction/verification.md`。
- [X] T006 [P] 在 `agent-llm/src/test/java/com/agentflow/llm/JtokkitTokenEstimatorTest.java` 用少量边界样例验证272000窗口、完整规则/tools/长参数计入、旧消息/文本上限不再阻碍及未知编码失败；一次参数化用例覆盖配置变更后的重估，不验证分词库内部算法。
- [X] T007 在 `agent-core/src/main/java/com/agentflow/core/context/HistoryStore.java`、`agent-core/src/main/java/com/agentflow/core/context/ContextSession.java`、`agent-core/src/main/java/com/agentflow/core/context/CompactionModel.java`、`agent-core/src/main/java/com/agentflow/core/artifact/ArtifactStore.java` 定义数据模型及真实I/O端口；在 `agent-core/src/main/java/com/agentflow/core/model/AssistantTurnDecision.java`、`agent-core/src/main/java/com/agentflow/core/model/ModelDecision.java` 和 `agent-core/src/main/java/com/agentflow/core/runtime/AgentRunOptions.java` 实现兼容扩展，Core不含厂商/数据库类型。
- [X] T008 在 `agent-llm/pom.xml` 加入JTokkit 1.1.0，在 `agent-llm/src/main/java/com/agentflow/llm/JtokkitTokenEstimator.java` 实现显式编码估算与有界计数缓存；调整 `agent-core/src/main/java/com/agentflow/core/context/ContextPolicy.java` 支持Harness窗口和完整输入边界，旧估算器不得作为静默fallback。
- [X] T009 运行本阶段目标和受影响模块回归，复用 `agent-core/src/test/java/com/agentflow/core/architecture/CoreDependencyBoundaryTest.java` 及旧构造器测试；把真实结果记入 `specs/010-context-history-compaction/verification.md`，没有新失败或改动不重复执行同一门禁。

**阶段门禁**：`./mvnw.cmd -pl agent-core,agent-llm,agent-spring-boot-starter -am test`。执行和证据复用遵循文首规则，Phase 4使用最终同一次全仓验证。

**阶段检查点**：任务完成、验证通过、diff归属核对后，仅暂存明确路径并创建 `feat(010): complete phase 2 - context contracts`。

## Phase 3: Integrated — 可靠 Harness 上下文管线

2026-10-07实施计划调整：原Phase 3–7共享Runtime、V7迁移、恢复服务和自动装配，合并为一个可运行且可验证的集成提交边界。T010–T053编号、各故事顺序、范围和验收要求不变；不把已混合实现声称为五个独立验证/提交阶段。

### US1 — 有序历史与跨 Run 恢复（P1）

**目标**：完整历史、有界恢复与本地HTTP依赖可工作。

**主验证点**：HarnessRuntimeTest验证执行顺序；JpaHistoryStoreTest验证持久事实；ContextHistoryApiTest与LocalWebDependencyTest验证API和真实安全链，各有明确责任。

- [X] T010 [P] [US1] 在 `agent-core/src/test/java/com/agentflow/core/runtime/HarnessRuntimeTest.java` 新增H01运行边界用例：两次Run保留文本/调用/失败事实、恢复checkpoint+tail、热循环只追加、有界加载和不重放；使用可控HistoryStore/模型观察实际输入与工具次数。先跑失败用例，不新建WorkingContext专用测试矩阵。
- [X] T011 [P] [US1] 在 `agent-web/src/test/java/com/agentflow/web/context/JpaHistoryStoreTest.java` 用临时文件库验证整批序号、同键同内容重复确认/异内容冲突、分页所有权和正文分批；同一类后续补checkpoint/provider保存场景，不为每张表新建一套DAO测试。
- [X] T012 [P] [US1] 在 `agent-demo/src/test/java/com/agentflow/demo/ContextHistoryApiTest.java` 先覆盖messages/context/touch的正常请求和代表性越权/分页边界，后续原类扩展附件与删除接口；在 `agent-demo/src/test/java/com/agentflow/demo/LocalWebDependencyTest.java` 保留一条真实登录→刷新→退出→重开拒绝的本地流程，以及代表性429/503检查。Redis不可达，不用Mock安全过滤器代替验证。
- [X] T013 [US1] 新增 `agent-demo/src/main/resources/db/migration/V7__context_history_compaction.sql`，在 `agent-web/src/main/java/com/agentflow/web/context/HistoryRecordEntity.java` 等数据模型实体中落实data-model.md的8张表、唯一键/索引/外键；不修改V1–V6，补充迁移后JPA validate到LocalStorageFileTest。
- [X] T014 [US1] 在 `agent-web/src/main/java/com/agentflow/web/context/JpaHistoryStore.java` 实现owner/run校验、短事务appendBatch、同键内容核对、元信息优先的有界分页及检查点读取；异常不得返回空历史。此时先明确失败，US5再接入有限重试。
- [X] T015 [US1] 在 `agent-core/src/main/java/com/agentflow/core/context/WorkingContext.java` 与 `agent-web/src/main/java/com/agentflow/web/context/ContextSessionService.java` 实现checkpoint+tail恢复、增量追加、profile/Token缓存和4会话/64MiB/单会话16MiB上限；无检查点且放不下时明确失败，US2再接入压缩，不能先全量读库后裁剪。
- [X] T016 [US1] 修改 `agent-core/src/main/java/com/agentflow/core/runtime/DefaultAgentRuntime.java` 和 `agent-core/src/main/java/com/agentflow/core/runtime/AgentExecutionContext.java`：模型批次确认后才准许工具、执行意图确认后才调用、结果确认后才下一轮；保留旧瞬态路径、审批/预算语义，不能用RuntimeTrace代替历史。
- [X] T017 [US1] 在 `agent-web/src/main/java/com/agentflow/web/run/RunPersistence.java` 将USER_MESSAGE加入createQueued事务、RUN_TERMINAL加入complete事务，并在 `agent-web/src/main/java/com/agentflow/web/run/RunCoordinator.java` 传递ContextSession；统一session→session_context→task→明细的锁顺序，队列取消也有事实，未提交终态不得发事件。
- [X] T018 [US1] 在 `agent-web/src/main/java/com/agentflow/web/context/ContextHistoryController.java` 实现messages/context/touch及有界公开DTO；Harness路径停用 `agent-web/src/main/java/com/agentflow/web/conversation/PersistentContextSource.java` 的成功问答拼接，保持既有turns/task/steps/SSE接口语义与所有权检查。
- [X] T019 [US1] 在 `agent-web/src/main/java/com/agentflow/web/support/WebRequestStateStore.java`、`agent-web/src/main/java/com/agentflow/web/support/LocalWebRequestStateStore.java` 与 `agent-web/src/main/java/com/agentflow/web/support/RedisWebRequestStateStore.java` 提供本地/旧Redis适配；修改 `agent-web/src/main/java/com/agentflow/web/auth/AuthService.java`、`agent-web/src/main/java/com/agentflow/web/auth/JwtBlacklistFilter.java`、`agent-web/src/main/java/com/agentflow/web/config/RateLimitFilter.java` 和 `agent-web/src/main/java/com/agentflow/web/config/SecurityConfig.java`，Harness使用H2令牌撤销及有界本地限流，刷新令牌仍以数据库为准；不跳过鉴权、不连Redis、不新增RBAC，故障503且覆盖新附件路径。
- [X] T020 [US1] 运行US1目标与本阶段全部模块测试，核对加载页数、实际模型input与调用次数，把证据写入 `specs/010-context-history-compaction/verification.md`；独立验收通过并审查diff后提交。


### US3 — 附件与容量边界（P1）

**目标**：大结果外置、按范围读取及两级LRU保持真实可用状态。

**主验证点**：LocalArtifactStoreTest集中验证文件/配额；ReadArtifactToolTest验证工具适配；在已有HarnessRuntimeTest和ContextHistoryApiTest补接入用例，不新建同义套件。

- [X] T021 [P] [US3] 在 `agent-web/src/test/java/com/agentflow/web/artifact/LocalArtifactStoreTest.java` 合并正文/配额测试：UTF-8范围与原文一致、单件截断、会话及全局LRU、活动pin保护、缺失/写失败和路径越界。使用小额配置与临时目录；只保留一个并发预留/读删保护场景，不造真实5GiB数据、真实磁盘写满或junction替换攻击矩阵。先确认目标失败。
- [X] T022 [P] [US3] 扩展 `agent-core/src/test/java/com/agentflow/core/runtime/HarnessRuntimeTest.java` 的一条“大工具结果经过默认执行器再外置”用例，覆盖全部归一化调用链和长参数不截短；在 `agent-tool/src/test/java/com/agentflow/tool/ReadArtifactToolTest.java` 验证范围/归属/错误返回；扩展 `agent-demo/src/test/java/com/agentflow/demo/ContextHistoryApiTest.java` 的artifact/delete、404/410、忙会话409与受理202。不另建RuntimeArtifactTest或ArtifactApiTest。
- [X] T023 [US3] 在 `agent-web/src/main/java/com/agentflow/web/artifact/LocalArtifactStore.java` 实现受控路径、流式限额写入、WRITING到可读状态的发布与UTF-8范围读取；元信息/保存范围真实，不返回尚未确认可用的引用，源目录不作为清理目标。
- [X] T024 [US3] 在 `agent-web/src/main/java/com/agentflow/web/artifact/ArtifactAdmissionCoordinator.java` 与 `agent-web/src/main/java/com/agentflow/web/artifact/ArtifactMaintenance.java` 实现单进程短锁预留/pin/删除领取、两级LRU、低水位停止和有界重试接口；I/O在锁外，计量含预留和未实际删完正文，不能清理活动依赖。
- [X] T025 [US3] 在 `agent-core/src/main/java/com/agentflow/core/tool/DefaultToolExecutor.java`、`agent-core/src/main/java/com/agentflow/core/tool/DefaultToolResultNormalizer.java` 和 `agent-core/src/main/java/com/agentflow/core/runtime/DefaultAgentRuntime.java` 接入内容策略，覆盖执行器与Runtime各自SAFETY_NORMALIZER；保留状态/关联/证据校验，Harness按Token预览/附件取代8192字符拒绝，真实参数不截短。
- [X] T026 [US3] 新增 `agent-tool/src/main/java/com/agentflow/tool/ReadArtifactTool.java`，在 `agent-tool/src/main/java/com/agentflow/tool/AgentToolAutoConfiguration.java`、`agent-tool/src/main/java/com/agentflow/tool/ToolPolicyAutoConfiguration.java` 接入有归属scope的只读工具和明确允许策略；按需读取返回有界内容，不接入任意文件读取或终端。
- [X] T027 [US3] 实现 `agent-web/src/main/java/com/agentflow/web/artifact/ArtifactController.java`，在 `agent-web/src/main/java/com/agentflow/web/conversation/ConversationService.java` 和 `agent-web/src/main/java/com/agentflow/web/conversation/ConversationController.java` 加显式删除/隐藏/异步清理；删除与Run准入共用短锁顺序，清理数据库按外键顺序且保留恢复状态直到完成。
- [X] T028 [US3] 运行本阶段目标与受影响模块回归，在 `specs/010-context-history-compaction/verification.md` 记录附件范围、清理顺序和活动保护结果；原有RAG/审批契约使用已有测试，不复制新套件，审查通过后提交。


### US2 — 压缩与检查点（P1）

**目标**：送模前处理容量，检查点确认后切换，不拆调用组。

**主验证点**：ContextCompactorTest为算法主测试；JpaHistoryStoreTest补事务边界；HarnessRuntimeTest仅确认接入。完整两次压缩后重开留给唯一E2E主线。

- [X] T029 [P] [US2] 在 `agent-core/src/test/java/com/agentflow/core/context/ContextCompactorTest.java` 集中验证阈值/预留、完整调用组、20%目标、再次压缩及模型失败不切换；用少量 `agent-core/src/test/resources/context/compaction-cases.json` 场景审查Prompt栏目、修订/未知结果/外部文字边界和无工具请求。参数化边界不做字段组合穷举，先确认失败，不断言整段Prompt逐字相等。
- [X] T030 [P] [US2] 扩展 `agent-web/src/test/java/com/agentflow/web/context/JpaHistoryStoreTest.java` 的checkpoint事务测试：覆盖序号与CAS、保存失败保留旧指针、文件库重开后不重复尾部；不新建JpaCheckpointTest，也不在此重复整个Runtime两次压缩流程。
- [X] T031 [US2] 实现 `agent-core/src/main/java/com/agentflow/core/context/ContextCompactor.java`：完整输入估算、阈值与硬上限、按完成调用组选前缀/尾部、动态20%摘要预算、首次加最多一次修正；不可行明确报错，源内容/覆盖序号不可交由模型猜测。
- [X] T032 [US2] 在 `agent-web/src/main/java/com/agentflow/web/context/JpaHistoryStore.java` 完成saveCheckpoint的revision CAS与原子active指针切换，并在 `agent-core/src/main/java/com/agentflow/core/context/WorkingContext.java` 提交确认后替换窗口；原始消息不删，失败重试只保存原摘要，恢复不重载覆盖原文。
- [X] T033 [US2] 把经Fixture审查的 `specs/010-context-history-compaction/prompts/context-compaction-v1.md` 固定为 `agent-core/src/main/resources/prompts/context-compaction-v1.txt`，用 `agent-core/src/main/java/com/agentflow/core/context/CompactionPrompt.java` 生成受限请求；输入视为材料、不回答旧问题/不调用工具，严格解析栏目且不要求隐藏推理。
- [X] T034 [US2] 在 `agent-core/src/main/java/com/agentflow/core/runtime/DefaultAgentRuntime.java`、`agent-core/src/main/java/com/agentflow/core/runtime/AgentExecutionContext.java` 接入每次送模前容量检查、摘要预算/取消、失败输入抑制；保持权威规则/当前用户输入/确认记忆，连续压缩复用旧摘要，模型配置变更在下一Run重估。
- [X] T035 [US2] 在 `agent-web/src/main/java/com/agentflow/web/context/ContextHistoryController.java` 与 `agent-web/src/main/java/com/agentflow/web/context/ContextSessionService.java` 暴露有效窗口、预留、估算来源、检查点范围及压缩前后大小/失败原因；不返回provider opaque内容或整库历史。
- [X] T036 [US2] 在 `agent-core/src/test/java/com/agentflow/core/runtime/HarnessRuntimeTest.java` 补一个容量准备接入用例，压缩算法边界复用ContextCompactorTest；运行本阶段模块门禁，在 `specs/010-context-history-compaction/verification.md` 留证。最终两次压缩后重开只在端到端主线完整执行一次。


### US5 — 有限补写与异常恢复（P1）

**目标**：故障停止新动作，同一记录补确认，历史工具不自动重放。

**主验证点**：BoundedPersistenceRetryTest验证重试规则；HarnessRuntimeTest和现有RunFinalTransactionTest/RunPersistenceRecoveryTest验证代表性失败。旧SSE/取消/线程池矩阵直接复用。

- [X] T037 [P] [US5] 在 `agent-web/src/test/java/com/agentflow/web/context/BoundedPersistenceRetryTest.java` 用可控时钟参数化“短暂恢复、持续失败、不可重试、取消/到期”四类结果；验证4次总尝试、200/500/1000ms及10秒上限，退避不占事务。先确认失败，不真等超时或用大量随机异常轰炸。
- [X] T038 [P] [US5] 在 `agent-core/src/test/java/com/agentflow/core/runtime/HarnessRuntimeTest.java` 验证模型记录未确认不调用工具、工具结果未确认不再调模型、已保存意图缺结果恢复UNKNOWN且不重放；扩展现有 `agent-web/src/test/java/com/agentflow/web/run/RunFinalTransactionTest.java` 与 `agent-web/src/test/java/com/agentflow/web/run/RunPersistenceRecoveryTest.java` 的“提交成功但确认丢失”和终态事件顺序。以存储边界注入故障和重建服务验证，不逐条指令杀进程，不重新建设SSE/审批/线程池矩阵。
- [X] T039 [US5] 实现 `agent-web/src/main/java/com/agentflow/web/context/BoundedPersistenceRetry.java` 并接到 `agent-web/src/main/java/com/agentflow/web/context/JpaHistoryStore.java`、`agent-web/src/main/java/com/agentflow/web/artifact/LocalArtifactStore.java` 和 `agent-web/src/main/java/com/agentflow/web/artifact/ArtifactMaintenance.java`；事务外退避、同键读回确认、不可重试立即终止，不能重跑工具/重新生成摘要作为存储重试。
- [X] T040 [US5] 在 `agent-core/src/main/java/com/agentflow/core/runtime/TerminationReason.java`、`agent-web/src/main/java/com/agentflow/web/run/RunTerminationReason.java` 增加HISTORY_PERSISTENCE_FAILED/CONTEXT_COMPACTION_FAILED/ARTIFACT_STORAGE_FAILED，并修正 `agent-core/src/main/java/com/agentflow/core/runtime/DefaultAgentRuntime.java`、`agent-core/src/main/java/com/agentflow/core/tool/DefaultToolExecutor.java` 和 `agent-web/src/main/java/com/agentflow/web/run/RunResultProjector.java` 的捕获/映射；可靠历史失败不能落入忽略观察异常或笼统INVALID_INPUT。
- [X] T041 [US5] 在 `agent-web/src/main/java/com/agentflow/web/context/ContextSessionService.java` 和 `agent-web/src/main/java/com/agentflow/web/artifact/ArtifactMaintenance.java` 实现启动/恢复核对：分页收敛WRITING/DELETING/缺失文件和计量、阻止未核对新写入、为孤立调用建立UNKNOWN恢复说明，不执行旧工具、不全量加载正文。
- [X] T042 [US5] 在 `agent-web/src/main/java/com/agentflow/web/run/RunCoordinator.java` 与 `agent-web/src/main/java/com/agentflow/web/run/RunPersistence.java` 接通有限补写、原终态pending维护和取消/关闭收敛；保持固定锁顺序，终态未确认继续可观察503，不新增无限Run重试或另起Runtime。
- [X] T043 [US5] 运行故障目标及本阶段模块回归，把有限补写、零重放、耗尽后零新动作、终态未提交不发完成事件的证据记入 `specs/010-context-history-compaction/verification.md`；已有取消、超时、SSE和释放用例继续复用，不另建HistoryRecoveryTest/RunHistoryLifecycleTest。


### US4 — Responses 与装配（P2）

**目标**：生产协议和本地装配接通，实际用量与缺失值有区别。

**主验证点**：OpenAiResponsesAgentModelClientTest共用Mock测主模型/摘要；LocalStorageFileTest、AgentWebAutoConfigurationTest、AgentLlmAutoConfigurationTest及ContextHistoryServiceTest复用装配/用量验证；JpaHistoryStoreTest补provider持久化。

- [X] T044 [P] [US4] 在 `agent-llm/src/test/java/com/agentflow/llm/OpenAiResponsesAgentModelClientTest.java` 共用一个HTTP Mock覆盖主请求和摘要适配：text+call/call_id/必要opaque续接、完整有效input、摘要无工具、显式失败/取消与返回体上限；用参数化样例检查usage缺失/0/缓存子项。先确认目标失败，不分建ResponsesCompactionModelTest或真实供应商测试矩阵。
- [X] T045 [P] [US4] 在 `agent-demo/src/test/java/com/agentflow/demo/LocalStorageFileTest.java` 及已有Web/LLM自动配置测试中覆盖完整默认装配、自定义Bean退让和缺必需存储启动失败，附一项主模型/摘要用量去重汇总；扩展 `agent-web/src/test/java/com/agentflow/web/context/JpaHistoryStoreTest.java` 验证opaque续接保存/恢复/压缩后释放。现有旧chat/embedding回归继续使用，不分建HarnessUsageTest、ResponsesContinuationTest。
- [X] T046 [US4] 实现 `agent-llm/src/main/java/com/agentflow/llm/OpenAiResponsesAgentModelClient.java`、`agent-llm/src/main/java/com/agentflow/llm/ResponsesDecisionMapper.java` 与 `agent-llm/src/main/java/com/agentflow/llm/http/BoundedResponsesTransport.java`：受限HTTP、取消/截止、ordered输出、single-tool、usage和opaque引用；store=false、parallel_tool_calls=false、不传previous_response_id，不回退chat或偷偷换模型。
- [X] T047 [US4] 实现 `agent-llm/src/main/java/com/agentflow/llm/ResponsesCompactionModel.java`，复用受限传输和请求配置，接入版本化Prompt/严格解析、32K总输出与8K可见摘要限额、取消/失败真实记录；不实现provider自动compaction代替本地算法。
- [X] T048 [US4] 定义 `agent-llm/src/main/java/com/agentflow/llm/ResponsesContinuationStore.java` 并实现 `agent-web/src/main/java/com/agentflow/web/context/JpaResponsesContinuationStore.java`；在同一可靠写入边界确认必要opaque引用，按有效窗口保留/回收，隐藏载荷不得进入Core/public DTO，模型切换不带回旧覆盖历史。
- [X] T049 [US4] 实现 `agent-web/src/main/java/com/agentflow/web/context/ModelCallLedger.java` 并调整 `agent-core/src/main/java/com/agentflow/core/runtime/BudgetTracker.java`、`agent-web/src/main/java/com/agentflow/web/context/ContextHistoryController.java`：按requestKey去重记主请求/摘要，包括已知失败用量；cached/reasoning为子项、缺失null，actualUsage与knownUsage/usageComplete分开；预算缺实际值用显式保守估算。
- [X] T050 [US4] 在 `agent-web/src/main/java/com/agentflow/web/autoconfigure/HarnessContextConfiguration.java`、`agent-llm/src/main/java/com/agentflow/llm/AgentLlmAutoConfiguration.java`、`agent-web/src/main/java/com/agentflow/web/run/RunCoordinator.java`、`agent-web/src/main/java/com/agentflow/web/run/RunLifecycleProperties.java` 接入contracts/configuration.md默认值及校验；Harness替代4096/2048累计限额与120秒上限，冻结Run配置，不改变旧Core默认或旧chat-model配置。
- [X] T051 [US4] 在 `agent-llm/src/main/java/com/agentflow/llm/AgentLlmAutoConfiguration.java`、`agent-web/src/main/java/com/agentflow/web/autoconfigure/AgentWebAutoConfiguration.java`、`agent-spring-boot-starter/src/main/java/com/agentflow/autoconfigure/AgentRuntimeAutoConfiguration.java` 精确装配同一个Runtime；必需能力缺失明确失败，默认Bean退让，在 `agent-demo/src/main/resources/application.yml` 启用Demo本地Harness默认配置，并用本Phase门禁验证全部生产Bean确实齐备。
- [X] T052 [US4] 运行已有 `agent-llm/src/test/java/com/agentflow/llm/AgentLlmAutoConfigurationTest.java`、`agent-spring-boot-starter/src/test/java/example/hiagent/StarterOverrideTest.java`、`agent-eval/src/test/java/com/agentflow/eval/ContractTest.java`；仅在本次新行为没有覆盖时补最小断言。不得为了H01复制RAG/MCP/评测全套兼容场景。
- [X] T053 [US4] 运行本故事HTTP/装配/用量目标及全部受影响模块测试，核对真正调用Responses、必要续接/取消、provider字段不外泄，在 `specs/010-context-history-compaction/verification.md` 记录证据与限制后审查本故事；统一在本Phase门禁通过后提交。


**阶段门禁**：`./mvnw.cmd -pl agent-demo,agent-eval -am test`。所有目标测试、已有回归和装配启动通过后，将T010–T053标记完成。

**阶段检查点**：`feat(010): complete phase 3 - durable harness context pipeline`，不包含Phase 4的独立端到端测试文件。

## Phase 4: Integration — 一条主线与交付

**目标**：证明整体可用，复用已取得的边界证据完成交付。

**主验证点**：HarnessContextFlowTest仅一条完整主线；其余验收读取主验证点结果，不把所有失败组合重新跑成E2E。

- [X] T054 在 `agent-demo/src/test/java/com/agentflow/demo/HarnessContextFlowTest.java` 的显式离线配置与Fixture中写一条端到端主线：跨Run历史→大输出附件→两次压缩→关闭重开→读取有效上下文与附件，并核对实际工具调用数及用量。其余故障/LRU/所有权以各自主测试证据验收，不在E2E再跑完整矩阵；新增集成缺口按TDD修复。
- [X] T055 同步 `specs/010-context-history-compaction/contracts/openapi.yaml` 与已实现接口，复用 `agent-demo/src/test/java/com/agentflow/demo/ContextHistoryApiTest.java` 的HTTP结果检查正常/错误DTO；旧任务/步骤/SSE路径不改名，不另建等价契约测试套件。
- [X] T056 核对 `specs/010-context-history-compaction/quickstart.md` 的主验证点已有证据，仅补遗漏或受新修改影响的场景；最后运行一次 `./mvnw.cmd clean verify`，将测试数/退出码与限制记入 `specs/010-context-history-compaction/verification.md`。已有测试不删除/跳过，成功后无新改动、失败或未决风险不重复全仓验证；新增依赖随构建解析和既有门禁检查，不增加独立企业安全扫描平台。
- [X] T057 按speckit-analyze只读复核 `specs/010-context-history-compaction/spec.md`、`specs/010-context-history-compaction/plan.md`、`specs/010-context-history-compaction/tasks.md` 和契约；以独立文档修订处理所有HIGH/CRITICAL再重跑分析，将报告结果记入 `specs/010-context-history-compaction/verification.md`，不能把当前设计分析充作实现后分析。
- [X] T058 执行speckit-converge，对照 `specs/010-context-history-compaction/tasks.md` 与实际代码/证据找剩余未实现工作；如追加任务，先按TDD完成并复验，再确认没有未完成验收，不因预算或文档齐全提前结束。
- [X] T059 在 `specs/010-context-history-compaction/verification.md` 记录简历演示代码路径、离线事实与限制，审查最终产品diff、凭据/真实响应泄漏及排除项；汇总所有既有阶段SHA；本阶段提交与最终状态更新按下方交付检查点执行。

**阶段门禁**：`./mvnw.cmd clean verify`。执行和证据复用遵循文首规则，Phase 4使用最终同一次全仓验证。

**阶段检查点**：任务完成、验证通过、diff归属核对后，仅暂存明确路径并创建 `test(010): complete phase 4 - harness context acceptance`。

## 依赖与安全并行

Phase 1 → Phase 2 → Phase 3（US1 → US3 → US2 → US5 → US4）→ Phase 4。边界测试可隔离编写，共享实现顺序推进，只有主代理串行运行Maven；本次保留既有实现和检查证据，不重复同一已验证代码状态。

## 需求追踪

覆盖表示有具体实现/测试归属，不要求每一条FR单独建测试类。跨层复用证据，不用最终E2E代替边界测试。

| 需求 | 关注点 | 具体任务 |
|---|---|---|
| FR-001 | 有序消息与同响应文本/调用 | T005、T007、T010、T011、T013、T014、T016、T017、T020、T044、T046、T053 |
| FR-002 | 归属、序号与调用关联 | T005、T007、T010、T011、T013、T014、T016、T020 |
| FR-003 | 可靠历史独立于观察摘要 | T010、T014、T016、T017、T018、T020、T038、T043 |
| FR-004 | Run间输入与既有取消/审批 | T005、T010、T012、T016、T017、T018、T020、T038、T042 |
| FR-005 | 检查点/尾部恢复且不重放 | T007、T010、T011、T014、T015、T020、T030、T032、T036、T038、T041、T043、T045、T048 |
| FR-006 | 增量工作状态 | T010、T015、T016、T020 |
| FR-007 | 分页与内存边界 | T010、T011、T012、T013、T014、T015、T018、T020、T035、T041 |
| FR-008 | 272K及可配置窗口 | T006、T008、T015、T029、T031、T036、T045、T050、T053 |
| FR-009 | 全输入估算与独立预算 | T006、T008、T029、T031、T034、T036、T044、T045、T046、T049、T050、T053 |
| FR-010 | 80%阈值与预留触发 | T029、T031、T034、T036、T050 |
| FR-011 | 合法前缀和20%压缩目标 | T029、T030、T031、T032、T034、T036 |
| FR-012 | 提交后切换检查点 | T013、T029、T030、T032、T034、T036、T045、T048 |
| FR-013 | 重复压缩及权威规则 | T029、T030、T032、T033、T034、T036 |
| FR-014 | 独立摘要Prompt | T007、T029、T031、T033、T036、T044、T047、T053 |
| FR-015 | 有界摘要/保存失败 | T029、T030、T031、T032、T033、T034、T036、T037、T039、T040、T043、T044、T047、T049、T053 |
| FR-016 | 压缩诊断与真实容量错误 | T029、T031、T034、T035、T036、T049 |
| FR-017 | 自动外置大结果 | T007、T021、T022、T023、T025、T028 |
| FR-018 | 附件真实元信息和截断 | T013、T021、T022、T023、T024、T027、T028、T038、T040、T041、T043 |
| FR-019 | 按ID/范围授权读取 | T021、T022、T023、T026、T027、T028 |
| FR-020 | 保存预览、不自动全文展开 | T021、T022、T023、T025、T026、T028 |
| FR-021 | 真实长参数与结果边界 | T006、T008、T021、T022、T023、T025、T028、T029、T031 |
| FR-022 | 会话/全局LRU及活动保护 | T012、T018、T021、T022、T024、T027、T028、T037、T039、T041、T043、T050 |
| FR-023 | Windows本地持久化 | T002、T003、T004、T012、T013、T019、T021、T023、T045、T050、T051、T053 |
| FR-024 | Responses与配置模型 | T005、T007、T009、T044、T045、T046、T047、T048、T050、T051、T052、T053 |
| FR-025 | 模型切换与远端状态边界 | T006、T008、T015、T034、T044、T045、T046、T048、T050、T051、T052、T053 |
| FR-026 | 稳定前缀及工具顺序 | T006、T008、T044、T046、T053 |
| FR-027 | 真实用量与缓存子项 | T013、T044、T045、T046、T047、T049、T052、T053 |
| FR-028 | 可关联诊断及敏感信息 | T006、T008、T012、T018、T019、T022、T027、T029、T033、T035、T044、T045、T046、T047、T048、T049、T051、T053 |
| FR-029 | 有限幂等补写、故障停止 | T007、T011、T014、T016、T017、T021、T024、T025、T030、T032、T037、T038、T039、T040、T041、T042、T043、T050 |
| FR-030 | 不确定操作不重放 | T010、T016、T017、T038、T041、T042、T043 |
| FR-031 | 全新本地数据、不迁移 | T001、T002、T003、T004、T013、T019 |

| 成功标准 | 主验证/实现任务 | 核验重点 |
|---|---|---|
| SC-001 | T010、T011、T012、T038、T054 | 顺序/关联、失败取消、跨Run/重启 |
| SC-002 | T029、T030、T032、T054 | 两次压缩、20%、覆盖与权威约束 |
| SC-003 | T006、T029、T034、T044、T050 | 容量全计入、预留、阈值、不无限摘要 |
| SC-004 | T021、T022、T023、T026 | 附件范围、截断、权限与不可用 |
| SC-005 | T021、T024、T027 | 两级LRU、真实最近使用、活动保护 |
| SC-006 | T011、T030、T037、T038、T039、T042 | 4次/10秒、未知提交、零重放/零后续动作 |
| SC-007 | T002、T012、T019、T044、T045、T049、T051 | 文件库、Responses、切模型、真实用量 |
| SC-008 | T052、T054、T056、T057、T058 | 无付费默认依赖、Fixture标识、全仓回归 |

## 最终状态和交付

Phase 4全部任务、全仓verify、analyze与converge通过后提交本Phase，再同步spec/verification/ROADMAP为已验证并创建最终文档状态提交；依AGENTS.md推送Feature分支，不合并main。实施已授权，Feature门禁全部通过后推送独立分支。

specs当前被Git忽略；实施时按明确路径追踪本Feature所需文档，不改忽略规则、不夹带无关文件、不使用git add . / -A。

**任务统计**：59项；Phase计数4/5/44/6。US1 11项、US2 8项、US3 8项、US4 10项、US5 7项，其余15项为基线/基础/交付；状态以逐项复选框为准。任务重编号以本文件为准，旧72项记录仅为上一版历史。
