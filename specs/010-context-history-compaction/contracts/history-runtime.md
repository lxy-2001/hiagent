# H01 Core / Runtime Contract

适用FR-001–016、FR-028–031。以下契约已在H01实现，验证记录见../verification.md；Core不得依赖Spring、数据库、JTokkit或厂商SDK。

## 1. 入口及兼容

- AgentRuntime.run(request,eventSink,options)保持；AgentRunOptions增加可选ContextSession及模型工作配置，提供原2/3/4参数构造器。
- Harness模式由Coordinator创建owner/session/run绑定的ContextSession。DefaultAgentRuntime沿用原循环；AgentExecutionContext委托其准备请求、确认消息及引用。旧库消费者不传该会话时使用显式瞬态路径，不能标记durable=true。
- 原ContextAssembler和ContextSource签名保留给兼容路径，Harness不再拼接其成功问答。新的TokenEstimator由适配器提供，UTF8旧实现不作为Harness静默fallback。
- ModelDecision保留旧实现，增加AssistantTurnDecision：decisionId、orderedItems、nextAction(FINAL/CONTINUE/TOOL_CALL)、usage、usageSource、nullable usageDetails、nullable continuationRef。最多一个调用；FINAL与调用不可同时成立，CONTINUE不等于新计划状态。
- ProviderToolNames继续做别名往返映射；历史和执行记录使用原注册名，不能把厂商别名当新工具。

## 2. HistoryStore端口

操作均传入受控scope、截止时间和CancellationSignal；不得由模型选择owner。

| 操作 | 输入/输出 | 原子性与限制 |
|---|---|---|
| loadCheckpoint | scope → 已提交Checkpoint或空 | 空表示确实没有；I/O异常不能伪装空历史 |
| readAfter | scope、after、until、limit≤128、byteBudget → 有序页 | 先元信息再有界正文，截止序号固定 |
| appendBatch | scope、mutationId、payloadHash、records → 已确认序号范围/revision | 同键同内容重复确认；异内容冲突；整批原子 |
| saveCheckpoint | scope、expectedRevision、coverage、summary → committed checkpoint | 覆盖必须是已确认合法前缀；插入与active指针同事务 |

禁止持数据库事务执行模型/工具或等待退避。持久化实现负责最多4次总尝试和同键补确认；Core只接收确认结果或稳定失败。

## 3. 一轮执行顺序

已提交USER_MESSAGE → 恢复/复用WorkingContext → 容量复查/必要摘要 → 模型请求 → 保存有序模型批次和用量 → 校验/审批 → 保存执行意图 → 实际工具调用一次 → 校验和外置大结果 → 保存TOOL_RESULT → 追加当前窗口 → 下一轮。

模型失败、取消、队列超时等也记录已知事实；未发生的操作不能补成成功。StepRecorder/EventSink仍是观察通道；它们失败不影响已有执行事实，但HistoryStore失败超过界限必须停止新动作。

工具执行前参数不截断。已完成调用组可在后续压缩中整体进入摘要；不能只把function_call参数改成附件ID而保留原结果，伪造模型曾发出的调用。

## 4. 压缩边界

输入计数=全部实际规则/工具/消息/长参数及必要续接开销。阈值=min(window×ratio, window-outputReserve-safety)，默认217600。当前规则、原始Run输入和确认记忆必须保持来源，摘要不提升成系统授权。

程序先固定摘要覆盖范围；H为当前可压缩有效历史Token，target=floor(H×0.20)。选取≤32768的最近连续尾部，再将可见摘要限制到min(8192,target-tailTokens)。不可拆工具组；若合法边界没有可行预算，明确CONTEXT_BUDGET_EXCEEDED。不可把目标20%当成无损语义保证。

摘要接口CompactionModel只接受CompactionRequest（受限源历史、固定栏目、目标Token、scope/cancel/deadline），返回CompactionResult（完成状态、摘要、用量）；不执行工具、不自行设置coverage。初次+最多一次修正。保存失败只补写同一个已生成摘要，不重新请求模型来“重试数据库”。提交完成才替换窗口；CAS冲突重读，不覆盖更新。

## 5. 失败与终态

| 情况 | Core/公开原因 | 行为 |
|---|---|---|
| 历史/检查点补写耗尽 | HISTORY_PERSISTENCE_FAILED | 停新模型/工具；保留已执行事实和未确认状态 |
| 摘要失败且旧窗口放不下 | CONTEXT_COMPACTION_FAILED | 不启用半成品，不无限压缩 |
| 无合法切分/硬容量超限 | CONTEXT_BUDGET_EXCEEDED | 发送前停止 |
| 所需附件保存仍失败 | ARTIFACT_STORAGE_FAILED | 不声称全文已存，不重跑工具 |
| 多调用、孤立结果等协议异常 | INVALID_DECISION / TOOL_RESULT_INVALID | 不执行部分调用；记录诊断 |
| Run时间/用量或取消 | 沿用TIMED_OUT/BUDGET_EXCEEDED/CANCELLED | 同一预算覆盖准备、摘要、退避与执行 |

HISTORY_PERSISTENCE_FAILED、CONTEXT_COMPACTION_FAILED和ARTIFACT_STORAGE_FAILED三个新增原因同步TerminationReason、RunTerminationReason及错误映射；CONTEXT_BUDGET_EXCEEDED等既有值不改名。专门捕获历史异常，不能被通用catch改成INVALID_INPUT。

最终模型批次保存后才返回AgentResult；终态事务同时写RUN_TERMINAL。数据库无法确认终态时沿用503/pending/维护补提交，不能先发布RUN_TERMINATED。RunControl的worker退出及名额释放规则保持。

## 6. 恢复与安全

最新检查点+未覆盖尾部是恢复单位；原消息分页查看。缺结果调用以RECOVERY_NOTICE/UNKNOWN转换为明确的不可确认工具响应，不能说“没有执行”。单纯打开会话、查询历史或恢复工作状态不触发工具/模型请求。用户明确发起新Run后，送模前的容量准备可以按压缩规则调用摘要模型；这不属于重放历史工具，旧工具仍不得自动执行。

当前Run pin依赖附件；闲置缓存按上限释放。单会话读写由已有Run准入串行化，查看/SSE可并发。协议payload及外部文本都不是权限来源；没有通用测试/完成裁判。
