# Quickstart: 验证工程基线与装配边界

**Feature**: 001-engineering-baseline-starter

**Audience**: 项目贡献者、评审者和面试演示者

本指南描述 Feature 001 实施完成后的验收方式。它不是当前仓库状态的通过声明；真实结果
将在实施阶段写入 `verification.md`。

## 1. 前置条件

- Git。
- Java 17 JDK，且 `java -version` 与 `javac -version` 均可执行。
- 首次运行时能够访问 Maven Central，以便 Wrapper 和依赖下载；缓存完成后验证不得调用
  模型或依赖 MySQL、Redis、Qdrant。

不要求预装系统 Maven，不要求 Docker，也不要求 OpenAI-compatible API Key。

## 2. 检查固定构建环境

在仓库根目录运行：

```bash
./mvnw --version
```

预期结果：

- Maven 版本为 3.9.16。
- Java 运行时为 17。
- Wrapper 从仓库配置解析版本，而不是依赖系统 `mvn`。

如果脚本不可执行，在 Linux 上先检查 Git 是否保留了 `mvnw` 的可执行位；不要改用系统
Maven 绕过版本门禁。

## 3. 执行唯一完整门禁

```bash
./mvnw -B -ntp clean verify
```

预期结果：

- 6 个 Maven 模块全部构建成功。
- 最终工程基线为 Java 17 与 Spring Boot 4.1.1；Spring AI 未实际适配，因此不声称已验证
  支持或兼容。
- 核心边界、自动配置、模型适配器、完整 Demo 上下文和临时 HTTP 入口测试全部执行。
- 不读取真实模型 Key，不调用付费模型。
- 不要求启动 MySQL、Redis 或 Qdrant。
- 任一模块失败时命令返回非零状态，不用本地答案、确定性向量或 Noop 伪装成功。
- 在依赖已缓存且未启动外部运行服务的正常开发环境中，记录统一门禁的墙钟耗时；耗时不超过
  600 秒才满足 SC-009，超过 600 秒必须记录为未通过并说明原因。SC-009 只判定耗时门禁，
  命令功能是否成功仍按 SC-001 等标准单独判定。

这也是 GitHub Actions 必须调用的完整命令。CI 不应维护另一套测试清单。

## 4. 聚焦验证

开发单个任务时，可以先运行受影响模块及其依赖；Phase 或 Feature 收尾仍必须扩大验证
范围。

### 核心依赖边界

```bash
./mvnw -pl agent-core -am test
```

预期证明 `agent-core` 生产代码没有 Spring、数据库、Redis、Qdrant、HTTP 客户端、模型
厂商或 Spring AI 类型依赖。

### 模型装配与失败语义

```bash
./mvnw -pl agent-llm -am test
```

预期覆盖三个模型端口的独立应用覆盖，以及缺 Key、Provider 错误、空内容和无效 embedding
响应；测试使用受控 Mock HTTP，不访问真实服务。

### Web 自动配置

```bash
./mvnw -pl agent-web -am test
```

预期证明 Controller、Service 和必要持久化组件通过精确自动配置加载，可替换 Bean 在应用
自定义实现存在时会退让，且不注册 Noop 生产实现。

### 完整 Demo 消费方

```bash
./mvnw -pl agent-demo -am test
```

预期证明：

- Demo 上下文使用 H2 和测试配置启动，不连接外部服务。
- Controller、Service、Repository、Runtime、模型端口、ToolRegistry、真实 Demo
  RagRetriever、StepRecorder 和 `InMemoryShortTermMemory` 均被发现且候选唯一。
- 11 个当前 HTTP 操作全部注册；Auth、Chat、Agent、Knowledge 四组至少各有一次受控基本
  分发验证。
- 这些操作明确标记为临时 Demo 快照，不形成稳定 API 承诺。

## 5. 人工审查点

自动化通过后，评审者还应确认：

1. `.github/workflows/verify.yml` 使用 Java 17 和同一个 Wrapper 命令，没有模型 secret 或
   外部 service container。
2. 每个模块只通过自己的 `AutoConfiguration.imports` 暴露精确自动配置，Demo 没有扩大
   根包扫描或手工导入模块内部配置。
3. 应用自定义 Bean 优先于框架默认 Bean；缺失真实能力时明确失败或保持未启用。
4. 当前 Runtime 缺少真实 `RagRetriever` 时装配明确失败，不注册 Noop；最终可选 RAG
   语义留给 Feature 005。
5. 生产源码与配置中没有固定 API Key、JWT secret、数据库密码或管理员默认密码。
6. README 和配置文档没有声称已经完成 Runtime 重构、正式 Run/SSE、可信 RAG、MCP、
   最终 Starter 或生产高可用。

## 6. 验收证据

Feature 实施完成时，`verification.md` 必须记录：

- 操作系统、Java 和 Maven Wrapper 版本。
- 完整门禁及各 Phase 专项命令的真实退出结果。
- 实际测试数量、完整门禁开始/结束时间、墙钟秒数、SC-009 判定和 GitHub Actions 链接。
- 6 模块、11 临时操作、覆盖退让、缺 Key 失败和核心纯 Java 边界的可观察证据。
- 未验证的内容和明确留给 Feature 002～008 的事项。

没有实际命令输出时，不得预填“通过”或把本指南当作验收结果。

## 7. 本 Feature 不验证的内容

- Agent Runtime 新状态机、预算、取消或 Tool Schema。
- 正式 Run API、可靠 SSE、晚订阅重放或多订阅者资源管理。
- 记忆策略、可信 RAG 引用、MCP 审批和评测体系。
- 最终可发布 Starter、真实基础设施兼容性、微服务、Kubernetes 或生产 SLA。

这些能力必须在路线图对应 Feature 中单独规格化、实现和验收。
