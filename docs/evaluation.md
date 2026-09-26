# Agent 评测

评测运行真实 Runtime、工具执行、引用检查，以及公开登录/任务/审批/会话 HTTP 路径。默认模型与外部服务使用确定性测试夹具：成绩证明机制按约定运行，不证明模型智能，也不代表真实 Qdrant 或第三方 MCP 已验收。

## 离线运行

在仓库根目录使用 Java 17 和 Maven Wrapper；不需要填写 `.env`，也不需要启动 Docker。第一次构建可能下载 Maven 依赖。

```powershell
.\mvnw.cmd -pl agent-demo -am test '-Dtest=AgentEvaluationSuiteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dagentflow.eval.variant=baseline' '-Dagentflow.eval.repeat=3'
```

Linux/macOS 将 `mvnw.cmd` 换成 `./mvnw`。默认 repeat=1，允许1–10；验收使用3遍，共78条结果。普通 `clean verify` 也运行全部26个场景一遍。

控制台输出唯一目录：`agent-demo/target/evaluation/<evaluationId>/`，其中包含 `report.json`、`report.md`。报告先落盘，随后根据门禁返回退出码；错误、缺失记录或任一必需场景未通过都会使命令失败。不要用 Maven 的 JUnit 方法数替代报告中的场景数。

HTTP 夹具采用独立端口与内存 H2，真实登录；不会操作现有8080应用或本机数据库。首次框架预热上下文关闭后，每个场景重新创建资源；每场景18秒执行、2秒清理，预热计入整套十分钟上限。无法关闭的工作线程会停止后续场景并标记 NOT_RUN。

## 缩小窗口与比较

```powershell
.\mvnw.cmd -pl agent-demo -am test '-Dtest=AgentEvaluationSuiteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dagentflow.eval.variant=compact-context' '-Dagentflow.eval.repeat=3'
.\mvnw.cmd -pl agent-demo -am test '-Dtest=AgentEvaluationCompareTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dagentflow.eval.baseline=<基线report.json的绝对路径>' '-Dagentflow.eval.candidate=<候选report.json的绝对路径>' '-Dagentflow.eval.dimension=contextWindow'
```

候选只把上下文窗口从16384改成4096，不修改期望结果。C26必须检出预算退化；当前固定RAG场景C10–C12也会超过较小窗口。候选命令和比较命令返回非零是这个演示的预期结果，不应通过修改断言消除它。比较输出独立目录中的 `compare.json`、`compare.md`，不会再调用模型，也不会覆盖输入报告。

比较按 caseId+repeat 配对，要求相同数据、夹具、断言、模式与重复次数。只支持 code、prompt、policy、model、contextWindow 中一个声明维度变化。元数据不匹配返回 INCOMPARABLE；只换标签返回 NO_EFFECTIVE_CHANGE。延迟仅比较双方已知且口径相同的值。

## 怎么读报告

- caseStatus 是断言结果；预期被拒绝的 Run 可以是 FAILED，而 caseStatus 是 PASS。硬门禁不能由其它场景的成功抵消。
- steps、invocations、Run ID 与断言名用于定位；报告不输出用户输入、回答正文、工具参数或服务原始响应。
- C23 在 Runtime 启动前上下文加载失败，保留 HTTP recordingComplete=false 和空核心步骤；其通过证据来自连续、完整的公开生命周期事件，以及零模型/工具调用，不伪造核心终止步骤。
- usage 来源区分 REPORTED、FIXTURE、UNKNOWN；没有调用时为 NOT_APPLICABLE。缺少计数或异常请求不能当免费、不能零填充。
- runtimeTotal 的 CORE 与 WEB_ACTIVE 口径分开；queueWait、approvalWait、toolExecution、modelCall 单列。审批等待已包含在总时长中，不能再次相加。n<20 的分位数只是小样本描述，不证明性能提升。
- 只有所有调用有真实上报用量，并且价目快照与 provider/model/币种/日期匹配，才有模型费用估算；当前命令未提供价目快照，因此费用 UNKNOWN。Embedding、基础设施和最终账单不在估算范围。
- provenance 记录代码 SHA、工作区摘要、数据和有效配置指纹。dirty=true 表示本地未提交修改，不能称为干净提交的结果。Fixture 指纹包含实际执行脚本，禁止只换版本标签。

## 可选真实模型

只有显式同时使用以下两个开关才启用：

```powershell
.\mvnw.cmd -pl agent-demo -am test '-Dtest=AgentEvaluationSuiteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dagentflow.eval.mode=live' '-Dagentflow.eval.allow-paid=true'
```

仅从启动进程环境读取四项：`AGENTFLOW_MODEL_PROVIDER`、`AGENTFLOW_MODEL_BASE_URL`、`AGENTFLOW_MODEL_API_KEY`、`AGENTFLOW_MODEL_CHAT_MODEL`。不自动加载 `.env`，不要把密钥写到命令行或报告。缺少配置明确失败，不退回假模型。

真实模式只运行 C01/C02，工具仍是本地只读工具。整批最多5次请求、60秒，每次输出最多512 tokens，不自动重试；后续没有预算的场景记录 NOT_RUN。repeat最多3，可能因请求预算不足而故意无法完成所有重复。真实模型可能不遵守要求，评测应如实失败，不能保证得到 PASS。

默认测试只用本机回环服务验证此入口与限额；真实付费模型、外部 Embedding 与第三方 MCP 未在本 Feature 中运行。MODEL_LIVE 是执行模式字段，回环测试不作为真实模型能力证据。

## 契约与验证

稳定 JSON Schema 位于 `agent-eval/src/main/resources/evaluation/`。外部报告读取有10MiB大小上限、重复字段拒绝和闭合 Schema 检查。两份报告原子发布，不覆盖历史产物。

```powershell
.\mvnw.cmd clean verify
npm --prefix agent-demo test
```

`agent-eval` 是 Demo 的 test scope 依赖；生产 Demo JAR 不包含评测驱动或测试夹具。自定义驱动只能放在测试侧，不应给生产应用加假模型。干净副本的 `agentflow.eval.provenance` 选项需要逐文件核对来源 manifest，不能自行填写一个 SHA 冒充来源。
