# H01 Verification / Evidence

**当前状态：Phase 1–4实现与验收已完成，共59项；最终状态文档、阶段提交和分支推送按本文末尾交付记录确认。** 日期和耗时以机器测试日志记录为准。下列设计阶段的探针与文档检查保留为历史，不替代产品证据。

实施工作树：`C:/Users/25118/.codex/worktrees/hiagent-h01/hiagent`；分支：`codex/010-context-history-compaction`。原`E:/hiagent`工作区及用户已有改动保留；本工作树中的用户`mvnw`改动也不纳入提交。

## 1. 设计阶段基线与范围（历史）

- 实际代码分支：codex/008-starter-demo-delivery；HEAD：fb3de112eb220a743232fdad1af710c092826540。
- SDD指针：.specify/feature.json → specs/010-context-history-compaction；计划实施分支codex/010-context-history-compaction尚未创建。
- 用户原有改动：mvnw，以及batman-bike.html、ig-champions.html、learning/、pelican-bike.html、pelican-grandpa-ride.html、pelican-plane.html；本轮不编辑这些路径。
- 本轮仅复审/同步specs内SDD文件并收紧测试设计，无产品代码实现、旧库迁移/删除、附件实际清理、真实模型调用、提交或推送。
- specs与.specify目前被Git忽略，因此以文件回读和内容校验确认交付；git diff为空不能证明这些文件未修改。实施时按tasks约定精确纳入Feature文档。

## 2. 已执行研究探针

**目的**：核对选择H2文件模式是否存在明显SQL/锁阻碍；不代替Flyway/JPA集成。

临时目录：C:/Users/25118/AppData/Local/Temp/hiagent-h01-h2-probe-PAeQnO。只使用该探针目录中的临时库与6份原迁移SQL副本，没有连接项目旧数据库。

~~~powershell
java -cp 'C:/Users/25118/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar' 'C:/Users/25118/AppData/Local/Temp/hiagent-h01-h2-probe-PAeQnO/H2FileProbe.java' 'C:/Users/25118/AppData/Local/Temp/hiagent-h01-h2-probe-PAeQnO'
~~~

退出码0，实际输出：

~~~text
H2_FILE_PROBE_OK migrations=6 reopen=true rowLock=true rollback=true publicTables=14
~~~

已覆盖：原V1–V6 SQL依次执行、文件重开读取已提交数据、两个连接的行锁及回滚。**未覆盖**：Flyway schema history、Hibernate实体validate、完整Spring应用启动、V7、进程崩溃/断电耐久性。Flyway/JPA、V7及恢复的正式验证当前列入T002/T013/T038/T054；不把真实进程崩溃/硬件断电测试扩为本Feature门禁，也不得从探针推断全部兼容。

## 3. 上轮plan/tasks生成的历史验证（72项任务版本）

| 检查 | 证据/状态 |
|---|---|
| Spec Kit plan入口 | setup-plan.sh --json已执行一次，解析到当前010；未创建或切换Git分支 |
| Spec Kit tasks入口 | setup-tasks.sh --json已执行一次，读取本地tasks模板及research/data-model/contracts/quickstart |
| Hook | .specify/extensions.yml不存在，无计划/任务/分析扩展Hook |
| 范围与模板 | 12份计划配套文件已生成；spec/checklist及6份入口同步；无产品实现 |
| 需求和任务追踪 | 最终直接校验结果见下方；覆盖表位于tasks.md |
| 契约与文档链接 | 最终直接校验结果见下方；仅做设计结构检查，不冒充HTTP实现测试 |


### 文档直接校验结果

执行：`python C:/Users/25118/AppData/Local/Temp/hiagent-h01-sdd-Vk8UD0/validate_sdd.py`，退出码0。校验结果文件位于同目录result.json；脚本与原需求基线留在临时目录，没有写入产品模块。

- 12份设计配套文件齐全；31条FR、8条SC、5个用户故事保持原行为文本不变。
- 8个Phase、72项任务、已勾选0项；31/31需求有具体实现/测试任务，19项独立测试编写任务标注[P]，阶段依赖已说明。
- 103个本地文档链接目标存在；无遗留模板占位符或未关闭澄清标记。
- OpenAPI包含5条路径/5个操作；8个Schema通过JSON Schema结构校验，35个内部引用可解析；路径参数、JWT安全声明和错误缓存头均核对。此项不是运行中的HTTP契约测试，也不是完整第三方OpenAPI认证。
- 初次校验暴露的是脚本对[P]说明行和operation级路径参数的误判；修正判定后完整重跑通过，没有因此弱化产品需求。
- Git状态仍仅包含原有mvnw和6个未跟踪文件/目录条目；代码分支/HEAD及Feature指针未变。当前12份新设计文件位于被忽略的specs目录，未提交或推送。

本轮额外发现并纳入设计：JWT黑名单、登录刷新提示及限流仍依赖Redis。T013/T021及Phase 7装配要求本地模式用H2/有界内存承接这些必要状态，并以真实安全链验证；不以跳过鉴权让本地演示通过。

Spec Kit的最终analyze为只读，报告在本次会话输出；实施开始前和最终交付前都应重新运行，不能长期复用一次设计检查替代后续检查。

## 4. 设计阶段产品验证状态（历史）

| 验证项 | 当前状态 | 计划证据入口 |
|---|---|---|
| H2/Flyway/JPA文件库、独占/锁/重启 | NOT RUN | T002–T004、LocalStorageFileTest |
| Core契约、分词与旧构造器 | NOT RUN | T005–T009 |
| 跨Run历史/分页/取消事实及本地Web安全链 | NOT RUN | T010–T020 |
| 附件正文/两级LRU/并发/权限 | NOT RUN | T021–T028 |
| 两次压缩、Prompt、CAS与恢复 | NOT RUN | T029–T036 |
| 有限补写/未知结果/终态SSE | NOT RUN | T037–T043 |
| Responses/续接/用量/装配 | NOT RUN | T044–T053 |
| 端到端与全仓clean verify | NOT RUN | T054–T056 |
| 实现后analyze / converge | NOT RUN | T057–T058 |
| 真实模型效果/缓存收益 | NOT RUN，不属默认门禁 | 用户后续授权才执行 |
| 阶段提交、Feature推送 | NOT RUN | 全部任务及AGENTS门禁满足后 |

## 5. 原阶段记录格式（历史）

实施时逐Phase追加实际记录：日期/分支/SHA → 任务编号 → 失败测试命令与目标失败原因 → 最小实现路径 → 定向测试与模块门禁的测试数/退出码 → 范围审查/限制 → 阶段提交SHA。真实模型和离线Fixture结果分开，失败不可删去。

该设计阶段尚无产品验收结论和提交SHA。技术设计决定已写入，不需要重复确认用户已经拍板的行为；若实际兼容测试推翻数据库/协议假设，应先更新SDD后实施。

## 6. 2026-10-06 测试范围复审（历史）

- 用户要求：符合个人简历项目规模，保留有效测试，避免企业级和重复测试。
- 8个Phase不变；任务72→59，计划新增测试文件27→13，直接引用的既有测试4→8。合并测试编写/证据记录任务，产品功能与31项FR、8项SC不变；13只是职责规划，不是数量门槛。
- 每个关键行为一个主验证位置；已有SSE/取消/资源释放等回归复用。一条完整E2E验证跨Run、附件、两次压缩和重开，不重复故障/LRU/权限矩阵。
- 临时文件库重开、少量存储边界注入及一个附件并发场景足够支撑首版；不加入真实磁盘写满、断电、压力/浸泡或逐指令杀进程验证。
- 目标测试→阶段受影响模块→最终一次全仓验证，复用同代码状态的有效证据；既有测试不删、不跳、不弱化。
- 明确恢复边界：打开/查询/恢复工作状态不发模型请求；用户主动开始新Run后的容量准备可触发摘要，但不重放旧工具。
- 直接校验：运行 `python C:/Users/25118/AppData/Local/Temp/hiagent-h01-test-review-EFtgpJ/validate_sdd.py`，退出码0；报告保存在同目录result.json。31条FR与8条SC行为文本不变，5个故事、8个Phase、59项连续唯一任务，已完成0项；31/31需求和8/8成功标准有任务映射。
- 计划新增测试文件13个、直接引用既有测试8个；13项测试编写任务可在对应阶段隔离编写，所列测试文件无同阶段并行冲突。106个本地链接有效；OpenAPI的5条路径/5个操作、8个Schema、35个内部引用检查通过，无残留模板占位符。
- Git状态仍只包含基线中的mvnw及6项未跟踪条目；HEAD、当前分支和Feature指针未变。没有产品测试、实现、提交或推送。
- 最后的speckit-analyze为只读，最终报告在本次会话输出；此处记录的是已实际完成的文档直接校验，产品测试仍全部NOT RUN。

## 2026-10-07 Phase 1 / T001–T004

实施分支 codex/010-context-history-compaction；基线 fb3de112eb220a743232fdad1af710c092826540，未合并009。隔离工作区 C:/Users/25118/.codex/worktrees/hiagent-h01/hiagent；原 E:/hiagent 的用户修改保留。

LocalStorageFileTest 初次因缺少 local-harness profile 失败；实现后真实 Flyway V1–V6、Hibernate validate、事务提交/回滚、短行锁和关闭重开通过。新增嵌套测试配置曾污染 Demo 扫描，取消其组件注解后复验通过。H2 runtime 仅在 Demo，Starter 未增加数据库依赖。

命令：./mvnw.cmd -pl agent-demo -am test；BUILD SUCCESS，耗时 3:20。临时证据 hiagent-h01-phase1-red.log、hiagent-h01-phase1-green.log、hiagent-h01-phase1-fix.log、hiagent-h01-phase1-gate2.log。无真实模型调用、旧库修改。


## 7. Phase 2 契约实现记录

- 既有提交：`b208bb06e729dbebd373a6033586de544b869750`，`feat(010): complete phase 2 - context contracts`；任务T005–T009。新增消息/历史/附件/压缩契约及可配置分词实现，保留Core纯Java和旧调用入口。
- 初期定向RED见`hiagent-h01-phase2-red.log`、`hiagent-h01-phase2-red2.log`；目标通过记录见`hiagent-h01-phase2-green.log`。随后`hiagent-h01-phase2-gate.log`暴露长参数仍受旧限制，`hiagent-h01-phase2-current.log`暴露后续集成中的新回归；这些失败保留，不将它们记为门禁通过。长参数及新主线修正已纳入Phase 3，最终阶段门禁将重新覆盖这些基础契约。

## 8. Phase 3 定向验证与审查修复

任务范围T010–T053；集成同一DefaultAgentRuntime的可靠历史、工作上下文、检查点、附件、Responses、用量及本地装配。没有增加H02计划工具、H03文件工具、H04终端、桌面壳或通用完成裁判。

| 主验证位置 | 当前已取得的证据 |
|---|---|
| 历史/Runtime/缓存/有限补写 | `hiagent-h01-main-green.log`、`hiagent-h01-chain-green3.log`、`hiagent-h01-managed-green.log`等聚焦记录；最终以本阶段模块门禁复核 |
| 取消、截止、续接与压缩失败用量 | `hiagent-h01-boundaries-green.log`：ContextCompactorTest 11、HarnessRuntimeTest 13、Responses适配20、JpaHistoryStoreTest 6，共50项通过 |
| Artifact及Run准入事务保护 | `hiagent-h01-artifact-review-red.log`先复现预览元信息常驻内存、活动会话被清理及读取配置冲突；修复后`hiagent-h01-artifact-final-green2.log`中LocalArtifactStoreTest 3、RunFinalTransactionTest 6通过 |
| 默认本地启动 | 同一`artifact-final-green2`记录中LocalStorageFileTest 2通过：临时H2文件库V1–V7、Hibernate validate、默认Responses/持久上下文装配，无外部数据库或Redis连接 |
| 压缩输入累计预算 | `hiagent-h01-compaction-input-red.log`：第一次摘要耗尽输入后仍发第二次，预期1次实际2次；修复后`hiagent-h01-compaction-budget-green.log`通过 |
| 可见摘要与总输出分离 | `hiagent-h01-compaction-ledger-red.log`：预期总输出32768、实际8192；修复后同一`compaction-budget-green`记录通过 |

单次Artifact正文Token计量减去消息格式开销，协议读取上限8192与实际配置限额分别生效；不删除无进展保护。附件预览/墓碑按ID查询数据库，不全量驻留内存。全局清理与新Run准入通过短领取协调，准入保护直到事务完成，数据库和文件I/O不持有该monitor。

## 9. Phase 4 早期主线记录（历史，最终结果见下文）

`HarnessContextFlowTest.offlineHarnessRunsTwiceCompactsTwiceAndReopensWithoutToolReplay`在`hiagent-h01-artifact-final-green2.log`通过；一条显式离线Fixture验证跨Run历史、大结果附件、两次压缩、关闭重开、有效尾部/引用及工具不重放。该次聚焦运行共12项、0失败/0错误/0跳过；它不替代全仓verify、公开HTTP回归、analyze或converge。

所有新模型验证均使用HTTP Mock或显式离线Fixture，没有付费模型请求、旧MySQL迁移或删库。真实模型摘要语义质量、供应商模型可用性与缓存收益未验证，不能由离线结果推断。

### 集中预算与诊断修复验证

`./mvnw.cmd -pl agent-demo -am test -Dtest=ContextCompactorTest,ContextSessionServiceTest,HarnessRuntimeTest,OpenAiResponsesAgentModelClientTest -Dsurefire.failIfNoSpecifiedTests=false`：退出0，BUILD SUCCESS；11+7+13+20=51项，零失败/错误/跳过（`hiagent-h01-compaction-budget-green.log`）。摘要可见目标与总生成额度分别管理；失败摘要已耗尽累计输入时不再开始修正请求；已知真实用量保留，未知用量以明确保守估算扣预算。

最终契约审查发现诊断接口误用默认输出预留。扩展既有ContextHistoryServiceTest账本用例先RED（实际32768、预期100；`hiagent-h01-latest-reserve-red.log`），让Snapshot读取最新请求真实预留/安全余量后GREEN（`hiagent-h01-latest-reserve-green.log`，3项、退出0）。没有请求时仍使用配置；没有增加接口字段或数据库迁移。

### 最终集成修复（2026-10-07）

- 防御性参数拷贝：原ToolSchemaContractTest先失败（新旧对象identity相同），修正ValidationResult保留managed语义并复制参数；`hiagent-h01-validation-copy-green.log`中ToolSchemaContractTest 5、HarnessRuntimeTest 13通过。
- 阶段回归首次仍失败（`hiagent-h01-phase3-gate-green.log`文件名不代表成功）：Demo 69项中2失败、14错误。定位到测试配置进入组件扫描、旧消费者误启用Harness及V5→V6迁移测试未限定目标版本。改用TestConfiguration、显式禁用旧消费者Harness和限定迁移目标6；旧断言保留。聚焦`hiagent-h01-demo-regressions.log`中13项仅压缩主线失败。
- 摘要容量：`hiagent-h01-adaptive-summary-red.log`单项先复现CONTEXT_COMPACTION_FAILED；历史加固定总输出预留超过窗口，未发出摘要请求。现按剩余窗口限制总输出，同时独立保留可见摘要目标；请求不可容纳时仍明确失败。`hiagent-h01-adaptive-summary-green.log`中ContextCompactorTest 12、HarnessContextFlowTest 1，共13项，退出0，零失败/错误/跳过；两次压缩和重开主线在当前代码通过。

### Phase 3 最终阶段门禁

- `hiagent-h01-phase3-gate-final.log`：Demo 69项、0失败、6错误；旧Hibernate/create-drop测试误启用Harness但没有V7 JDBC表，保留原断言并为这些旧测试显式禁用Harness。
- `hiagent-h01-phase3-gate-corrected.log`：Demo 69项、1失败、0错误；数据库超时夹具被默认local-harness配置覆盖。
- `hiagent-h01-phase3-gate-green-final.log`（名称不代表通过）：Demo 69项、2失败、1错误。test profile排除了旧夹具所需Redis；同次RAG回归出现一次等待终态超时及一次PERSISTENCE_UNAVAILABLE。RAG代码与断言未改，未将这两次失败归因于已确认的根因。
- RunDatabaseTimeoutTest改用独立timeout-test profile，保持MySQL connectTimeout/socketTimeout及Hikari断言；聚焦`RunDatabaseTimeoutTest,RagConversationEndToEndTest`在`hiagent-h01-focused-timeout-rag.log`中5项通过，0失败/错误/跳过。
- 最终阶段命令：`./mvnw.cmd -pl agent-demo,agent-eval -am test`，日志`hiagent-h01-phase3-gate-reviewed.log`；退出0、BUILD SUCCESS，9个测试模块共1,019项，0失败、0错误、1项跳过。Demo 69项全部通过，其中原RAG 4项亦通过；前述RAG失败在聚焦和最终门禁均未复现，不声称已证明其根因。结束时间为日志记录的2026-10-07T06:14:48+08:00。
- 唯一跳过的是原有RealQdrantSmokeTest，按其既有显式真实服务开关运行；本次未增加跳过或弱化既有断言。全仓最终verify另列于Phase 4。

## Phase 4 / T054–T059：最终验收与实现后审查

### 全仓验证

- 命令：`./mvnw.cmd clean verify`。退出码0，`BUILD SUCCESS`，全部10个reactor条目成功，Demo可执行jar已打包。日志：`%TEMP%/hiagent-h01-final-clean-verify.log`；机器日志结束时间`2026-10-07T06:24:01+08:00`，耗时04:02。
- 从本次Surefire XML独立汇总：1,019项，0失败、0错误、1跳过。唯一跳过为既有`RealQdrantSmokeTest.roundTripsDeterministicVectorsUsingAllProtocolOperations`，其真实服务开关`feature005.realQdrant=true`未开启；没有新增跳过或弱化断言。

| 模块 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| agent-core | 405 | 0 | 0 | 0 |
| agent-llm | 68 | 0 | 0 | 0 |
| agent-tool | 16 | 0 | 0 | 0 |
| agent-rag | 62 | 0 | 0 | 1 |
| agent-mcp | 79 | 0 | 0 | 0 |
| agent-spring-boot-starter | 7 | 0 | 0 | 0 |
| agent-web | 274 | 0 | 0 | 0 |
| agent-eval | 39 | 0 | 0 | 0 |
| agent-demo | 69 | 0 | 0 | 0 |
| 合计 | 1,019 | 0 | 0 | 1 |

- 本次包含真实H2/Flyway/JPA文件库重开、默认装配、本地认证链、新HTTP接口、Responses HTTP Mock、既有RAG/审批/取消/SSE/资源释放回归。前述RAG失败在本次全仓验证中未复现，仍不声称已证明其根因。
- 全仓通过后，仅在已有端到端主线补充两个Run的用量断言，没有修改生产代码。针对修改执行`./mvnw.cmd -pl agent-demo -am -Dtest=HarnessContextFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`：退出0、1项通过、0失败/错误/跳过。日志`%TEMP%/hiagent-h01-phase4-usage-acceptance.log`，机器日志结束时间`2026-10-07T06:36:15+08:00`。其他套件证据复用同一生产代码状态的全仓结果，后续文档更新不重复全仓测试。

### 唯一完整主线及公开契约

- `HarnessContextFlowTest.offlineHarnessRunsTwiceCompactsTwiceAndReopensWithoutToolReplay`采用显式测试Fixture，真实Coordinator、Runtime、H2及附件存储：跨两个Run持续记录→一次大工具输出形成附件→连续两次压缩→关闭重开应用→恢复第二检查点与未覆盖消息→按范围读取附件。工具实际调用总数为1；重开和恢复新增工具调用、模型请求均为0；历史含一个工具意图及两个终态，检查点序号保持且压缩目标≤20%。
- 两个Run分别核对Fixture报告用量：5次主模型决定加1次摘要，输入600、输出80,104、总计80,704 Token；摘要只累计一次。此为确定性离线计数，不是真实模型质量或账单。
- OpenAPI直接校验：3.1.0、5条路径/5个操作、8个Schema、35个内部引用均可解析，operationId唯一，JWT bearer及各响应Cache-Control:no-store符合实现。`ContextHistoryApiTest`在全仓门禁中覆盖新增消息/诊断/touch/删除/附件范围接口；旧任务/步骤/SSE接口及认证不改名。

### speckit-analyze：实现后只读报告

| 项目 | 核对结果 |
|---|---|
| 需求 / 成功标准 | 31条FR + 8条SC，共39条，全部有具体任务，覆盖率100% |
| 任务 | 59个唯一且连续ID，无重复、无失效任务引用 |
| 故事 / 验收 | 5个用户故事，22个验收场景 |
| 文档 / 路径 | 任务引用89个仓库文件均存在；需求检查表16/16通过 |
| 冲突 / 歧义 / 重复 | 未发现需修复的跨文档冲突、需求歧义或重复任务 |
| HIGH / CRITICAL | 0个未解决问题；宪法五项原则无违反 |

逐条需求到任务的映射见tasks.md的“需求追踪”；对应实现/主验证点见plan、quickstart及上方阶段证据。本次报告针对实际实现，设计阶段分析保留为历史。状态文案由后续独立完成文档更新，不将设计时的NOT RUN表当作当前状态。

### speckit-converge：实现与验收收敛

- 已逐项核对31条FR、8条SC、22个验收场景、16项设计决定（9项项目机制 + 7项适配/集成）和5项宪法原则。
- 结果：converged。没有missing/partial/contradicts/unrequested的待实现产品缺口；最后的证据与交付工作已在T054–T059覆盖，无需追加重复任务。
- 审查期间tasks.md字节内容/哈希保持不变，没有写入空Convergence阶段；通过后由实施流程勾选既有任务。
- `.specify/extensions.yml`不存在，analyze/converge/implement无待执行扩展hook。

### 范围与敏感信息审查

- 按基线fb3de112eb220a743232fdad1af710c092826540核对Feature改动路径；本阶段只提交一条E2E、对应契约/启动指南、任务状态与验证记录。
- 132个Feature源文件/文档的凭据模式检查没有候选命中；API/JWT示例只用占位符/环境变量。Core依赖边界测试通过，无Spring/JPA/provider实现倒灌Core。
- `git diff --check`通过；mvnw用户改动、H01临时协调文件、日志、target及真实模型响应不纳入任何提交。只使用明确路径暂存，原E:/hiagent工作区不修改。

### 可讲解代码路径与限制

| 展示机制 | 核心路径 | 验证证据 |
|---|---|---|
| 自研循环 + 可靠交互记录 | DefaultAgentRuntime → ContextSessionService → JpaHistoryStore；RunPersistence创建/终态事务 | HarnessRuntimeTest、JpaHistoryStoreTest、原Run事务/恢复测试 |
| 增量窗口与检查点恢复 | WorkingContext、ContextSessionService有界缓存 | HarnessRuntimeTest、HarnessContextFlowTest |
| 容量触发与合法压缩切分 | ContextCompactor、CompactionPrompt、ResponsesCompactionModel、JtokkitTokenEstimator | ContextCompactorTest、分词/Responses适配测试、两次压缩主线 |
| 大输出附件及两级LRU | LocalArtifactStore、ArtifactMaintenance、ReadArtifactTool | 附件/工具主测试、ContextHistoryApiTest、主线范围读取 |
| 有限补写与零重放 | BoundedPersistenceRetry、OrphanCallRecovery、RunCoordinator/RunPersistence | 重试、故障/恢复及既有取消/SSE测试 |
| Responses协议与用量真伪边界 | OpenAiResponsesAgentModelClient、ResponsesDecisionMapper、ModelCallLedger | HTTP Mock、ContextHistoryServiceTest、主线用量断言 |
| Windows本地独立启动 | application-local-harness.yml、V7迁移、精确自动配置 | LocalStorageFileTest、LocalWebDependencyTest及默认应用启动 |

- 默认`gpt-6.1-sol`是用户指定且可配置的模型名称，未用付费请求验证供应商/账号可用性；离线测试不证明真实摘要语义质量、Prompt缓存命中率或计费收益。
- H2默认只供单进程本地使用。结构化历史保留，大附件达到配额后可清理，保留墓碑和当时预览，不承诺全部附件永久全文可读。
- 原公开finalAnswer仍受65,536字符边界；默认8次决策等循环策略留给H02升级。H03文件编辑、H04真实终端、桌面壳、安装能力及009前端合并均不在H01。
- 既有RAG门禁曾出现的两次失败已完整保留；后续聚焦/阶段/全仓门禁未复现，但其根因仍未确认。

### 提交与交付记录

| 边界 | 提交 | 信息 |
|---|---|---|
| Phase 1 / T001–T004 | a90ef97e2ec315fd57cbdda6b5b425014d2fbff4 | build(010): complete phase 1 - local storage baseline |
| Phase 2 / T005–T009 | b208bb06e729dbebd373a6033586de544b869750 | feat(010): complete phase 2 - context contracts |
| Phase 3 / T010–T053 | 481e399f8474f34fefe90ea32754689c2e9b2929 | feat(010): complete phase 3 - durable harness context pipeline |

Phase 4验收和审查已通过；阶段SHA将在提交成功后由最终状态文档补记。分支推送在最终状态提交后执行，以真实Git回执确认；不合并main、不创建PR或Release。
