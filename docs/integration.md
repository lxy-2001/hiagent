# 按需接入与覆盖

当前坐标为 `com.agentflow:*:0.1.0-SNAPSHOT`，尚未发布 Maven Central。先在根目录 `./mvnw install -DskipTests`（Windows `mvnw.cmd`），再构建样例。本地安装只是构建准备。

| 组合 | 显式依赖 | 默认外部要求 | 验证样例 |
|---|---|---|---|
| pure core | agent-core | 调用者提供模型/Registry | samples/plain-java |
| Spring local | agent-spring-boot-starter | 调用者提供 AgentModelClient | samples/spring-agent |
| Spring LLM | Starter + agent-llm + Boot restclient | 兼容模型配置 | spring-agent 的 llm profile |
| Spring RAG | Starter + agent-rag | 自定义 RagRetriever 可无 LLM；默认实现还需 agent-llm 和检索配置 | rag profile |
| Spring MCP | Starter + agent-mcp | 显式服务名单、工具名单和本地策略 | mcp profile |
| full Web | agent-web | MySQL/Redis/JWT；可另加 MCP | samples/web-agent |

三个可选 profile 使用各自独立的测试源码目录，不要同时启用来代替隔离验收。样例主类位于 `example.hiagent`，没有扫描 `com.agentflow`。样例只消费已安装构件，不引用主项目源码或测试类路径。

```powershell
pwsh -NoProfile -File scripts/verify-consumers.ps1
```

脚本使用六个不同的新临时目录，各自 `clean verify dependency:tree`，保留退出码、目录和依赖树。纯 Java 不含 Spring；轻量 Starter 不含 Web、LLM、RAG、MCP、JPA、Redis。完整 Web 保留历史传递依赖兼容。

## Bean 覆盖

| 对象 | 默认提供者 | 覆盖行为 |
|---|---|---|
| AgentModelClient | 应用或模型适配器 | 无实现且无完整 Runtime 时启动失败 |
| AgentRuntime | Starter | 应用提供完整对象时默认退让 |
| ToolRegistry / ToolExecutionPolicy | agent-tool | 精确类型退让；默认策略 DENY |
| ToolExecutor / ToolResultNormalizer | Starter | 自定义对象参与实际调用，核心安全检查仍生效 |
| ContextAssembler / ContextPolicy / ContextTextPolicy / TokenEstimator / TimeSource | Starter | 单独可覆盖，不依赖 Web |
| StepRecorder | Web | 默认 Jpa；应用覆盖时使用该实例，非 Web 无默认持久化 |
| ContextSource / 确认记忆 / ShortTermMemory | Web 原配置 | 原有精确覆盖保留，三者职责不同 |
| RagRetriever | RAG | 自定义证据 Retriever 不加载默认快照/向量连接 |
| McpToolProvider / McpClientOperations | MCP | 沿原有覆盖规则；自定义客户端只允许明确的单服务组合 |

审批 gate 通过 RunOptions/Web Coordinator 传入；Starter 不安装自动批准器。多个未限定同类型模型不是“任选一个”，而是明确启动失败。替换 AgentModelClient 也不等于删除已选择 LLM 模块的 Chat/Embedding 端口；不需要这些端口时只选轻量 Starter。

可直接查看样例测试了解自定义模型和工具；它们显式使用合成输入，仅用于离线测试，样例生产主类不会自动注册 Fake。

完整 Web 样例复制当前公开 Flyway V1–V6 schema，测试仅通过 JDBC 和标准 PasswordEncoder 建立测试账户，其余动作走真实登录/Run/审批/步骤 HTTP。真实系统的账户供给由应用决定；无公开注册接口，也不建议绕过应用权限直接调用内部仓库。
