# H01 数据模型与状态不变量

日期：2026-10-06。本文件定义V7目标模型；尚无对应实现。继续使用现有agent_session、agent_task、agent_step、确认记忆、审批/调用表，不把它们改成消息历史表。

## 1. 标识、顺序与归属

- scope=(userId,sessionId)；runId仍为taskId别名。线程指session，不是Java工作线程。
- turnSequence沿用004，表示Run序号；新增recordSequence为session内严格递增的消息/事实序号；stepNo、eventId与recordSequence互不换算。
- mutationId是一次逻辑写入的稳定标识。重试使用原值及相同payloadHash；(sessionId,mutationId)唯一。批量写入占据连续序号，提交后才对恢复可见。
- 所有读取及写入都校验owner/session/run，不信任模型参数里的归属ID。UTC时间用于展示/LRU，顺序以序号和稳定ID裁决。

## 2. 表与字段（V7前向扩展）

| 表 | 关键字段/索引 | 用途 |
|---|---|---|
| agent_session_context | session_id PK/FK、last_record_sequence、active_checkpoint_id、context_revision、last_used_at、deleted_at | 序号分配、检查点CAS、LRU及删除可见性；不重用last_turn_sequence |
| agent_history_batch | id、session_id、mutation_id、payload_hash、first_sequence、last_sequence、committed_at；unique(session,mutation) | 幂等确认与整批提交；不能用唯一冲突直接冒充同内容成功 |
| agent_history_record | id、session_id、run_id、sequence、kind、role、occurred_at、decision_id、call_id、tool_name、status、inline_payload、payload_bytes、artifact_id、adapter_state_ref；unique(session,sequence) | 原始可见消息及执行事实；按序索引，正文与可分页元信息分开读 |
| agent_context_checkpoint | id、session_id、revision、base_checkpoint_id、covered_through_sequence、summary、summary_tokens、source_history_tokens、retained_history_tokens、tail_start_sequence、model_request_key、prompt_version、model_profile_hash、created_at；unique(session,revision) | 已提交摘要和覆盖范围；旧消息不删除 |
| agent_context_model_call | id、session_id、run_id、request_key、purpose、status、provider/model、input_tokens、output_tokens、cached_input_tokens、reasoning_tokens、usage_source、estimated_input_tokens、output_reserve_tokens、safety_tokens、estimate_source、source_history_tokens(nullable)、retained_history_tokens(nullable)、failure_code、started_at、finished_at；unique(run,request_key) | 主决策/摘要调用用量，每请求一次；未知列NULL |
| agent_artifact | id、owner_id、session_id、run_id、kind、state、relative_path、saved_bytes、original_bytes(nullable)、sha256、preview、truncated、redacted、created_at、last_accessed_at、deleted_at | 附件与可用状态；(session,last_accessed,id)索引；不暴露物理路径 |
| agent_responses_continuation | id、owner_id、session_id、run_id、provider/model、payload、payload_bytes、reasoning_token_hint、created_at | provider opaque续接信息；仅LLM适配器可解码，不返回公共接口 |
| auth_access_revocation | jti PK、expires_at；到期索引 | 本地模式持久保存访问令牌撤销，值不含原始Token；到期有界清理 |

正文使用可流式/受限读取的CLOB/TEXT，先检查payload_bytes和本轮预算再加载；避免JPA一次查询把整段会话CLOB全部实体化。外键/删除顺序须验证，不设置跨大表级联一把删除。

## 3. HistoryRecord种类

USER_MESSAGE、ASSISTANT_MESSAGE、TOOL_CALL、TOOL_EXECUTION_INTENT、TOOL_RESULT、RUN_TERMINAL、RECOVERY_NOTICE。

- ASSISTANT_MESSAGE与TOOL_CALL允许属于同一决策批次，保留供应商输出的相对顺序；可见工作文本不等于隐藏推理。
- TOOL_EXECUTION_INTENT表示获得执行条件并准备调用，不证明工具已经开始或完成。TOOL_RESULT保留原始SUCCESS/FAILED、稳定错误及附件引用。
- 崩溃后缺结果使用RECOVERY_NOTICE/UNKNOWN；这是Harness事实说明，不是伪造工具成功结果。生成合法模型工具响应时明确说明结果不可确认。
- RUN_TERMINAL记录已确认Run事实；取消前后实际已完成步骤不能删除。最终答案与用户输入继续由旧API按既有规则查询。
- 秘钥/鉴权头不入历史、日志或provider保存项；沿用敏感字段过滤。内容被脱敏/截断须标注，不能声称保存了未保存的原文。

## 4. 工作上下文（内存，不是另一份事实库）

WorkingContext保存scope、profileHash、checkpoint、未覆盖有序消息、Token计数及引用保护集合。普通追加不重新读全库；恢复使用最新检查点和尾部，分页最多128条，单工作集16MiB、全局最多4缓存/64MiB。

规则/工具定义保持稳定，当前用户请求、确认记忆按权威记录提供；旧摘要不能覆盖新输入。模型切换重估容量和协议，不直接复用旧provider opaque状态。不同模型需要不同token缓存键。闲置缓存可释放，磁盘记录仍保留。

## 5. 事务和不确定结果

1. createQueued：沿用session行锁，插入task及USER_MESSAGE/批次；整体提交后才能入队。相同任务补确认不重复增加turn或record序号。
2. appendBatch：短事务锁session_context行，核对mutationId/hash，分配并插入连续记录，更新revision；事务外执行有界重试，每次使用新事务。
3. saveCheckpoint：检查expectedRevision、旧checkpoint及覆盖前缀均匹配，插入新checkpoint并更新active指针，同事务提交；CAS失败重载，不能覆盖新历史。摘要模型不决定sequence。
4. complete：在已有任务终态事务写RUN_TERMINAL，保持已确认终态不覆盖；先统一锁顺序再修改，涉及多个锁时固定session→session_context→task→明细。只需要task的既有路径可以只锁task，但不得随后反向获取session/context；新增删除/准入路径遵循同序。
5. 不在数据库事务内等待模型、执行工具、睡眠退避或写大文件。执行意图、真实外部操作、结果确认之间并非原子事务；重启时UNKNOWN不能自动重放。
6. 所有未知提交先按原mutationId查询。得到相同hash视为已确认，不同hash为冲突；查询也失败时保持未确认并停止新动作。

## 6. 附件与续接生命周期

附件状态：WRITING → AVAILABLE/TRUNCATED → DELETING → EVICTED；文件丢失为MISSING，写入失败为WRITE_FAILED。墓碑保留必要引用、预览和执行事实；会话显式删除最终可清理其数据库记录。

WRITING先有元信息/配额预留，内容写同目录临时文件，校验长度/Hash并完成重命名后确认AVAILABLE。失败不能先公布可读引用。DELETING直到文件实际删除才释放字节计量；数据库确认丢失由启动核对收敛，不能反向重做原工具。

provider续接payload是不可解释的协议材料，有8MiB单响应上限，不作Prompt正文或前端“思考”内容。只保留恢复当前有效窗口所需的续接材料；检查点覆盖后可安全回收不再需要的provider载荷，原始可见消息保留。它不借附件LRU删除仍必需的续接项。

## 7. 会话删除与最近使用时间

显式DELETE时若session存在非终态Run则409。空闲会话先标deleted_at，所有普通所有权查询立即隐藏；后台有界删除文件后，按依赖顺序清除消息/检查点/provider项/旧任务明细及session，保留清理操作的最小恢复状态直到完成。失败可重试，不报告已经物理清完。

用户touch、提交Run、主动附件读取刷新session.last_used_at；成功附件读写刷新last_accessed_at。轮询/列表/SSE/扫描不刷新。全局淘汰按session.last_used_at,id，再按artifact.last_accessed_at,id；缺失时间用创建时间；数据库保存排序信息，重启不把全量历史改成最近使用。

## 8. 用量与状态展示

cached_input_tokens和reasoning_tokens分别是输入/输出中的子项，不再加到total。未报告字段为NULL，明确零保留为0。主请求和摘要请求按request_key只汇总一次；完整性标志usageComplete独立于业务是否成功。若存在未知用量，公开诊断给knownUsage及usageComplete=false，整体actualUsage=null；不能用估算冒充实际账单。

模型容量计数与provider实际用量分开；失效附件仍可能有原预览参与历史上下文，但引用不可读必须明确。数据库失败不自动变为“测试失败”或任务语义判断。

## 9. 本地Web依赖适配

AuthRefreshToken仍由既有数据库表保存和校验，不让未使用的Redis刷新提示写入成为登录前置。访问令牌撤销使用上述H2表，重启不能丢失未过期撤销记录；JWT签名、到期、所有权和刷新/退出语义保留。限流沿用当前每IP/每分钟120次的规则，以单进程有界桶实现，最多4096个未过期桶、2分钟到期；只淘汰到期桶，满时明确429，计数可在进程重启后重置。数据库不可用返回503而非放行。旧非Harness路径可以保留Redis适配，不以Noop绕过安全检查。
