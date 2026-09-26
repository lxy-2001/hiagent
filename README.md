# HiAgent / AgentFlow Java

一个 Java 17 Agent 后端：自研决策循环、工具执行、上下文预算、确认记忆、可验证引用、MCP 审批与离线评测；通过轻量 Spring Boot Starter 按需接入。

项目边界是单进程、可运行、可测试、可讲解的 Agent。没有分布式故障转移、自动重放外部写入或企业级 SLA 承诺。

## 入口一：无真实凭据的离线验收

需要 Java 17、PowerShell 7 和 Node.js。Maven Wrapper 固定 3.9.16，Spring Boot 4.1.1。首次构建可能下载依赖；不需要填写 `.env`，不需要 Docker 或付费模型。

在仓库根目录执行：

```powershell
.\mvnw.cmd clean verify
npm --prefix agent-demo test
pwsh -NoProfile -File scripts/verify-consumers.ps1
pwsh -NoProfile -File scripts/verify-delivery.ps1
```

- `verify-consumers.ps1` 本地安装当前 Maven 构件，再把六种消费者分别复制到新临时目录，实际构建、调用并检查依赖隔离。结果在 `.ua/008-consumers/`。
- `verify-delivery.ps1` 按白名单复制必要源码到新目录，运行五条演示流程、26 个评测场景各三遍和前端测试。结果在 `.ua/008-delivery/`，包含逐文件来源校验与原工作区 dirty 信息。
- 这些结果标为 `OFFLINE_FIXTURE`。它们证明运行机制，不代表真实模型准确率或生产性能。测试只操作自身随机端口、内存数据库和回环服务。

手动打开演示页面，见[离线演示](docs/demo.md)。评测与窗口退化对比，见[评测说明](docs/evaluation.md)。

## 入口二：配置真实服务的 Demo

完整 Web 使用 MySQL、Redis、JWT 和登录账户；真实模型需要兼容接口及密钥。默认 RAG 还需要已导入的语料快照、Embedding 和 Qdrant；MCP 仅连接显式配置的服务名单。

```powershell
.\mvnw.cmd clean package -DskipTests
java -jar agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar --spring.config.additional-location=file:E:/hiagent-private/real.yml --server.port=18083
```

先按[配置说明](docs/configuration.md)准备本机私有配置，再执行启动命令。`package -DskipTests` 只是打包，不能代替验收。Compose 自动读取项目 `.env` 仅用于容器配置，不会把变量自动传给另行启动的 Java 进程。不要覆盖已有 `.env`、停止现有应用或删除数据库卷。

## 模块与按需依赖

| 模块 | 责任 |
|---|---|
| agent-core | 纯 Java 决策循环、Tool、预算、上下文、引用与取消语义 |
| agent-tool | 工具注册和应用侧策略装配 |
| agent-spring-boot-starter | core/tool/Boot 基础依赖和可覆盖 Runtime 装配 |
| agent-llm | OpenAI Chat Completions 兼容模型适配 |
| agent-rag | 本地语料快照、混合检索和 knowledge.search |
| agent-mcp | MCP 双版本协商、受限 Schema 与内部工具映射 |
| agent-web | 身份、Run 持久化、会话、审批、SSE |
| agent-eval | 旁路评分、报告、来源与比较；Demo 仅测试依赖 |
| agent-demo | 组合根、页面和演示；Fixture 仅测试类路径 |

轻量用户引入 `com.agentflow:agent-spring-boot-starter:0.1.0-SNAPSHOT`，自行提供 `AgentModelClient` 或增加所需模型适配。无模型且未提供完整 Runtime 时启动失败。完整 Web 保留原有传递依赖，不宣称无需数据库。

[架构与调用顺序](docs/architecture.md) · [六种接入及覆盖矩阵](docs/integration.md) · [交付检查](docs/delivery-checklist.md) · [简历证据与限制](docs/portfolio-evidence.md)

## 交付边界

本地构件坐标已用于仓库外消费者验收。Maven Central、GitHub Release、标签和站点均为 `NOT_PUBLISHED`。007/008 本轮未执行真实付费模型或第三方服务实验；Microsoft Learn 的受限 Schema 不兼容仍是已知限制。分支交付不代表已经合入 main。

开发遵循按 Feature 的 SDD 和阶段测试/提交。设计工作文件 `specs/`、`.specify/` 是本地资料，公开运行说明不依赖它们。
