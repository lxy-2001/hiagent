# H01 Configuration / Assembly

以下为Demo Harness模式默认值；复用库不因引入Starter而强制启用本地存储。全部在启动时校验，Run开始冻结模型/容量快照；运行中切模型仍不支持。

| 配置键 | Demo默认值 | 约束 |
|---|---|---|
| agentflow.harness.enabled | true | 库消费者默认false/显式启用 |
| agentflow.harness.storage.data-root | %LOCALAPPDATA%/HiAgent | 可改盘；规范化绝对路径，不允许根目录/工程目录作为清理目标 |
| agentflow.harness.model.protocol | responses | 仅Agent Harness路径；旧chat/embedding端口独立 |
| agentflow.harness.model.name | gpt-6.1-sol | 配置字符串；不覆盖旧agentflow.model.chat-model供chat/embedding使用 |
| agentflow.harness.model.base-url | https://api.openai.com/v1 | 可配置Responses兼容服务；不自动沿用不支持Responses的地址 |
| agentflow.harness.model.api-key | 环境变量 | 不在源码、历史或日志保存实际值 |
| agentflow.harness.context.window-tokens | 272000 | 正整数，所有阈值与输出预留可满足 |
| agentflow.harness.context.compact-ratio | 0.80 | 0.80～0.85 |
| agentflow.harness.context.output-tokens | 32768 | 单请求，另受Run剩余输出预算约束 |
| agentflow.harness.context.safety-tokens | 8192 | 必须留出正的输入空间 |
| agentflow.harness.context.compacted-history-ratio | 0.20 | (0,1)，默认约五倍压缩目标 |
| agentflow.harness.context.summary-tokens | 8192 | 可见摘要上限，服从动态20%目标 |
| agentflow.harness.context.recent-tokens | 32768 | 尾部上限，保持完整调用组 |
| agentflow.harness.context.encoding | o200k_base | JTokkit实际支持的编码；估算来源可观察 |
| agentflow.harness.context.cached-sessions | 4 | 至少容纳实际活动worker；超出时拒绝/释放闲置项 |
| agentflow.harness.context.session-memory-bytes | 16777216 | 恢复前/追加时检查，16 MiB |
| agentflow.harness.context.total-memory-bytes | 67108864 | 64 MiB，不能按每线程分别无约束分配 |
| agentflow.harness.run.max-iterations | 8 | 保持当前策略，H02另议 |
| agentflow.harness.run.max-duration | 10m | 校验>0且≤1h；实际工具/模型/摘要不得超剩余值 |
| agentflow.harness.run.max-input-tokens | 3000000 | 累计，包括主模型/摘要；缺用量用保守预算估计并标来源 |
| agentflow.harness.run.max-output-tokens | 524288 | 累计，不把单次32768误当Run总额 |
| agentflow.harness.model.request-timeout | 120s | 每次取与Run剩余的较小值 |
| agentflow.harness.model.max-response-bytes | 8388608 | 8 MiB，超限明确失败 |
| agentflow.harness.persistence.max-attempts | 4 | 含首次；不可重试错误可提前终止 |
| agentflow.harness.persistence.backoff | 200ms,500ms,1000ms | 不无限重试、不持事务等待 |
| agentflow.harness.persistence.retry-window | 10s | 到期不再发起I/O，不承诺强杀阻塞系统调用 |
| agentflow.harness.artifact.max-file-bytes | 67108864 | 单件64 MiB，超出明确截断 |
| agentflow.harness.artifact.session-max-bytes | 536870912 | 512 MiB |
| agentflow.harness.artifact.session-target-bytes | 402653184 | 384 MiB，严格小于max |
| agentflow.harness.artifact.total-max-bytes | 5368709120 | 5 GiB，使用long并检查溢出 |
| agentflow.harness.artifact.total-target-bytes | 4294967296 | 4 GiB |
| agentflow.harness.artifact.preview-tokens | 4096 | 仍计入本次模型输入 |
| agentflow.harness.artifact.read-tokens | 8192 | 范围读取上限 |

max-file≤session-max≤total-max；target<max；配置变小触发有界清理，不能删除受保护内容。无按天TTL配置。数据库连接jdbc:h2:file:<data-root>/db/hiagent;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=2000，不开TCP/AUTO_SERVER，hibernate.ddl-auto=validate，Flyway沿用迁移链。正常退出关闭连接池。

## 装配职责

- AgentWebAutoConfiguration注册持久HistoryStore、ContextSessionService、LocalArtifactStore、维护器及HTTP适配；明确依赖EntityManager/DataSource，不用包扫描偶然发现。
- AgentLlmAutoConfiguration按harness.enabled及harness.model.protocol选择AgentModelClient，注册ResponsesCompactionModel、JtokkitTokenEstimator及续接端口消费者；用户同类型Bean优先。ResponsesContinuationStore的持久实现由Web提供，缺失时Harness启动失败。
- AgentRuntimeAutoConfiguration装配纯Java上下文服务和同一个DefaultAgentRuntime，不把JPA或算法写进Starter；旧构造器/显式瞬态模式有单独测试。
- AgentToolAutoConfiguration只在ArtifactStore可用时注册ReadArtifactTool，默认策略明确允许该READ_ONLY工具；自定义Registry由应用负责包含它，Harness启动须检查所需能力而非偷偷替换用户Registry。
- Demo负责H2依赖、数据目录和默认profile；不把H2硬塞进基础Starter，不改变RAG/MCP按需装配。禁用Harness仍可验证008既有集成契约。

## 兼容调整

ContextPolicy的131072、Estimator的64消息/262144字节、Normalizer/Runtime的8192字符、Coordinator的4096/2048及RunLifecycleProperties的120秒旧上限，均须在Harness路径实际解除并由上述边界取代。模型返回大于旧finalAnswer 65536字符时仍沿用公开答案边界，必须真实OUTPUT_TOO_LARGE；本Feature不偷偷改变该API上限。

API Key、JWT密钥继续使用环境变量或既有安全配置，不新增明文样例。summary/preview/日志过滤敏感信息，启动日志只打印非敏感有效参数。

## 本地Web运行依赖

现有AuthService、JwtBlacklistFilter和RateLimitFilter会写/读Redis。Harness不能只换H2却让所有HTTP请求继续依赖Redis，也不能跳过这些检查。agent-web内的WebRequestStateStore是本地与旧Redis的替换边界，提供访问令牌撤销写入/查询和限流计数；Core不感知它。

LocalWebRequestStateStore用H2的auth_access_revocation保存jti/到期时间，重启保留撤销；限流使用可控时钟和最多4096个未过期桶，沿用120次每IP每分钟、桶2分钟到期，只回收过期桶，满时429。计数是进程内短期防护，重启重置不改变JWT撤销事实。AuthRefreshToken的数据库记录仍是刷新令牌事实来源，登录不需要额外Redis提示键。

Harness装配选择本地实现且不发起Redis连接；旧消费者可显式使用Redis实现，保留原构造器兼容入口。数据故障503，不静默放行；新artifact路径也遵循统一401/429/503响应。LocalWebDependencyTest必须走真实安全过滤器、登录/刷新/退出/重启和限流，不用Mock安全Bean掩盖外部依赖。
