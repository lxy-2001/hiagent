# H01 Artifact Storage / read-artifact Contract

## 1. 范围和归属

Artifact是大工具结果、日志/报告或必要快照的托管附件。H01只实现通用UTF-8内容能力，用确定性工具验收；不提前实现终端进程或任意工程文件操作。

ArtifactStore端口提供write/readRange/metadata；实现为agent-web的LocalArtifactStore，模型工具为agent-tool/ReadArtifactTool。scope来自ToolContext而非工具参数。ID为不透明标识，不接受绝对路径；用户只能读所属会话附件。不同会话不共享物理正文。

## 2. 工具契约

工具名read-artifact，READ_ONLY，默认允许策略必须显式注册。

输入：artifactId（必填）、offsetBytes（默认0，非负）、maxBytes（默认32768，最大65536）。输出：artifactId、state、content、actualOffsetBytes、nextOffsetBytes、eof、savedBytes、truncated、redacted。

UTF-8切分不得截断字符；非边界offset报INVALID_ARTIFACT_RANGE；成功读取返回nextOffsetBytes供下一次直接使用。结果额外受8192 Token及当前模型窗口限制，不能每次读一个64MiB文件；预览≤4096 Token。超过范围、未知、无权、EVICTED/MISSING明确返回失败，不用空字符串冒充成功。

当前Runtime对失败工具的终止策略保持；H02再决定可恢复反馈循环。重新运行原工具生成新输出不是恢复旧附件。

## 3. 保存

单件64MiB；超过只保存受限部分，标truncated及已保存范围/原总长度是否已知，不拆分绕过配额。真实执行参数不能在执行前因附件策略截短。保存前先验证状态/关联及敏感字段，不能把API Key/鉴权头当附件保存；脱敏须标redacted，RAG证据完整性继续遵守旧契约。

大内容用流式有界写入；H01现有String工具桥接不代表H04已具备流式进程采集。文件创建/修改结果仅有状态/路径时不重复回显全文。大Tool Call参数须在历史中保留真实内容或明确引用，不能伪造参数。

write顺序：预留配额/登记WRITING → 同托管目录临时文件写入并校验/落盘 → 同卷重命名 → 元信息确认AVAILABLE/TRUNCATED → 返回可用引用。失败保留可核对状态，不能先给模型一个声称全文已保存的ID。

## 4. 配额与并发

- 单会话512MiB，目标384MiB；全局5GiB，目标4GiB。两级均计算已保存、正在写入预留及尚未实际删除的正文，数据库/工程文件不计入。
- 单进程QuotaCoordinator使用一个短临界区协调预留、pin及淘汰领取；不能只“先查大小再无锁写”。实际I/O不持该锁或数据库事务，完成/失败再收敛计量。
- 会话超额只清理自身未保护附件；全局先处理已删除会话与可删临时文件，再按idle session.lastUsedAt/id和artifact.lastAccessedAt/id升序回收，释放到目标即停止，不要求删空整个会话。
- 全局跳过非终态Run所在会话（含等待审批）；会话内允许清理旧Run且当前不依赖的附件。任何清理都保护读写句柄、WRITING和当前Run pin，删除前复查，避免读删竞态。
- 真实touch/Run提交/用户主动读附件更新会话时间，附件读写更新自身时间；后台轮询/列表/扫描不刷新。时间持久化，重启按数据库记录恢复。
- 未到低水位但当前写入仍满足两级上限和实际磁盘空间，可继续。没有可删对象立即报告，不空转；瞬态删除失败使用既有4次/10秒策略，耗尽则ARTIFACT_STORAGE_FAILED，停止新动作。

## 5. 删除和恢复

标记DELETING再删正文，物理删除成功并确认后才释放计量、标记EVICTED。清理不删除消息、预览、checkpoint或执行事实。正文缺失标MISSING；读取已清理内容明确不可用。

启动时分页核对WRITING/DELETING、文件残留与字节计量，未完成收敛前禁止新内容写入；不执行历史工具。临时文件只能按元信息与命名归属识别，不能递归清理整个data-root。只清理经过真实路径校验的artifacts目录，拒绝越界、符号链接/junction替换，DB目录与用户工作区不可成为目标。

显式session DELETE：非终态Run返回409；空闲时标删除、隐藏普通查询并返回202，维护器有界清理后移除数据库会话记录。LRU永不调用此接口。重试删除是继续清理，不是再次运行任务。
