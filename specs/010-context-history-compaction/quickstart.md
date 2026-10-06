# H01 实施验证指南

日期：2026-10-07。本文说明实际实现的运行入口与验证安排；已执行结果以[verification.md](verification.md)为准，实施顺序和门禁见[tasks.md](tasks.md)。

## 1. 验证边界

- 使用Java 17、仓库Maven Wrapper、临时H2文件库/内容目录、可控时钟和HTTP Mock；不连接旧MySQL、不读取真实Key、不调用付费模型。
- 对新行为先写最小失败测试、确认失败原因，再实现并转绿。相同规则集中在一个主测试位置，其他层只验证接入；现有回归不删、不跳、不弱化。
- 新增测试文件按13个职责位置规划；数量不是硬指标，也不为每条FR、record、getter或表另建测试。若多个场景可参数化或复用现有Fixture，直接复用。
- 文件恢复采用关闭重开数据源/重建服务，失败用存储边界注入；不做压力/浸泡、逐指令杀进程、硬件断电、真实磁盘写满或大规模并发矩阵。

## 2. 主要验证位置

路径均相对仓库根目录；这些是目标位置，不要求每个子项成为单独测试方法。

| 计划新增文件 | 主要覆盖 |
|---|---|
| agent-demo/src/test/java/com/agentflow/demo/LocalStorageFileTest.java | 临时文件库迁移/JPA启动、提交回滚、关闭重开及一次短锁冲突 |
| agent-llm/src/test/java/com/agentflow/llm/JtokkitTokenEstimatorTest.java | 完整输入计数、默认272K配置及模型/编码改变后的重估 |
| agent-core/src/test/java/com/agentflow/core/runtime/HarnessRuntimeTest.java | 跨Run有效上下文、保存后才继续、有界加载、不重放；附件和压缩只做接入检查 |
| agent-web/src/test/java/com/agentflow/web/context/JpaHistoryStoreTest.java | 批次序号/幂等、分页、检查点CAS、必要provider续接的保存与恢复 |
| agent-demo/src/test/java/com/agentflow/demo/ContextHistoryApiTest.java | 消息/上下文/touch/附件/删除接口；正常返回及代表性权限/状态错误 |
| agent-demo/src/test/java/com/agentflow/demo/LocalWebDependencyTest.java | Redis不可达时真实登录/刷新/退出/重开撤销；代表性429、503，不Mock安全过滤器 |
| agent-web/src/test/java/com/agentflow/web/artifact/LocalArtifactStoreTest.java | 正文范围、截断、小配额LRU、活动保护、缺失/写失败/路径边界及一个短并发场景 |
| agent-tool/src/test/java/com/agentflow/tool/ReadArtifactToolTest.java | 范围/归属/不可用的工具结果映射 |
| agent-core/src/test/java/com/agentflow/core/context/ContextCompactorTest.java | 软阈值/硬上限、工具配对、20%目标、连续压缩、失败保留旧窗口；Prompt结构/信任边界 |
| agent-web/src/test/java/com/agentflow/web/context/BoundedPersistenceRetryTest.java | 暂时恢复、耗尽、不可重试、取消/到期；4次总尝试和同一写入补确认 |
| agent-llm/src/test/java/com/agentflow/llm/OpenAiResponsesAgentModelClientTest.java | 一套HTTP Mock覆盖主调用/摘要、text+call、续接、失败取消、usage缺失/0/子项 |
| LocalStorageFileTest、AgentWebAutoConfigurationTest、AgentLlmAutoConfigurationTest及ContextHistoryServiceTest | 默认Demo完整启动、Bean退让/缺能力失败及真实/未知用量汇总，复用现有测试位置 |
| agent-demo/src/test/java/com/agentflow/demo/HarnessContextFlowTest.java | 下节唯一完整端到端主线 |

复用已有ModelDecisionContractTest、ContextContractTest、CoreDependencyBoundaryTest、RunFinalTransactionTest、RunPersistenceRecoveryTest、AgentLlmAutoConfigurationTest、StarterOverrideTest及agent-eval/ContractTest。现有取消、审批、SSE、RAG与资源释放测试正常参加模块回归；只有本次变化带来的实际缺口才补断言，不再复制同义套件。

## 3. 一条完整端到端主线

在HarnessContextFlowTest中用真实持久化/装配和受控模型，连续走完：

1. 创建会话并执行第一Run，生成可见工作文本、工具调用、大结果附件及最终回答；检查消息关联和附件引用。
2. 第二Run读取已确认历史；用小测试窗口触发两次压缩，检查覆盖范围及当前有效上下文，不拆工具调用组。
3. 关闭重开数据源和服务，从检查点加尾部恢复；读取有效上下文和一段附件，核对没有重复加载覆盖原文、没有再次执行旧工具。
4. 对实际工具调用次数和主请求/摘要用量做整体核对，确认各模块接通。

默认272000窗口、容量不可行、存储故障、LRU、越权/删除等边界在各自主测试验收，不全部重复成E2E。离线摘要样例证明算法及协议边界，不能证明真实模型语义不遗漏。

## 4. 怎样运行，避免重复

实施单项任务时只跑对应测试。例如US2算法：

~~~powershell
./mvnw.cmd -pl agent-core -am "-Dtest=ContextCompactorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
~~~

此选项只允许依赖模块没有同名定向测试，不允许目标测试漏跑；需核对目标模块实际执行数。Phase结束时执行tasks中对应模块门禁并记录结果。若同一代码状态已有相同范围的完整证据，直接复用；不能用一个定向用例代替模块门禁。

最终只执行一次全仓验证：

~~~powershell
./mvnw.cmd clean verify
~~~

这是Phase 4同一次门禁，不需要再按本文把所有定向命令重复执行一遍。新修改、失败或未决问题才需要重跑受影响检查。测试发现缺陷时保留失败证据并修复，不能减少断言制造通过。

## 5. 接口和证据

新增操作见[OpenAPI](contracts/openapi.yaml)：消息分页、上下文诊断、会话touch、显式会话删除、附件范围读取。任务GET、步骤GET和SSE继续各司其职。

将命令、实际执行数/退出码和场景结果记入[verification.md](verification.md)。重点记录加载范围、检查点覆盖、压缩前后Token、真实工具次数、附件状态、保存尝试次数和终态通知顺序；不含密钥、真实模型响应或隐藏推理。断言属于协议、持久化、算法还是整体接入应清楚，避免不同层重复证明同一事实。

真实模型验证不是默认门禁。仅在用户后续授权后单独记录供应商/模型/日期、真实usage和限制；Mock不能证明账号可用、摘要质量或缓存折扣。

## 6. 完成标准

完成任务并记录证据 → 全仓verify → 实现后speckit-analyze无未解决HIGH/CRITICAL → speckit-converge无剩余工作 → 更新路线图和验证记录 → 阶段及最终文档提交 → 推送Feature分支。当前实施状态和已执行验证以verification记录为准。

## 7. 本地启动与模型配置

Demo默认profile是local-harness，H2文件库与托管附件默认放在 `%LOCALAPPDATA%/HiAgent`。可以用 `agentflow.harness.storage.data-root` 指定独立数据目录。生产路径必须配置真实模型Key和至少32字节的本地JWT密钥；缺少必需配置时明确启动失败，不选择Fake。

~~~powershell
$env:AGENTFLOW_JWT_SECRET = "<随机生成且至少32字节的本地JWT密钥>"
$env:AGENTFLOW_HARNESS_API_KEY = "<你的Key>"
$env:AGENTFLOW_HARNESS_BASE_URL = "https://api.openai.com/v1"
$env:AGENTFLOW_HARNESS_MODEL = "gpt-6.1-sol"
./mvnw.cmd -pl agent-demo -am package -DskipTests
java -jar agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar
~~~

`gpt-6.1-sol`只是用户指定的默认配置字符串；实际服务需支持该模型及Responses工具协议，测试不证明账号或模型可用。自建OpenAI兼容服务可更改base-url和model；模型窗口/编码需配套配置。离线端到端Fixture只存在测试源码，用于证明上下文、存储和恢复流程。
