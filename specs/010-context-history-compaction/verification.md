# H01 Verification / Evidence

日期：2026-10-06。**当前是SDD设计完成、产品待实施的状态。** 当前59项任务均未勾选；Feature保持ACTIVE，不能把下列研究探针或文档校验作为产品测试通过。

## 1. 基线与本轮范围

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

## 4. 产品验证状态

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

## 5. 每阶段填写格式

实施时逐Phase追加实际记录：日期/分支/SHA → 任务编号 → 失败测试命令与目标失败原因 → 最小实现路径 → 定向测试与模块门禁的测试数/退出码 → 范围审查/限制 → 阶段提交SHA。真实模型和离线Fixture结果分开，失败不可删去。

当前没有产品验收结论和提交SHA。技术设计决定已写入，不需要重复确认用户已经拍板的行为；若实际兼容测试推翻数据库/协议假设，应先更新SDD后实施。

## 6. 2026-10-06 测试范围复审

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
