# Research: 工程基线与 Starter 边界

**Feature**: 001-engineering-baseline-starter

**Date**: 2026-09-01

**Status**: Complete

本文件记录 Feature 001 的技术决策依据。它解决规划阶段的兼容性、构建、自动配置、
失败语义和测试边界问题，不扩大 Feature 的产品范围。

## 1. 当前基线审计

### Findings

- 仓库包含 6 个 Maven 模块；当前基线为 Java 17、Spring Boot 3.5.14、Spring AI 1.1.6
  和 springdoc-openapi 2.8.14。
- 当前在依赖已缓存时以离线模式执行 `mvn test` 成功，共运行 11 个测试；`agent-core`
  与 `agent-rag` 当前没有测试，因此现有成功结果不能证明核心依赖边界或 RAG 自动配置语义。
- Demo 当前有 11 个 HTTP 操作，分为 Auth 4 个、Chat 2 个、Agent 4 个、Knowledge 1 个。
- `agent-web` 的精确自动配置修复方向正确，但部分可替换 Bean 仍依赖 `@Primary`，没有对
  每个应用覆盖点证明默认实现会退让。
- `OpenAiCompatibleModelClient` 同时实现三个模型端口。应用只覆盖其中一个端口时，另外
  两个端口仍可能出现候选歧义。
- 活跃生产路径仍存在缺 Key 时的本地聊天答案、确定性 embedding、默认 Noop RAG 和
  Noop StepRecorder。这些路径违反“失败必须真实可观察”的项目宪法。
- 当前源码没有 `org.springframework.ai` 导入；Spring AI Starter 尚未承载实际适配行为。
- `agent-llm` 中的 `AgentFlowProperties` 同时承载 Model、Tools、Security 和 MCP 配置，
  并被 Web 与 Demo 引用；这是当前已知的临时跨模块配置边界。

### Decision

先保留并自动化证明现有行为基线，再逐项修正装配和失败语义。文件存在或上下文能够偶然
启动不视为证据，每一项结论必须由聚焦测试或完整 Demo 上下文测试证明。

## 2. 受支持的版本组合

### Decision

Feature 001 的最终受支持运行与构建组合为：

| Component | Target | Role |
| --- | --- | --- |
| Java | 17 | 编译目标、最低运行版本和 CI JDK |
| Maven（通过 Wrapper） | 3.9.16 | 仓库固定的统一构建入口 |
| Spring Boot | 4.1.1 | 当前稳定应用与依赖管理基线 |
| Spring Framework | 7.x | 由 Spring Boot 4.1.1 管理 |
| springdoc-openapi | 3.1.0 | Boot 4 兼容的文档依赖 |

**Not supported by this Feature**: Spring AI 2.0.1 仅作为后续 Feature 重新评估的当前候选；
当前不保留依赖，也不声明 HiAgent 已支持或验证兼容。

实施时先在现有 3.5.14 基线上建立测试证据，再按官方迁移建议在兼容性 Phase 内验证最新
3.5.x，最后迁移到 4.1.1。中间版本只用于降低迁移诊断难度，不形成额外发布承诺。

### Rationale

- Spring Boot 官方在 2026-08-20 发布 4.1.1，官方安装文档将 4.1.1 列为稳定版本，并
  继续支持 Java 17 作为最低版本。
- Boot 4 迁移指南要求先检查最新 3.5.x，说明 Boot 4 基于 Spring Framework 7、
  Jakarta EE 11 和 Servlet 6.1，并明确列出模块化 Starter 与测试依赖变化。
- Spring AI 2.0.1 已于 2026-08-21 发布，但当前仓库没有使用其类型。保留未使用 Starter
  会让简历和文档夸大真实实现，因此本 Feature 移除依赖，只把该版本记录为后续重新评估
  的当前候选，不声明本项目已验证兼容。
- Maven 3.9.16 是已发布的 3.9.x 版本；Wrapper 能让本地和 CI 使用同一 Maven 实现。

### Official Evidence

- [Spring Boot 4.1.1 release](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)
- [Spring Boot installation and system requirements](https://docs.spring.io/spring-boot/installing.html)
- [Spring Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 3.5.16 release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
- [Spring AI 2.0.1 release](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)
- [Spring AI getting started](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [springdoc-openapi releases](https://github.com/springdoc/springdoc-openapi/releases)
- [Maven 3.9.16 release notes](https://maven.apache.org/docs/3.9.16/release-notes.html)

### Alternatives Considered

- **停留在 Boot 3.5.14**：变更最少，但不会满足项目“完成时不过时”的目标，也避开了
  本 Feature 明确要求的兼容性结论。
- **只升级到 Boot 3.5.16**：迁移风险更低，也可作为过渡检查点；但它仍是上一代主线，
  不作为本项目最终基线。
- **直接引入 Spring AI 2.0.1**：只有实际适配器使用其 API 时才有价值。当前加入会增加
  依赖和迁移面，却没有可演示代码路径，因此延期到对应模型适配 Feature。
- **升级到更高 Java 版本**：Boot 4 支持 Java 17，项目宪法也固定 Java 17；本 Feature
  没有业务收益足以同时改变 JDK 基线。

### Migration Risks and Controls

| Risk | Control |
| --- | --- |
| Boot 4 上游模块化 Starter 依赖变化 | 按迁移指南逐项审查 Web、Test、Security、Flyway 依赖；不在本 Feature 创建或拆分 HiAgent Starter |
| Jackson 3 包名与行为变化 | 编译错误和 JSON 聚焦测试驱动迁移，不预先批量替换 |
| 第三方兼容性 | 固定 springdoc 3.1.0，并由完整上下文和全仓验证证明 |
| 功能修复与升级混淆 | 兼容性迁移独立 Phase、独立验证和阶段提交 |
| 升级失败难以定位 | 先验证现有基线，再验证最新 3.5.x，最后进入 Boot 4 |

## 3. Maven Wrapper 与 CI

### Decision

- 提交官方 Maven Wrapper 的 `mvnw`、`mvnw.cmd` 和
  `.mvn/wrapper/maven-wrapper.properties`，固定 Maven 3.9.16。
- 采用 Wrapper 的 only-script 形式，避免在仓库提交 Wrapper JAR；属性中记录发行包 URL
  和 SHA-256 校验值。
- 根 POM 使用 Maven Enforcer 在 `validate` 阶段要求 Java 17 与 Maven 3.9.x；精确 Maven
  版本由 Wrapper 保证。
- 本地和 CI 的完整门禁统一为 `./mvnw -B -ntp clean verify`。
- GitHub Actions 在 `push` 和 `pull_request` 事件使用 Temurin 17、Maven 缓存、只读仓库
  权限和 15 分钟超时；不配置模型 secrets、数据库/Redis/Qdrant service containers 或真实
  网络调用。

### Rationale

Wrapper 解决“开发者系统 Maven 版本不同”的可复现性问题；Enforcer 给错误环境提供快速
且可理解的失败；CI 复用同一入口，避免本地和远端维护两套门禁。

### Alternatives Considered

- **只写 Maven 版本文档**：无法强制或自动下载一致版本。
- **CI 直接运行 `mvn`**：会让 CI 和本地入口发生漂移。
- **默认启动 Docker 基础设施**：Feature 001 只验证工程和装配，外部服务会增加波动且
  无法证明更多 Agent 行为。

## 4. 自动配置与应用覆盖

### Decision

- 模块只通过精确 `AutoConfiguration.imports` 被发现，不扩大 Demo 根包扫描。
- 可替换端口使用 `@Bean` 与针对接口的 `@ConditionalOnMissingBean`；不使用 `@Primary`
  掩盖竞争 Bean。
- 非可替换的 Controller 和 Service 可以由模块自动配置精确 `@Import`。
- 模型层把共享 HTTP 传输和 `AgentModelClient`、`ChatModelClient`、`EmbeddingClient`
  三个端口适配 Bean 分开，每个端口独立退让。
- `RagRetriever` 不提供框架级 Noop 默认值；Demo 或消费应用必须显式提供真实实现。
- 当前 Runtime 构造函数把 `RagRetriever` 作为必需端口，因此缺失时在 Runtime 装配阶段
  明确失败；本 Feature 不提前实现 Feature 005 的可选 RAG 语义。
- `AgentFlowProperties` 本 Feature 只删除生产默认凭据，不进行跨模块拆分；对应配置分别在
  Feature 002、006、008 随真实能力边界收口。
- 每个承诺的覆盖点都用 `ApplicationContextRunner` 验证默认、覆盖和缺失三种状态。

### Rationale

条件必须针对消费方注入的接口判断。这样应用只替换一个端口时不会同时破坏其他端口，
也不会依赖 Bean 名称、扫描顺序或 `@Primary` 的隐式优先级。

### Alternatives Considered

- **一个实现类同时注册三个端口**：代码少，但无法安全地单独覆盖一个端口。
- **组件扫描所有模块**：会把内部类变成偶然 API，并使测试与真实消费方式不一致。
- **始终注册 Noop**：启动容易，但会把未配置能力表示成成功空结果。

## 5. 缺失能力与模型失败语义

### Decision

- 缺 API Key 时，在任何网络调用前抛出 `agent-llm` 本地配置异常。
- Provider HTTP 错误、空响应、空内容和无效 embedding 均返回明确失败。
- 本地固定答案、确定性 embedding、Fake 和 Noop 只能出现在 `src/test` 或显式离线演示
  Fixture 中，不能成为生产 Bean。
- 可选能力没有实现时保持未注册；必需能力在装配或首次使用时明确失败。

### Rationale

测试可重复性应由测试替身提供，而不是由生产代码伪造成功。错误留在适配器模块，可以
保持 `agent-core` 不感知 Spring、HTTP、JSON 或厂商 SDK。

### Alternatives Considered

- **缺 Key 时返回本地答案**：调用方无法区分模型成功与配置错误。
- **embedding 使用哈希向量**：会产生表面可检索、实际没有语义的错误结果。
- **全部启动时强制失败**：会让未启用的可选适配器阻止应用启动；按能力是否必需区分更
  符合模块化消费方式。

## 6. 测试架构

### Decision

| Layer | Test style | Proves |
| --- | --- | --- |
| `agent-core` | JUnit + test-scope ArchUnit | 生产字节码只依赖允许的 JDK/核心类型 |
| 各自动配置模块 | `ApplicationContextRunner` | 默认 Bean、应用覆盖、缺失能力与唯一候选 |
| 模型适配器 | Mock HTTP server | 请求映射、成功解析、缺 Key、错误和空响应 |
| `agent-demo` | `@SpringBootTest` + H2 | 完整应用、Repository、Runtime 和 Bean 来源 |
| 临时 Web | MockMvc 映射快照 | 11 个操作注册，四类入口可基本分发 |
| 全仓 | Maven `verify` | 所有模块在同一外部运行服务隔离门禁下通过 |

完整上下文只替换不可控外部边界，不 Mock `AgentRuntime`、Service、Repository 或模块核心
Bean。H2 只证明 Spring/JPA 装配，不宣称证明 MySQL 或 Flyway 生产兼容性。

### Alternatives Considered

- **只做 Controller 单元测试**：不能发现 Entity、Repository 或跨模块自动配置遗漏。
- **默认连接真实 MySQL/Redis/Qdrant**：引入不可控前置条件，超出本 Feature 验收范围。
- **只检查 Bean 存在**：无法证明来源、唯一性和覆盖退让。

## 7. 数据、契约与文档边界

### Decision

- 不生成 `data-model.md`：Feature 001 不新增实体、字段、关系或状态机。
- 不生成 `contracts/`：已澄清的 11 个 HTTP 操作全部是临时 Demo 快照，不是稳定公开
  契约；Feature 003 再定义 Run/SSE 的机器可读契约。
- 生成 `quickstart.md` 作为 Feature 验收入口；实施完成后再生成 `verification.md` 记录
  实际命令、结果、耗时和限制。

### Rationale

为临时接口提前生成公开契约会制造错误兼容承诺；没有数据变化时生成空数据模型只会增加
维护噪音。

## 8. 凭据与本地状态

### Decision

- API Key、JWT secret、数据库密码和初始化管理员密码不提供生产默认值。
- `application.yml` 与 Compose 只引用环境变量；`.env.example` 只提供变量名和非秘密
  占位说明；测试凭据仅放在测试资源。
- `.gitignore` 忽略密钥文件、构建输出、日志、`.ua/`、本机 Feature 指针和检查点，但
  保留正式 `AGENTS.md`、`.agents/`、`.specify/`、`specs/`、`docs/` 与 Wrapper。

### Rationale

可复现测试不需要真实凭据，生产默认密码也不能作为“开箱即用”的代价。版本控制应保留
治理和设计证据，只排除机器局部状态与秘密。

## 9. 已解决问题

规划阶段没有剩余未决技术问题：

- 版本线、迁移次序和回退风险已明确。
- Wrapper、CI 和默认外部运行服务隔离门禁已明确。
- 自动配置覆盖规则与缺失能力行为已明确。
- 数据模型和公开契约是否需要生成已明确。
- Runtime、正式 Run/SSE、记忆、RAG、MCP、评测和 Starter 的最终设计均明确延期到对应
  Feature。
