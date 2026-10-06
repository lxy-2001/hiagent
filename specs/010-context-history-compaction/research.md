# H01 Research / Decisions

日期：2026-10-06。源码基线：fb3de112eb220a743232fdad1af710c092826540（008分支）。这是实现前研究，不是Feature验收。无未解决的行为澄清项。

## R01 本地存储：H2文件模式，复用JPA/Flyway

**决定**：H2 2.4.240，MODE=MySQL，数据库位于data-root/db/hiagent；同一数据目录仅一个进程，禁用AUTO_SERVER/远端访问，关闭连接时正常提交/回滚。H2运行时依赖进入Demo，Web继续依赖JPA抽象。V1–V6不可变，V7追加。
**理由**：现有代码依赖EntityManager、事务与PESSIMISTIC_WRITE，测试已使用H2；引入SQLite需要重核方言、锁和迁移，没有本Feature收益。不再默认要求独立MySQL服务，也不迁移旧数据。
**证据**：根POM为Boot4.1.1，缓存BOM固定H2 2.4.240/Flyway12.4.0；本轮临时文件库顺序执行6份原SQL、重开连接读取提交数据、双连接行锁阻塞及回滚后写入，得到H2_FILE_PROBE_OK migrations=6 reopen=true rowLock=true rollback=true publicTables=14。没有访问旧项目数据库。
**限制**：探针通过不证明Hibernate实体校验、Flyway元数据、应用启动或异常断电恢复；tasks必须补这些范围中的文件重启、事务、迁移集成测试。H2不是对MySQL全部行为的保证。

## R02 Runtime：复用循环，替换上下文接缝

**现状**：DefaultAgentRuntime逐轮调用ContextAssembler；AgentExecutionContext仅保存本Run的user/tool链。PersistentContextSource只取最近20个成功问答，ContextPolicy硬上限131072，Utf8TokenEstimator有64消息和262144文本边界。RunCoordinator又用4096/2048累计预算。
**决定**：新增纯JavaContextSession/WorkingContext，由现有循环调用；可靠HistoryStore替代Harness路径的成功问答Source。保留旧构造器/瞬态路径用于库兼容，Demo Harness模式必须配置真实存储。所有旧限制需实际移除或在明确旧模式中保留，不能只改显示配置。
**拒绝**：第二套HarnessRuntime、全库加载、每轮重新摘要、把Step/Event当消息来源。

## R03 Responses：完整有效窗口 + 适配器续接信息

**现状**：OpenAiCompatibleModelClient.completeAgent仍请求/chat/completions，ProviderDecisionMapper只支持旧决策格式。模型预设不等于协议已支持。
**决定**：新增Responses适配器，保留旧chat/embedding端口；store=false、parallel_tool_calls=false，发送当前有效窗口，不接previous_response_id或服务端自动压缩。本地检查点为唯一恢复依据。严格保留message/function_call/function_call_output关联；必要的encrypted continuation项由适配器端口保存和重放，Core只持有不透明引用，不提取隐藏推理。
**注意**：同一响应可能有文本与工具调用，不能只取output_text。保留message.phase等适配信息；普通文本不自动视为最终完成。一次多个工具调用在H01明确拒绝，不偷偷执行部分。完整/incomplete/refusal/error分开，usage即使失败也按真实返回记一次。
**边界**：默认gpt-6.1-sol是用户指定；账户/代理是否提供它须实际连接验证。不把公开模型能力当成本项目已联调结果。

## R04 分词和预算：可配置本地估算

**决定**：agent-llm新增JTokkit1.1.0适配，encoding显式o200k_base；对稳定文本和规范化工具JSON缓存计数，增加协议开销和8192安全余量。模型切换使计数缓存失效。o200k_base作为项目估算配置，不声称已证明等同gpt-6.1-sol内部计费分词；opaque续接量按可用usage/保守上界计入，无法估算时不能当零。
**比较**：逐轮调用远端计数接口增加请求依赖，不作为默认；继续字节数代替Token会浪费大窗口；自研tokenizer无学习收益。官方容量拒绝仍须处理，不能无上限重试。
**预算**：窗口与Run总量独立；摘要使用同一模型、同一Run用量账本与剩余时间。主决策单次上限32768，Run初始输入3000000/输出524288/600秒/8轮；这些是可配置防护参数，不是自动扩大循环能力。

## R05 历史的确认不等于副作用原子提交

**决定**：用户输入、模型批次、执行意图、结果和终止事实都有稳定mutationId与payload摘要；数据库写入使用短事务，重试每次新事务。相同键不同内容报冲突；提交确认丢失先查询同键，不能生成新键重插。写入最多首次+3次，200/500/1000ms退避，10秒窗口，不可重试错误提前终止。
**边界**：不能把文件/外部工具与数据库组成原子事务，也不承诺exactly-once。调用意图已确认而结果缺失时，显示UNKNOWN且不自动重放。Tool结果写入失败后不能请求模型继续新动作；错误报告和资源释放仍可执行。
**可靠性区别**：现有RuntimeTrace吞掉观察异常可以保留；可靠历史端口不可吞错。终态必须沿用RunControl唯一提交/释放条件，不另造独立生命周期。

## R06 Artifact与最近使用淘汰

**决定**：内容UTF-8文件与元信息分离，不同会话不做共享去重，简化归属与删除。单件64MiB、会话512MiB/目标384MiB、全局5GiB/目标4GiB；数据库不在附件配额内。删除会话与临时文件优先，其后按会话lastUsedAt、附件lastAccessedAt由旧到新。
**并发**：短临界区预留/归还配额和领取清理对象；实际大文件I/O不持数据库行锁。全局淘汰跳过未结束Run，会话内保护当前依赖/读写对象；删除前再查保护条件。配额包括写入预留和待删正文，实际释放后才扣减。只读分页/SSE不能把所有会话刷新成最近使用。
**恢复**：WRITING/DELETING有可恢复状态；缺失文件标MISSING而非返回空成功。只清理托管目录，保留原消息、预览和墓碑；当前文件重新读取不能冒充旧附件。

## R07 压缩检查点

**决定**：本地摘要请求，不调用供应商托管compaction接口。程序选定覆盖前缀，模型只返回摘要；返回后检查状态、格式、大小并原子保存检查点和active指针。摘要+尾部≤原有效历史20%，重复压缩输入为旧摘要+新变旧前缀，不重新加载旧原文。
**Prompt**：见prompts/context-compaction-v1.md，保护用户目标、约束、失败、未知结果和引用的信任级别。不用摘要判断测试是否通过，不建立语义Judge。
**失败**：一次初次请求+一次有界修正；不能切坏工具配对、截短实际参数或启用未提交摘要。语义保真通过回归样例验证，不宣称模型摘要绝不遗漏。

## R08 公开接口与模块

增加消息历史/上下文诊断/附件范围读取、显式touch与session删除；保留原tasks/steps/events/turns和认证。touch用于准确LRU；普通GET不产生后台活跃假象。DELETE运行中会话返回409，空闲会话先标删除后自动清理；它不是LRU删除会话的入口。
Core服务留agent-core；provider与tokenizer留agent-llm；持久化/文件适配暂放现有agent-web；ReadArtifactTool留agent-tool。为本地文件存储新建Maven模块会增加当前装配成本，暂不迁移模块。

## R09 本地可运行的Web依赖闭合

**源码事实**：AuthService.issueTokens写Redis刷新提示键，JwtBlacklistFilter每个带jti的请求查Redis，RateLimitFilter对/api请求做Redis计数；不可达时任务/会话接口503。只迁H2不足以实现本地无外部数据服务。ShortTermMemory默认已是InMemory实现，不需新改造。
**决定**：在Web适配层引入小型WebRequestStateStore替换点。本地模式用H2持久化访问令牌撤销、用有界内存保持现有限流规则，refresh token仍由旧数据库表维护；旧非Harness路径保留Redis实现。JWT/所有权、拒绝和退出语义不削弱，不扩展登录产品或RBAC。
**验收**：LocalWebDependencyTest在Redis不可达时走真实安全链，验证登录、刷新、撤销跨重启、限流429和本地数据库故障503；不能以@MockBean替换过滤器后宣称独立运行。

## 一手资料（本轮读取；链接可变化）

- H2文件/事务/锁与兼容模式：`https://h2database.com/html/features.html`
- Responses工具调用与call_id：`https://developers.openai.com/api/docs/guides/function-calling`
- 推理模型的续接与encrypted_content：`https://developers.openai.com/api/docs/guides/reasoning`
- 用户指定模型官方页：`https://developers.openai.com/api/docs/models/gpt-6.1-sol`
- JTokkit库、编码与Java兼容性：`https://github.com/knuddelsgmbh/jtokkit`

公开资料说明能力/协议；本项目默认值、配额、事务边界和类设计是HiAgent的选择，不冒充行业唯一做法。
