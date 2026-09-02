# AgentFlow-Java（HiAgent）

HiAgent 是一个面向学习、面试讲解和简历展示的 Java Agent 后端项目。重点是实现并证明
Agent 的运行机制，同时把模型、Tool、RAG、MCP 和 Spring Boot 集成设计成可替换模块。

> Feature 001 已完成本地验收：Phase 1～6 均已验证并提交；分支推送后仍需核对 GitHub
> Actions 的远端结果。

## V1 要证明的能力

V1 将形成一条可以运行和解释的完整链路：

- 自研的 Agent 决策循环、状态转换、预算、取消和终止语义。
- 结构化 Tool 注册、Schema 校验、执行和结果回传。
- Run、Step、Event 以及支持晚订阅/重连的单机流式观察。
- Token 预算内的上下文装配、会话和受控记忆。
- 可回溯到真实文档片段的 RAG 与引用校验。
- MCP Tool 映射、高风险 Tool 暂停和人工审批。
- 离线 Agent 评测、运行轨迹、成本和延迟比较。
- 可被其他 Spring Boot 应用按需引入和覆盖的 Starter/适配器。
- 一个最小 Demo，用于复现完整 Agent 场景和简历展示。

分布式任务、复杂权限、多租户、微服务、Kubernetes、高可用、完整 APM 和产品级前端
不属于 V1。它们只有在 V1 验证后确有价值时才进入 V1.1。

## 自研与复用边界

本项目自研 Agent 语义和策略，包括 Runtime 主循环、Tool 执行模型、上下文与记忆策略、
RAG 引用策略、MCP 映射、风险控制和评测逻辑。

本项目复用通用基础设施，包括 HTTP/SSE、JSON、Spring Boot 生命周期、模型厂商连接、
数据库驱动、Qdrant 客户端以及标准日志/指标传输。Spring AI 当前不属于已验证依赖；
后续只有在实际适配器和测试完成后才重新评估。

## 模块职责

| 模块 | 责任 |
| --- | --- |
| `agent-core` | 纯 Java Agent 端口、值对象和枚举；Runtime 当前暂在 agent-web，Feature 002 迁移 |
| `agent-llm` | 模型厂商连接到核心模型端口的适配 |
| `agent-tool` | 本地 Tool 和执行相关适配 |
| `agent-rag` | 文档、Embedding、检索和向量库适配 |
| `agent-web` | HTTP、SSE、取消和审批等传输边界 |
| `agent-demo` | 组合根和可运行演示，不承载可复用核心行为 |

跨模块 Bean 通过各模块自己的精确自动配置发现；不能依赖扩大根包扫描或手工导入内部
配置碰巧生效。最终 Starter 和可选适配器拆分在对应 Feature 中单独验证。

## 当前工程基线

| 项目 | 已验证值 |
| --- | --- |
| Java | 17 |
| Maven | Wrapper 固定 3.9.16 |
| Spring Boot | 4.1.1 |
| Maven 模块 | 6 个（core、llm、tool、rag、web、demo） |
| 本地全仓结果 | 最近一次实际结果以 [验证记录](specs/001-engineering-baseline-starter/verification.md) 为准 |

默认验证不需要真实模型 API Key、MySQL、Redis、Qdrant 或 Docker；测试使用 H2、Mock HTTP
和受控测试替身。首次运行 Wrapper 或依赖未缓存时需要访问 Maven Central 下载文件。

### 检查 Wrapper

```bash
./mvnw --version
```

应显示 Maven 3.9.16 和 Java 17。Wrapper 的发行包地址及 SHA-256 记录在
[Feature 001 验证记录](specs/001-engineering-baseline-starter/verification.md)。

### 唯一完整门禁

```bash
./mvnw -B -ntp clean verify
```

本命令是本地和 GitHub Actions 共用的验证入口。失败必须返回非零状态；不能用固定回答、
确定性向量、Noop 或其他占位结果把失败伪装成成功。

开发单个模块时可先运行：

```bash
./mvnw -pl agent-core -am test
./mvnw -pl agent-llm -am test
./mvnw -pl agent-web -am test
./mvnw -pl agent-demo -am test
```

这些聚焦命令不能替代 Phase 或 Feature 收尾时的完整门禁。

## 当前 Demo 状态

Demo 的完整上下文测试使用 H2 和测试配置验证 Web 入口、Repository、Runtime 及现有适配器
的装配。真实运行 Demo 还需要根据环境提供数据库、缓存、模型和安全配置；仓库不提供可直接
使用的 API Key、JWT Secret、数据库密码或固定管理员账号密码。当前 HTTP 操作是临时 Demo
快照，不代表 V1 最终公开 API；稳定 Run/SSE 契约由后续 Feature 定义。

## 规划与开发方式

- [项目宪法](.specify/memory/constitution.md)：不可违反的工程原则。
- [项目路线图](specs/ROADMAP.md)：V1 目标、Feature 顺序和延期范围。
- [SDD 使用说明](specs/README.md)：从需求到计划、任务和验证的流程。
- [AGENTS.md](AGENTS.md)：实施范围、测试、Git 提交和推送规则。
- [Feature 001 文档](specs/001-engineering-baseline-starter/)：当前规格、计划、任务和证据。
- `specs/_project/`：早期蓝图，仅作历史参考。

每次只实施一个 Feature：

`specify → clarify → plan → tasks → analyze → implement → verify/converge`

后续 Feature 必须先更新其规格和任务，不得把未验证的能力写成当前基线。
