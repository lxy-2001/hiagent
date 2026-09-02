# Tasks: 工程基线与 Starter 边界

**Input**: Design documents from /specs/001-engineering-baseline-starter/

**Prerequisites**: spec.md、plan.md、research.md 和 quickstart.md 已完成；本 Feature 不创建 data-model.md 或稳定 contracts/。

**Tests**: 行为与缺陷修复必须先写会因目标行为缺失而失败的测试；Wrapper、依赖迁移、CI 和纯文档变更使用任务中指定的直接校验。

**Organization**: 任务按显式 Phase 和用户故事组织。每项任务包含准确路径、前置顺序和可观察证据。

**Pre-implementation gate**: 在执行 T001 前运行 speckit-analyze；存在未解决的 CRITICAL 或 HIGH 问题时先修订 SDD，不得开始编码。

## Format

- [P] 表示任务修改不同文件且没有未满足依赖，可以并行执行。
- [US1]、[US2]、[US3] 分别映射 spec.md 中的三个用户故事。
- Setup、兼容性基础和最终收尾任务不标用户故事。
- 任何任务只有在其命令和断言真实通过后才能标记为 [X]。

## Phase 1: Setup and Baseline

**Purpose**: 安全接管当前脏工作区，固定 Maven 入口，并保留升级前的真实基线证据。

- [X] T001 审查当前 main 分支和 git status --short，将既有改动逐路径归属到用户工作或 Feature 001，安全创建 feature/001-engineering-baseline-starter 且不搬运、回退或覆盖任何改动，并把分支、Java/Maven 版本和归属结论记录到 specs/001-engineering-baseline-starter/verification.md
- [X] T002 在 Spring Boot 3.5.14 现状上运行 mvn -B -ntp test，将六模块结果、实际测试数、耗时及任何失败原样记录到 specs/001-engineering-baseline-starter/verification.md，不把文件存在视为功能已完成
- [X] T003 添加 Maven 3.9.16 only-script Wrapper 到 mvnw、mvnw.cmd 和 .mvn/wrapper/maven-wrapper.properties，记录发行包 URL 与 SHA-256，并用 ./mvnw --version 验证 Maven 3.9.16、Java 17 和 Linux 可执行位
- [X] T004 在 pom.xml 配置 Maven Enforcer validate 门禁，要求 Java 17 与 Maven 3.9.x；用 ./mvnw -B -ntp validate 验证受支持环境成功，并检查错误消息能明确说明版本约束
- [X] T005 运行 ./mvnw -B -ntp clean verify 和 git diff --check，将 Phase 1 的退出码、测试数和耗时写入 specs/001-engineering-baseline-starter/verification.md

**Phase 1 checkpoint**: T001-T005 全部通过后，审查仅属于本 Phase 的 diff，并按 AGENTS.md 创建 Phase 1 完成提交。

---

## Phase 2: Compatibility Foundation - Spring Boot 4 Migration

**Purpose**: 在一个独立 Phase 内完成最新 3.5.x 过渡检查和 Boot 4.1.1 迁移，不夹带 Agent 产品行为修改。

**CRITICAL**: 本 Phase 只恢复现有编译、测试和应用行为；不得修改 Runtime、Tool、RAG、记忆或 HTTP 语义。

- [X] T006 将 pom.xml 的 Spring Boot 过渡版本更新为 3.5.16，运行 ./mvnw -B -ntp clean verify，并把过渡结果和迁移警告记录到 specs/001-engineering-baseline-starter/verification.md 后再进入 Boot 4
- [X] T007 在 pom.xml 固定 Spring Boot 4.1.1 与 springdoc-openapi 3.1.0，删除未被源码使用的 Spring AI BOM/version，并校验 dependency:tree 不再声明 org.springframework.ai 依赖
- [X] T008 按 Boot 4 上游依赖/Starter、测试和 Flyway 规则调整 agent-core/pom.xml、agent-llm/pom.xml、agent-tool/pom.xml、agent-rag/pom.xml、agent-web/pom.xml 和 agent-demo/pom.xml，仅保留当前六模块编译与测试需要的依赖；不得创建或拆分 HiAgent Starter/模块
- [X] T009 仅依据编译错误迁移已知受影响的 agent-llm/src/main/java/com/agentflow/llm/OpenAiCompatibleModelClient.java、agent-web/src/main/java/com/agentflow/web/agent/TaskEventPublisher.java、agent-demo/src/main/java/com/agentflow/demo/knowledge/QdrantClient.java；若编译错误直接指向其他现有文件，允许一并迁移该文件中的 Boot 4、Jackson 3 或测试 API，并在 verification.md 记录路径，禁止借机重构
- [X] T010 依次运行 ./mvnw -pl agent-core -am test、./mvnw -pl agent-llm -am test、./mvnw -pl agent-web -am test、./mvnw -pl agent-demo -am test 和 ./mvnw -B -ntp clean verify，将最终 Java 17/Boot 4.1.1 证据及无 Agent 行为变更的 diff 审查结论记录到 specs/001-engineering-baseline-starter/verification.md

**Phase 2 checkpoint**: Boot 4.1.1 全仓门禁通过且迁移 diff 不含功能修改后，创建独立的 Phase 2 完成提交。

---

## Phase 3: User Story 1 - 使用统一命令验证仓库 (Priority: P1) 🎯

**Goal**: 本地和 GitHub Actions 使用同一个 Wrapper 命令验证六个模块，默认不需要真实模型 Key、Docker 或外部运行服务。

**Independent Test**: 在未配置模型凭据且未启动 MySQL、Redis、Qdrant 的环境运行 ./mvnw -B -ntp clean verify，六模块成功且工作流调用同一命令。

- [X] T011 [P] [US1] 创建 .github/workflows/verify.yml，使其在 push 和 pull_request 事件触发，使用只读 contents 权限、Temurin 17、Maven 缓存、15 分钟超时和 ./mvnw -B -ntp clean verify，且不配置 secrets、service containers 或真实模型调用
- [X] T012 [P] [US1] 更新 README.md 和 docs/testing.md，只声明已验证的 Wrapper、Java 17、Boot 4.1.1、六模块测试入口、首次 Maven Central 下载要求及默认外部服务隔离，不保留错误测试数量或本地模型兜底说明
- [X] T013 [US1] 静态校验 .github/workflows/verify.yml 具有 push/pull_request 触发、只调用统一门禁且无 secrets/services，再在清除模型凭据且不启动 Docker 的环境执行 ./mvnw -B -ntp clean verify，将本地命令、退出码、测试数、耗时和 CI 工作流路径记录到 specs/001-engineering-baseline-starter/verification.md

**Phase 3 checkpoint**: US1 可独立复现，README 与 CI 使用同一入口后，创建 Phase 3 完成提交。此时只是最小可演示构建切片，不代表整个 Feature 完成。

---

## Phase 4: User Story 2 - 显式加载并覆盖可复用组件 (Priority: P2)

**Goal**: 模块通过精确自动配置装配当前 Runtime/Web 能力；应用 Bean 能逐端口覆盖默认值；缺少真实能力时明确失败而非伪成功。

**Independent Test**: 运行 agent-llm、agent-rag、agent-tool、agent-web 的 ApplicationContextRunner 测试和 agent-demo 完整上下文测试，验证默认、覆盖、缺失与唯一候选四类状态。

**Phase 4 ordering rule**: T014-T018 可并行编写并先形成真实 Red/characterization 证据；T019
汇总后按 T020 → T021 的顺序完成 LLM 失败语义与适配器拆分，T022-T025 可在不修改同一文件
时并行，最后由 T026 运行受影响模块验证。


### Failing and Characterization Tests

- [X] T014 [P] [US2] 编写 agent-llm 的失败与装配测试，先在 agent-llm/src/test/java/com/agentflow/llm/OpenAiCompatibleModelClientTest.java 和 AgentLlmAutoConfigurationTest.java 中覆盖缺 Key、Provider HTTP 错误、空响应/内容、无效 embedding 及三个端口分别覆盖时默认 Bean 独立退让；embedding 兜底断言必须改为明确失败，测试目标按 T021 拆分后的三个适配器迁移并保持可追踪
- [X] T015 [P] [US2] 新增 agent-rag/src/test/java/com/agentflow/rag/AgentRagAutoConfigurationTest.java，证明框架默认不创建 RagRetriever、应用提供的真实 RagRetriever 保持唯一；若显式装配 Runtime 但缺少该必需端口，必须明确失败而不是以空列表伪装成功，此边界由 T017 在 agent-web 验证
- [X] T016 [P] [US2] 新增 agent-tool/src/test/java/com/agentflow/tool/AgentToolAutoConfigurationTest.java，证明空 AgentTool 集合产生真实空 ToolRegistry、自定义 ToolRegistry 覆盖默认值，且未实现的 McpToolProvider 不会自动注册为生产能力
- [X] T017 [P] [US2] 新增 agent-web/src/test/java/com/agentflow/web/autoconfigure/AgentWebAutoConfigurationTest.java，覆盖 TaskPlanner、ShortTermMemory、StepRecorder 和 AgentRuntime 的默认唯一 Bean、逐接口应用覆盖、缺 RagRetriever 失败以及不存在 NoopStepRecorder
- [X] T018 [US2] 新增 agent-demo/src/test/java/com/agentflow/demo/AgentFlowDemoContextTest.java 和 agent-demo/src/test/resources/application-test.yml，以 H2、测试 JWT 和受控外部边界启动完整上下文，断言 Controller、Service、全部 Repository、Runtime、三个模型端口、ToolRegistry、KnowledgeRagRetriever、JpaStepRecorder 和 `InMemoryShortTermMemory` 的数量及 Bean 来源，不 Mock Runtime、Service 或 Repository；初次 Red 测试使用测试专用 `@SpringBootConfiguration`/组件扫描排除 KnowledgeBootstrapConfig，T025 完成后改用 `agentflow.knowledge.bootstrap.enabled=false` 正式属性开关验证同一行为
- [X] T019 [US2] 运行 T014-T018 的目标测试并把各次 Red/characterization 证据写入 specs/001-engineering-baseline-starter/verification.md；T018 初次运行必须通过 test-only 配置隔离知识导入，T025 完成后重新运行并验证正式属性开关；已经由用户既有改动满足的断言按 characterization 记录为通过，不得伪造失败或回退既有修复

### Minimal Implementation and Refactor

- [X] T020 [US2] 在 agent-llm/src/main/java/com/agentflow/llm/ModelClientException.java 和 agent-llm/src/main/java/com/agentflow/llm/OpenAiCompatibleModelClient.java 实现适配器本地明确失败，删除缺 Key、本地固定回答、确定性 embedding、空响应和 Provider 错误的伪成功路径，使 T014 的失败语义测试转绿；完成并验证后才能开始 T021
- [X] T021 [US2] 将 agent-llm/src/main/java/com/agentflow/llm/OpenAiCompatibleModelClient.java 收敛为不再实现三个核心端口的共享 HTTP/JSON 传输，并新增 agent-llm/src/main/java/com/agentflow/llm/OpenAiAgentModelClient.java、agent-llm/src/main/java/com/agentflow/llm/OpenAiChatModelClient.java、agent-llm/src/main/java/com/agentflow/llm/OpenAiEmbeddingClient.java；在 agent-llm/src/main/java/com/agentflow/llm/AgentLlmAutoConfiguration.java 对三个核心端口分别使用 ConditionalOnMissingBean，同时将 T014 的请求/失败测试迁移为对应的 OpenAiAgentModelClientTest、OpenAiChatModelClientTest 和 OpenAiEmbeddingClientTest，删除旧的确定性 embedding 成功断言
- [X] T022 [US2] 修改 agent-rag/src/main/java/com/agentflow/rag/AgentRagAutoConfiguration.java 使其不再注册默认 RagRetriever，并删除 agent-rag/src/main/java/com/agentflow/rag/NoopRagRetriever.java，保留应用自定义 Bean 且不提前实现 Feature 005 的可选 RAG 语义
- [X] T023 [US2] 在 agent-tool/src/main/java/com/agentflow/tool/AgentToolAutoConfiguration.java 将条件明确绑定 ToolRegistry 接口，保留真实空注册表语义且不注册 agent-tool/src/main/java/com/agentflow/tool/McpToolProvider.java
- [X] T024 [US2] 在 agent-web/src/main/java/com/agentflow/web/autoconfigure/AgentWebAutoConfiguration.java、agent-web/src/main/java/com/agentflow/web/agent/JpaStepRecorder.java 和 agent-web/src/main/java/com/agentflow/web/agent/RedisShortTermMemory.java 移除 `@Component`、`@Primary` 及 AgentWebAutoConfiguration 的对应 `@Import` 条目；为 JpaStepRecorder 保留显式 `@Bean + @ConditionalOnMissingBean`，RedisShortTermMemory 在本 Feature 不自动注册、仅允许应用显式提供；删除 agent-web/src/main/java/com/agentflow/web/step/NoopStepRecorder.java，并允许应用逐接口覆盖 TaskPlanner、Memory、Recorder 与 Runtime
- [X] T025 [US2] 在 agent-demo/src/main/java/com/agentflow/demo/config/KnowledgeBootstrapConfig.java 上使用 `@ConditionalOnProperty(name = "agentflow.knowledge.bootstrap.enabled", havingValue = "true", matchIfMissing = true)` 增加显式知识导入开关；T018 初次 Red 测试的 test-only 扫描排除仅用于隔离外部调用，完成本任务后必须在 agent-demo/src/test/resources/application-test.yml 设置该属性为 `false`，并使 AgentFlowDemoContextTest 使用真实模块装配通过
- [X] T026 [US2] 运行 ./mvnw -pl agent-llm -am test、./mvnw -pl agent-rag -am test、./mvnw -pl agent-tool -am test、./mvnw -pl agent-web -am test、./mvnw -pl agent-demo -am test 和 git diff --check，将覆盖退让、缺失能力、Bean 来源及测试结果记录到 specs/001-engineering-baseline-starter/verification.md

**Phase 4 checkpoint**: 所有承诺覆盖点、明确失败和完整 Demo 上下文均通过后，创建 Phase 4 完成提交。

---

## Phase 5: User Story 3 - 保护模块边界和当前 Demo 入口 (Priority: P3)

**Goal**: 自动化保护纯 Java core、11 个临时 Demo 操作和生产凭据边界，并让正式说明与真实代码一致。

**Independent Test**: 运行 core 架构测试、临时端点快照/四组 MockMvc 分发、凭据测试与秘密扫描，确认边界违规为 0、11 个操作全部覆盖、生产默认凭据为 0。

### Failing and Characterization Tests

- [X] T027 [P] [US3] 在 agent-core/pom.xml 添加仅 test scope 的 ArchUnit，并新增 agent-core/src/test/java/com/agentflow/core/architecture/CoreDependencyBoundaryTest.java，检查 agent-core 生产字节码不引用 Spring、JPA、Servlet、SQL、java.net.http、Redis、Qdrant、模型厂商或 Spring AI 类型
- [X] T028 [P] [US3] 将 agent-demo/src/test/java/com/agentflow/demo/AgentWebEndpointRegistrationTest.java 收敛为 agent-demo/src/test/java/com/agentflow/demo/TemporaryDemoEndpointSnapshotTest.java，精确断言 Auth 4、Chat 2、Agent 4、Knowledge 1 共 11 个临时操作，并对四组入口各做一次受控 MockMvc 基本分发
- [X] T029 [P] [US3] 新增 agent-llm/src/test/java/com/agentflow/llm/AgentFlowPropertiesTest.java 和 agent-demo/src/test/java/com/agentflow/demo/config/InitialDataConfigTest.java，证明 JWT 无生产默认 Secret、未显式提供初始化账号/密码时不创建管理员、只配置一半凭据时明确失败、完整显式测试凭据才创建测试用户
- [X] T030 [US3] 运行 T027-T029 的目标测试并将真实 Red 或 characterization 结果记录到 specs/001-engineering-baseline-starter/verification.md，确认失败只对应 core 越界、入口缺失或生产默认凭据

### Minimal Implementation and Documentation Truth

- [X] T031 [US3] 修改 agent-llm/src/main/java/com/agentflow/llm/AgentFlowProperties.java、agent-web/src/main/java/com/agentflow/web/config/SecurityConfig.java、agent-demo/src/main/java/com/agentflow/demo/config/InitialDataConfig.java 和 agent-demo/src/main/resources/application.yml，移除 JWT、数据库与初始化管理员通用默认值；缺 Secret 或半配置管理员时给出明确错误，测试值只存在于 src/test
- [X] T032 [US3] 创建 .env.example 并修改 docker-compose.yml 与 agent-demo/src/main/resources/static/index.html，使 Compose 和页面不再包含可直接使用的数据库/管理员密码，只展示环境变量名及非秘密占位说明
- [X] T033 [P] [US3] 更新 README.md、specs/README.md 和 docs/testing.md，标明 Feature 001 当前状态、唯一 Wrapper 门禁、外部服务隔离、真实失败语义和后续 Feature 边界，删除无激活 Feature、固定账号及错误测试统计
- [X] T034 [P] [US3] 更新 docs/architecture.md 和 docs/module-design.md，准确描述精确 AutoConfiguration.imports、逐端口覆盖、无 Noop RAG/Recorder、当前 Runtime/Web 临时边界以及 Feature 002/005/008 的退出点
- [X] T035 [P] [US3] 更新 docs/configuration.md、docs/api/rest-api.md、docs/database.md 和 docs/deployment.md，移除固定 Key/Secret/密码、本地兜底和未经验证的稳定 SSE/高可用承诺，将 11 个操作明确标记为临时 Demo；保留 docs/learn/ 用户学习快照不变
- [X] T036 [US3] 运行 ./mvnw -pl agent-core -am test、./mvnw -pl agent-demo -am test 和 git diff --check，并按 plan.md 的“凭据扫描”规则扫描 agent-*/src/main/**、docker-compose.yml、README.md、specs/ 和 docs/（排除 docs/learn/）中的 API key、JWT secret、数据库/管理员 password 非空字面量及基线审计记录的已知历史默认凭据指纹清单；允许环境变量引用、明确的非秘密占位符和 src/test/** 测试值，生产范围命中时命令必须非零且只记录脱敏文件/行号；同时将 0 违规、11 操作和四组分发证据记录到 specs/001-engineering-baseline-starter/verification.md

**Phase 5 checkpoint**: 架构、临时入口、凭据和正式文档均满足 US3 后，创建 Phase 5 完成提交。

---

## Phase 6: Feature Integration and Evidence

**Purpose**: 只收尾 Feature 001，证明全部需求，不新增后续 Agent 能力。

- [X] T037 按 specs/001-engineering-baseline-starter/quickstart.md 从头执行 Wrapper、core、LLM、Web、Demo 和完整门禁，将操作系统、Java/Maven 版本、实际测试数、开始/结束时间、墙钟秒数、命令退出码、CI 工作流位置及未验证限制完整写入 specs/001-engineering-baseline-starter/verification.md；依赖已缓存且未启动外部服务时，SC-009 仅在 elapsed_seconds <= 600 时通过，超过 600 秒必须标记为未通过并说明原因
- [X] T038 依据 spec.md 的 FR-001 至 FR-014 和 SC-001 至 SC-011 在 specs/001-engineering-baseline-starter/verification.md 逐项建立最终证据矩阵，并运行 ./mvnw -B -ntp clean verify、git diff --check 和 plan.md 规定的生产凭据/Noop 扫描；明确记录 SC-009 的 600 秒判定和 SC-011 的扫描退出码，任何失败必须保留真实原因
- [X] T039 重新运行 speckit-analyze，修正 specs/001-engineering-baseline-starter/spec.md、specs/001-engineering-baseline-starter/plan.md 和 specs/001-engineering-baseline-starter/tasks.md 中全部 CRITICAL/HIGH 不一致，再重跑受影响验证
- [X] T040 运行 speckit-converge 对照代码与 Feature 文档；若向 specs/001-engineering-baseline-starter/tasks.md 追加任务，先按依赖完成并验证所有新增任务，直到没有剩余未实现工作
- [X] T041 在全部任务、全仓门禁、analyze、converge 和证据通过后，将 specs/001-engineering-baseline-starter/spec.md 状态更新为 VERIFIED、将 specs/ROADMAP.md 的 Feature 001 更新为 VERIFIED，审查最终 diff 不含 docs/learn/、.ua/、密钥、后续 Feature 或用户无关改动，并再次运行 ./mvnw -B -ntp clean verify 与 git diff --check

**Phase 6 checkpoint**: 创建最终文档/状态完成提交；按 AGENTS.md 推送 feature/001-engineering-baseline-starter，等待 .github/workflows/verify.yml 对该提交给出真实结果。远端失败时不得报告 Feature 完成或直接合并 main。

---

## Dependencies and Execution Order

### Phase Dependencies

- Phase 4 内部顺序为 T014-T018 先形成 Red/characterization、T019 汇总、T020 → T021 依次完成 LLM 失败语义与适配器拆分，T022-T025 再按不修改同一文件的原则实施，最后 T026 运行受影响模块；T018 初次运行使用测试专用配置隔离外部导入，T025 完成后再验证正式属性开关。
- Phase 1 无前置 Feature，但 T001 的工作区归属和分支检查阻塞所有写操作。
- Phase 2 依赖 Phase 1 的 Wrapper 和升级前基线；Boot 4 迁移阻塞全部用户故事。
- Phase 3 依赖 Phase 2，先交付可独立验证的 P1 构建切片。
- Phase 4 依赖 Phase 2，并按优先级在 Phase 3 后实施；它建立 Phase 5 入口与 Bean 来源检查所需的显式装配。
- Phase 5 依赖 Phase 4 的真实装配和失败语义。
- Phase 6 依赖所有用户故事和 Phase checkpoint。

### User Story Dependencies

- US1 可独立证明统一构建与 CI 入口，不依赖 US2/US3。
- US2 可用模块级上下文和完整 Demo 上下文独立证明装配，不依赖最终稳定 API。
- US3 的 core 架构测试独立于 US2；临时入口来源检查依赖 US2 的显式装配，但不冻结公开 API。

### Parallel Examples

- Phase 3 中 T011 与 T012 修改不同文件，可并行；T013 等待二者。
- Phase 4 中 T014-T018 可在兼容性迁移完成后并行编写和形成初次 Red/characterization；T019 等待它们完成后汇总，随后按 T020 → T021 → (T022-T025) 执行，T022-T025 不得同时修改同一文件，最后由 T026 验证模块结果。
- Phase 5 中 T027、T028、T029 可并行编写；代码稳定后 T033、T034、T035 可并行同步不同正式文档组。
- 不得并行执行 T006-T010 的版本迁移步骤，也不得并行修改同一个 AutoConfiguration、Java 源文件或 pom.xml。

## Implementation Strategy

### MVP First

1. 完成 Phase 1 和 Phase 2，先获得可信的 Java 17/Boot 4.1.1 构建底座。
2. 完成 US1 后即可演示一个最小结果：同一 Wrapper 命令验证六模块且 CI 复用该入口。
3. US1 只是可演示增量，不满足整个 Feature 的装配、真实失败与边界验收，不能提前标记 VERIFIED。

### Incremental Delivery

1. US2 先写模块与 Demo 上下文测试，再删除伪成功并收敛条件装配。
2. US3 在已稳定装配上增加 core、路由、凭据和文档真实性证据。
3. 每个 Phase 只暂存明确路径、通过受影响门禁后提交；默认不逐 Phase 推送。
4. Phase 6 只做证据、分析、收敛和状态更新，不实施 Feature 002 及以后能力。

## Requirement Traceability

| Requirement | Primary tasks |
| --- | --- |
| FR-001 | T003-T005, T012-T013, T037-T038 |
| FR-002 | T005, T012-T019, T026, T036-T038 |
| FR-003 | T011-T013, T037-T038 |
| FR-004 | T018, T024-T026 |
| FR-005 | T017-T019, T024-T026, T034 |
| FR-006 | T014-T017, T021, T023-T024, T026 |
| FR-007 | T014-T015, T017, T020-T022, T029-T031 |
| FR-008 | T014-T017, T020, T022, T024, T036 |
| FR-009 | T015-T016, T022-T023 |
| FR-010 | T027, T036 |
| FR-011 | T028, T033-T036 |
| FR-012 | T006-T010, T037-T038 |
| FR-013 | T014, T018, T025-T026, T037-T038 |
| FR-014 | T029-T032, T035-T036 |
| SC-001 | T003-T005, T012-T013, T037-T038 |
| SC-002 | T012-T013, T018, T025-T026, T036-T038 |
| SC-003 | T011-T013, T037-T038 |
| SC-004 | T018, T024-T026, T037-T038 |
| SC-005 | T014-T017, T021, T023-T024, T026, T038 |
| SC-006 | T014-T017, T020, T022, T024, T026, T036, T038 |
| SC-007 | T027, T036, T038 |
| SC-008 | T028, T033-T036, T038 |
| SC-009 | T037-T038 |
| SC-010 | T006-T010, T037-T038 |
| SC-011 | T029-T032, T035-T036, T038 |
| GOV-001 | T001-T002, T039-T041 |

## Notes

- 现有 agent-web 和 agent-demo 未提交修复必须通过测试确认并纳入正确 Phase，不按“看起来已完成”直接勾选。
- docs/learn/ 中的用户学习资料保持不变，不作为 Feature 001 的最新行为依据。
- 测试 Fixture 只能位于 src/test；生产 Bean 不得使用 Fake、Noop、固定回答或确定性向量。
- 不创建最终 Starter 模块，不迁移 DefaultAgentRuntime，不设计正式 Run/SSE，不重写 RAG、记忆、MCP 或 Agent 循环。
- 每个任务的验证结果写入 verification.md；没有真实输出时不得预填通过。
- 每个 Phase 完成后提交，整个 Feature 验证后推送；不得 git add .、git add -A、强推或自动合并 main。
