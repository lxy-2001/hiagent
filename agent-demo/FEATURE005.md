# Feature005：可信检索与引用

Feature005 在现有 Agent 循环中加入 `knowledge.search`。模型选择检索后，Runtime 给真实片段分配当前 Run 的 `S1`–`S32` 编号；只有完整送达最终模型请求的片段才能被答案引用。成功答案及引用全文在同一个终态事务中保存，之后读取不依赖 Qdrant 或本地索引。

引用校验保证来源身份、内容摘要、编号和送达关系，不证明答案的每个断言在语义上正确。

## 离线验证

需要 Java 17、Maven Wrapper、Node。以下命令在仓库根目录按顺序运行，PowerShell 的 `-D` 参数使用引号：

```powershell
.\mvnw.cmd -pl agent-rag -am test
.\mvnw.cmd -pl agent-demo -am test
npm --prefix agent-demo test
.\mvnw.cmd clean verify
```

默认测试使用 H2、确定性模型、固定向量以及 loopback HTTP Fixture，不调用付费模型。`RagImportApplicationTest` 使用真实 CLI 配置、embedding/Qdrant HTTP 适配器和本地响应夹具完成导入、校验、激活和重复导入。`RagConversationEndToEndTest` 使用真实 HTTP、Runtime、事务和 SSE 验证成功引用、伪造拒绝、历史隔离、取消和降级。

`agent-rag/target/feature005-evaluation.json` 由测试实际计算并生成，包含 20 题的 gold、检索 Chunk ID 和 Recall@5。当前关键词及固定词项向量融合均为 1.0；固定向量不是商用 embedding。人工语义支持率模板位于 `agent-rag/src/test/resources/corpus/feature005/support-labels.json`，未执行真实模型实验时保持 `NOT_RUN`。

## 导入与配置

资料仅接受公开 UTF-8 `.md` / `.txt`。每文件最多 1 MiB、总计 20 MiB、100 个文件、10,000 个片段；默认按 800 码点切块、重叠 100。拒绝目录链接、无效编码和已知凭据模式。凭据检查不能代替人工确认资料可公开。

导入前停止使用同一 store 的应用。离线 CLI 不启动 Web、MySQL 或 Redis：

```powershell
.\mvnw.cmd -pl agent-rag -am install -DskipTests
.\mvnw.cmd -pl agent-rag dependency:build-classpath '-Dmdep.outputFile=target/005-classpath.txt'
$ragClasspath = 'agent-rag/target/classes;' + (Get-Content agent-rag/target/005-classpath.txt -Raw).Trim()
$env:AGENTFLOW_RAG_SOURCE_DIRECTORY = 'E:/public-java-docs'
$env:AGENTFLOW_RAG_STORE_DIRECTORY = 'E:/hiagent-data/rag'
$env:AGENTFLOW_RAG_EMBEDDING_SPACE_ID = 'public-java-v1'
$env:AGENTFLOW_RAG_QDRANT_BASE_URL = 'http://localhost:6333'
java -cp $ragClasspath com.agentflow.rag.cli.RagImportApplication --agentflow.rag.command=validate
```

`validate` 不访问模型或向量库。实际 `import` 会调用配置的 embedding 服务；在环境中设置 `AGENTFLOW_MODEL_EMBEDDING_BASE_URL`、`AGENTFLOW_MODEL_EMBEDDING_API_KEY`、`AGENTFLOW_MODEL_EMBEDDING_MODEL`、`AGENTFLOW_MODEL_EMBEDDING_DIMENSIONS` 后，把上面的 `validate` 改为 `import`。不要把真实 Key 放进文件或提交。

导入使用文件锁、内容身份和唯一 collection。向量数量及各 ID/hash 完整核对后，原子替换 active 指针。失败时报告是否已提交，不猜测或静默回退；显式 `cleanup-prepared` / `cleanup-retired` 只清理登记的非 active 资源。最多保留 active、一个准备快照和两个退役快照；清理前核对命令报告。

在线 Demo 还需要已有的 MySQL、Redis、JWT 和聊天模型配置。设置 `AGENTFLOW_RAG_ENABLED=true`、相同 store / profile，及 `QDRANT_BASE_URL`、可选 `QDRANT_API_KEY`。降级默认关闭，可显式设置 `AGENTFLOW_RAG_ALLOW_KEYWORD_FALLBACK=true`。语义空间、embedding 模型、维度或切块策略变化必须重新导入；服务启动后固定使用该快照。

旧 `/api/knowledge/reload` 认证后返回 410 `KNOWLEDGE_RELOAD_RETIRED`，不会导入；旧知识表与数据保留。

## HTTP 和演示

创建 Run：`POST /api/agent/tasks`，请求例如 `{"input":"取消何时释放执行槽？","requireEvidence":true}`。`requireEvidence` 缺省为 false，继续会话时添加 `sessionId`。

模型没有提供有效来源时，强制证据模式以 `INSUFFICIENT_EVIDENCE` 失败；伪造、畸形或未送达引用以 `CITATION_INVALID` 失败，不额外调用模型修复。检索失败保留 `RAG_*` 工具错误，单次检索超时不冒充整个 Run 超时。

终态后 `GET /api/agent/tasks/{taskId}` 返回 `requireEvidence` 和 `citations`。引用包含片段全文、相对路径、标题、快照/文档/片段身份、hash、码点范围。引用 JSON 上限 64 KiB；损坏存储返回 503 `CITATION_DATA_UNAVAILABLE`。四类 SSE 事件及终态负载不增加引用正文。新增终止原因需要严格枚举客户端同步升级。

页面使用纯文本展示来源，不执行片段 HTML，不自动打开来源 URL。历史答案中的旧引用只在模型上下文中中和，数据库原答案保持不变。

## 代码路径与实际验证边界

- `agent-rag/.../corpus/CorpusImporter`、`CorpusSnapshotStore`：完整导入和原子激活。
- `agent-rag/.../retrieval/HybridRagRetriever`：语义/关键词各 40 候选，RRF k=60，最多 8 个命中。
- `agent-core/.../rag/EvidenceLedger`、`CitationValidator`：每 Run 的绑定、精确送达匹配及最终校验。
- `agent-web/.../run/RunResultProjector`、`RunPersistence`、`CitationSnapshotCodec`：引用投影、终态原子保存、有界读取。

真实 Qdrant 和真实 embedding/模型尚未执行，不把 Fixture 结果当作服务或语义质量证明。对专用 Qdrant 1.13.4 实例，可显式运行：

```powershell
.\mvnw.cmd -pl agent-rag -am test '-Dtest=RealQdrantSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfeature005.realQdrant=true' '-Dfeature005.qdrantUrl=http://127.0.0.1:16333'
```

该测试创建并清理独立名称的测试 collection，使用确定性向量。默认不执行。
