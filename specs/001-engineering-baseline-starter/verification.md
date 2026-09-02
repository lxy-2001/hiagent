# Feature 001 Verification Record

本文件记录 Feature 001 的真实实施与验证证据。没有执行过的命令不得预填为通过。

## T001：工作区接管与分支审计

**审计时间**：2026-09-02 UTC
**基线提交**：`5792bf8` (`chore: add project learning workflow`)
**审计前分支**：`main`（与 `origin/main` 一致）
**审计后分支**：`feature/001-engineering-baseline-starter`
**远端同名分支**：审计时未发现；本地新建，尚未推送。

### 环境证据

| 项目 | 实际结果 |
| --- | --- |
| Java | OpenJDK/Temurin `17.0.20.1` |
| Maven | Apache Maven `3.9.16` |
| 操作系统 | Linux `6.8.0-111-generic`, amd64 |
| 当前 Feature | `/root/hiagent/specs/001-engineering-baseline-starter` |

### 既有改动归属

审计依据为 `git status --short`、逐路径 `git diff`、当前 Feature 文档和此前用户已提出的学习/治理请求。以下改动均在创建分支时原样保留；“既有”表示不是 T001 新产生的改动，后续任务如需修改必须先审查现有内容并只提交明确属于该任务的差异。

#### Feature 001 既有实现或设计材料

| 路径 | 归属与处理结论 |
| --- | --- |
| `agent-demo/pom.xml` | 已有 H2 测试依赖，直接服务于 T018；保留，后续按 T008/T018 复核。 |
| `agent-demo/src/main/java/com/agentflow/demo/AgentFlowDemoApplication.java` | 已有 Demo 入口装配修复；保留，按 T018/T028 表征。 |
| `agent-web/src/main/java/com/agentflow/web/autoconfigure/AgentWebAutoConfiguration.java` | 已有显式 Web 装配修复；保留，按 T017/T024 测试和收敛。 |
| `agent-demo/src/test/java/com/agentflow/demo/AgentWebEndpointRegistrationTest.java` | 已有 Web 入口测试；保留，按 T028 迁移或表征。 |
| `specs/001-engineering-baseline-starter/spec.md` | 当前 Feature 权威规格。 |
| `specs/001-engineering-baseline-starter/plan.md` | 当前 Feature 权威计划。 |
| `specs/001-engineering-baseline-starter/research.md` | 当前 Feature 调研记录。 |
| `specs/001-engineering-baseline-starter/quickstart.md` | 当前 Feature 验收路径。 |
| `specs/001-engineering-baseline-starter/tasks.md` | 当前 Feature 任务清单。 |
| `specs/001-engineering-baseline-starter/checklists/requirements.md` | 需求清单，审计时全部勾选。 |

#### 既有项目治理、学习资料或历史参考（不在 T001 中重写）

| 路径 | 归属与处理结论 |
| --- | --- |
| `.gitignore` | 用户此前要求的仓库忽略规则；保留。 |
| `.specify/memory/constitution.md` | 用户此前确认的项目宪法；保留，作为 Feature 约束。 |
| `.specify/templates/plan-template.md`、`.specify/templates/spec-template.md`、`.specify/templates/tasks-template.md` | 用户此前要求的 SDD 模板治理改动；保留。 |
| `AGENTS.md` | 用户此前确认的代理实施规则；保留并作为执行约束。 |
| `README.md` | 既有项目说明改动；保留，T012/T033 更新前先审查。 |
| `specs/README.md`、`specs/ROADMAP.md` | 既有项目级 SDD/路线图材料；保留，按 T033/T041 只改事实。 |
| `specs/_project/` | 历史蓝图和参考资料；保留，不作为当前 Feature 依据。 |
| `docs/learn/` | 用户学习资料快照；按规则保留，不纳入 Feature 001 实施。 |

未发现无法归属的未提交路径，也未发现需要删除、回退、搬运或覆盖的改动。创建分支时未执行 `reset`、`clean`、强制 checkout、stash 或历史改写操作；工作区状态保持原样。

**T001 结论：通过。** 已完成工作区归属审查、Feature 分支创建和本记录；可以进入 T002。

## T002：升级前全仓测试基线

**命令**：`mvn -B -ntp test`

| 项目 | 实际结果 |
| --- | --- |
| Reactor 模块 | 根 POM + 6 个 Maven 模块；6 个模块均 `SUCCESS` |
| 测试总数 | 11（LLM 3、Tool 1、Web 5、Demo 2） |
| Failures / Errors / Skipped | 0 / 0 / 0 |
| agent-core | 构建成功；无测试源，`No tests to run` |
| agent-rag | 构建成功；无测试源，`No tests to run` |
| Maven 总耗时 | `24.740 s`（命令观测墙钟约 `26.151 s`） |
| 退出码 | `0` |

测试运行于 Java 17.0.20.1、Maven 3.9.16、Spring Boot 3.5.14 基线。Demo 上下文使用已有
测试配置并成功启动；日志中的 Spring Data Redis repository 提示和 SpringDoc 开发提示均为
警告，不影响本次退出码。该结果只证明当前基线可构建及已有测试通过，不证明 core/RAG
边界、自动配置覆盖、真实失败语义或 Feature 001 的全部验收条件。

**T002 结论：通过（基线证据已记录）。**

## T003：固定 Maven Wrapper

**生成方式**：Apache Maven Wrapper Plugin `3.3.4`，`distributionType=only-script`。

| 项目 | 实际结果 |
| --- | --- |
| 脚本 | `mvnw`、`mvnw.cmd` |
| 属性 | `.mvn/wrapper/maven-wrapper.properties` |
| 发行包 URL | `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip` |
| 发行包 SHA-256 | `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce` |
| 官方 SHA-512 交叉核对 | `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3` |
| `mvnw` 权限 | `755`（Linux 可执行） |
| Wrapper JAR | 未生成；`.mvn/wrapper` 仅含 `maven-wrapper.properties` |

**验证命令**：`./mvnw --version`

实际输出确认 Apache Maven `3.9.16`、Java `17.0.20.1`、Linux amd64。发行包下载到临时目录后已用于计算 SHA-256，未放入仓库。

**T003 结论：通过。**

## T004：Java/Maven 版本门禁

根 `pom.xml` 使用 Maven Enforcer Plugin `3.6.3`，在 `validate` 阶段执行：

- Java 版本范围：`[17,18)`；失败消息明确为 `HiAgent requires Java 17`。
- Maven 版本范围：`[3.9,4.0)`；失败消息明确为 `HiAgent requires Maven 3.9.x`。

**验证命令**：`./mvnw -B -ntp validate`

| 项目 | 实际结果 |
| --- | --- |
| RequireJavaVersion | `passed` |
| RequireMavenVersion | `passed` |
| 六个模块 | 全部 `SUCCESS` |
| Maven 总耗时 | `10.492 s`（命令观测墙钟约 `11.748 s`） |
| 退出码 | `0` |

错误路径未通过伪造本机版本触发，以免引入额外 JDK/Maven；对 POM 的静态检查确认两条自定义
失败消息均直接包含所需版本和支持范围。Maven 已解析并执行该配置。

**T004 结论：通过。**

## T005：Phase 1 退出门禁

**命令 1**：`./mvnw -B -ntp clean verify`

| 项目 | 实际结果 |
| --- | --- |
| 六个模块 | 全部 `SUCCESS`（Spring Boot 3.5.14 基线） |
| 测试总数 | 11；Failures 0、Errors 0、Skipped 0 |
| agent-core / agent-rag | 构建成功；当前均无测试源 |
| Maven 总耗时 | `48.938 s` |
| 退出码 | `0` |

**命令 2**：`git diff --check` 退出码 `0`，未发现空白错误。

该门禁只证明当前 Phase 1 的 Wrapper、版本门禁和现有代码可构建；Boot 4 兼容性、模块覆盖
语义、核心架构边界和凭据扫描仍由后续 Phase 验证。

**T005 结论：通过。** Phase 1 的 T001-T005 均已取得证据。

## T006：Spring Boot 3.5.x 过渡验证

**变更**：根 `pom.xml` 的 Spring Boot parent 从 `3.5.14` 更新为 `3.5.16`；未修改 Agent 或 HTTP 行为。

**验证命令**：`./mvnw -B -ntp clean verify`

| 项目 | 实际结果 |
| --- | --- |
| 六个模块 | 全部 `SUCCESS`，日志确认 Spring Boot `3.5.16` |
| 测试总数 | 11；Failures 0、Errors 0、Skipped 0 |
| agent-core / agent-rag | 构建成功；仍无测试源 |
| Maven 总耗时 | `01:10 min` |
| 退出码 | `0` |

过渡版本构建和测试通过后才进入 Boot 4 迁移；本次差异仅为版本升级。

**T006 结论：通过。**

## T007：Boot 4.1.1 与 Spring AI 依赖清理

**变更**：根 `pom.xml` 更新 Spring Boot parent 到 `4.1.1`，`springdoc.version` 更新到 `3.1.0`；
移除未使用的 Spring AI BOM/version，并从 `agent-llm/pom.xml` 移除未被源码使用的
`spring-ai-starter-model-openai`。未发现源码导入 `org.springframework.ai`。

**验证命令**：`./mvnw -B -ntp dependency:tree -Dincludes=org.springframework.ai`

| 项目 | 实际结果 |
| --- | --- |
| Reactor | 根 POM + 6 个模块全部 `SUCCESS` |
| `org.springframework.ai` 匹配 | 无匹配依赖输出 |
| Maven 总耗时 | `01:42 min` |
| 退出码 | `0` |

Boot 4 的源码编译和测试由 T008-T010 继续验证；T007 只完成版本/依赖声明和依赖树门禁。

**T007 结论：通过。**
## T008：Boot 4 上游依赖与 Starter 调整

**变更**：按 Boot 4.1.1 的模块化依赖拆分复核六个模块。`agent-llm/pom.xml` 将可选的
`spring-boot-starter-web` 替换为可选的 `spring-boot-starter-restclient`；
`agent-web/pom.xml` 显式引入 `spring-boot-starter-restclient`，并加入 Boot 4 的
`spring-boot-starter-webmvc-test` 测试 Starter；`agent-demo/pom.xml` 加入同一 Web MVC
测试 Starter，已有 H2 测试依赖和 `flyway-mysql` 保留。`agent-core`、`agent-tool`、
`agent-rag` 经依赖审查无需新增依赖，未创建或拆分 HiAgent Starter。

**验证**：先执行 `./mvnw -B -ntp validate`，POM 解析及 Java/Maven 门禁通过。修复前的
干净 Demo 测试真实暴露 `RestClient.Builder` 无候选 Bean；加入 REST 客户端 Starter 后，
`./mvnw -B -ntp -pl agent-demo -am clean test` 通过（2 个 Demo 测试，Failures 0、
Errors 0、Skipped 0）。Flyway、H2 和外部服务隔离均按现有测试配置工作。

**T008 结论：通过。**

## T009：Boot 4 / Jackson 3 API 迁移

**变更**：仅迁移编译或测试错误直接指向的路径：

- `agent-llm/src/main/java/com/agentflow/llm/OpenAiCompatibleModelClient.java` 使用
  `tools.jackson.databind` 与 `JacksonException`。
- `agent-web/src/main/java/com/agentflow/web/agent/TaskEventPublisher.java` 使用
  Jackson 3 的 Mapper/异常类型。
- `agent-demo/src/main/java/com/agentflow/demo/knowledge/QdrantClient.java` 使用
  Jackson 3 的 `JsonNode`。
- `agent-web/src/test/java/com/agentflow/web/chat/ChatControllerSecurityTest.java`
  迁移 Boot 4 WebMvcTest 包路径，并在测试专用安全配置上显式启用 Web Security；
  `agent-demo/src/test/java/com/agentflow/demo/AgentWebEndpointRegistrationTest.java`
  迁移 `AutoConfigureMockMvc` 包路径。

这些修改只恢复既有 JSON、HTTP 和测试行为，没有新增 Agent 算法、接口或运行语义。

**验证**：上述路径的干净编译和测试均通过；Web 模块 5 个测试、Demo 模块 2 个测试
全部通过。迁移过程中保留并记录了缺少 `HttpSecurity` 的首次失败，未删除或弱化测试。

**T009 结论：通过。**

## T010：Boot 4.1.1 Phase 2 最终门禁

环境：Java `17.0.20.1`、Maven Wrapper `3.9.16`、Spring Boot `4.1.1`、Linux amd64。

| 命令 | 实际结果 |
| --- | --- |
| `./mvnw -B -ntp -pl agent-core -am test` | 2 个模块 SUCCESS；core 无测试源，退出码 0 |
| `./mvnw -B -ntp -pl agent-llm -am test` | 3 个模块 SUCCESS；3 tests，Failures/Errors/Skipped = 0/0/0 |
| `./mvnw -B -ntp -pl agent-web -am test` | 6 个模块 SUCCESS；5 tests，Failures/Errors/Skipped = 0/0/0 |
| `./mvnw -B -ntp -pl agent-demo -am test` | 7 个模块 SUCCESS；2 tests，Failures/Errors/Skipped = 0/0/0 |
| `./mvnw -B -ntp clean verify` | 7 个模块 SUCCESS；全仓 11 tests，Failures/Errors/Skipped = 0/0/0；Maven 35.552 s；退出码 0 |

对 Phase 2 变更逐文件审查：差异仅包含 Boot 版本/依赖兼容、Jackson 3 类型迁移、Boot 4
测试 API 迁移和测试专用安全基础设施；没有 Runtime、Tool、RAG、记忆、HTTP 业务语义或
数据模型修改。Phase 2 的 Wrapper、版本门禁和 `git diff --check` 均保持通过。

**T010 结论：通过。Phase 2 T006-T010 已全部取得证据，可进入 Phase 3。**
## T011：GitHub Actions 统一验证工作流

**文件**：`.github/workflows/verify.yml`。

工作流在 `push` 和 `pull_request` 触发，顶层和 Job 级权限均为只读 `contents: read`；使用
`ubuntu-latest`、Temurin Java 17、Maven 缓存和 15 分钟 Job 超时。唯一运行步骤为
`./mvnw -B -ntp clean verify`，没有 `secrets`、`services`、Docker 或模型调用配置。

**T011 结论：通过（工作流文件已加入仓库，远端执行结果待推送后由 GitHub Actions 产生）。**

## T012：README 与测试文档同步

更新 `README.md` 和 `docs/testing.md`，移除 Boot 3、未使用系统 Maven、固定管理员密码、
本地模型兜底、旧测试数量和启动外部服务等过时说明；文档现在明确 Java 17、Maven Wrapper
3.9.16、Spring Boot 4.1.1、6 个模块、首次 Maven Central 下载要求、默认外部服务隔离、
唯一完整门禁和当前临时 Demo 边界。当前观测值记录为 7 个测试类、11 个测试方法；
agent-core/agent-rag 无测试源这一事实也已明确标注。

**T012 结论：通过。**

## T013：Phase 3 独立验证

**静态工作流检查**：通过。已确认 `push`/`pull_request`、只读权限、Temurin 17、Maven
缓存、15 分钟超时、唯一统一命令均存在；未发现 `secrets`、`services`、Docker 或模型 Key
配置。

**本地门禁**：在未设置模型凭据、未启动 Docker/MySQL/Redis/Qdrant 的环境运行
`./mvnw -B -ntp clean verify`。

| 项目 | 实际结果 |
| --- | --- |
| 模块 | 根 POM + 6 个模块全部 `SUCCESS` |
| 测试 | 11；Failures 0、Errors 0、Skipped 0 |
| Maven 报告耗时 | `26.693 s` |
| 退出码 | `0` |
| CI 工作流路径 | `.github/workflows/verify.yml`（远端结果待推送后产生） |

**T013 结论：通过。Phase 3 的统一验证切片已独立可复现，可进入 Phase 4；Feature 001
仍未完成，不能据此标记 `VERIFIED`。**


## T014～T018：Phase 4 初始 Red / characterization

**执行时间**：2026-09-03 UTC（Java 17.0.20.1、Maven Wrapper 3.9.16、Spring Boot 4.1.1）。

本轮先运行测试再修改生产代码。失败均保留为目标行为缺失的真实证据；没有通过改名、
放宽断言或删除既有测试来制造通过。

| 任务 | 命令/范围 | 实际结果 | 结论 |
| --- | --- | --- | --- |
| T014 | ./mvnw -B -ntp -pl agent-llm -am test | 12 tests；Failures 9、Errors 0、Skipped 0；LLM 失败语义测试未抛出预期异常，三个端口覆盖测试发现旧共享 OpenAiCompatibleModelClient 同时占用接口 | Red，符合待实现行为 |
| T015 | ./mvnw -B -ntp -pl agent-rag -am test | 2 tests；默认无 RAG 断言失败（发现 ragRetriever Noop Bean），应用自定义唯一断言通过；Failures 1 | Red/characterization |
| T016 | ./mvnw -B -ntp -pl agent-tool -am test | 4 tests；Failures 0、Errors 0、Skipped 0；真实空注册表、自定义覆盖和 MCP 不自动注册均已满足 | characterization 通过 |
| T017 | ./mvnw -B -ntp -pl agent-web -Dtest=AgentWebAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test（模块依赖已先以 -DskipTests install 安装） | 6 tests；Failures 4、Errors 0、Skipped 0；默认选择 Redis memory，应用 Memory/Recorder 覆盖被 @Primary 实现夺取，缺 RAG 仍由 Noop 伪装；Planner/Runtime 覆盖通过 | Red/characterization |
| T018 | ./mvnw -B -ntp -pl agent-demo -Dtest=AgentFlowDemoContextTest -Dsurefire.failIfNoSpecifiedTests=false test | H2 上下文真实启动；扫描发现 8 个 JPA Repository；1 test 在 Bean 来源断言处失败，当前模型端口仍来自 OpenAiCompatibleModelClient；未 Mock Runtime、Service 或 Repository，测试专用扫描成功排除 KnowledgeBootstrapConfig | Red/characterization |

T018 的外部边界仅提供了测试作用域的 StringRedisTemplate Mock、H2 和测试 JWT；没有触发
Qdrant/模型网络调用。T019 记录完成，下一步按约束顺序进入 T020，再进入 T021。


## T020：LLM 适配器明确失败语义

**变更**：

- 新增 agent-llm/src/main/java/com/agentflow/llm/ModelClientException.java，将配置和
  Provider 边界错误统一为适配器模块本地异常。
- OpenAiCompatibleModelClient 在 chat、stream、embedding 调用前检查 Key；缺 Key、
  Provider HTTP/读取错误、空响应、空内容和无效 embedding 均抛出明确异常。
- 删除生产路径的本地固定回答、SHA-256 确定性 embedding 和静默降级；成功响应仍保留
  Provider 内容、用量和流式 delta。

**验证命令**：./mvnw -B -ntp -pl agent-llm -am test

T020 行为测试在 T021 适配器拆分后共同运行：LLM 模块共 14 tests，Failures/Errors/Skipped
均为 0/0/0，退出码 0。缺 Key、Provider 错误、空响应/内容和无效向量均由
ModelClientException 覆盖，成功 chat/stream 解析也通过。

**T020 结论：通过。**

## T021：三个独立 LLM 适配器与端口退让

**变更**：

- OpenAiCompatibleModelClient 收敛为不实现核心端口的共享 HTTP/JSON 传输。
- 新增 OpenAiAgentModelClient、OpenAiChatModelClient、OpenAiEmbeddingClient，分别
  实现 AgentModelClient、ChatModelClient、EmbeddingClient。
- AgentLlmAutoConfiguration 为三个接口分别使用 ConditionalOnMissingBean；共享传输
  只作为内部依赖，不再同时占用三个端口。
- T014 的请求、流式、缺 Key、Provider 错误、空响应/内容和 embedding 失败测试迁移到
  三个适配器测试类；旧组合测试文件已删除，未保留确定性 embedding 成功断言。

**验证命令**：./mvnw -B -ntp -pl agent-llm -am test

| 测试类 | 测试数 | 结果 |
| --- | ---: | --- |
| AgentLlmAutoConfigurationTest | 4 | 通过 |
| OpenAiChatModelClientTest | 5 | 通过 |
| OpenAiAgentModelClientTest | 2 | 通过 |
| OpenAiEmbeddingClientTest | 3 | 通过 |

LLM 模块及其 core 依赖均 SUCCESS；总计 14 tests，Failures/Errors/Skipped = 0/0/0，
退出码 0。三个覆盖场景均验证应用自定义端口仍可独立保留，默认 Bean 使用三个不同适配器
类型。

**T021 结论：通过。可以进入 T022～T025。**


## T022：移除默认 Noop RAG

**变更**：AgentRagAutoConfiguration 不再注册任何框架级 RagRetriever；删除
agent-rag/src/main/java/com/agentflow/rag/NoopRagRetriever.java。Demo 的 KnowledgeRagRetriever
仍由应用自身提供，RAG 是否可选留给后续 Feature 005。

**验证命令**：./mvnw -B -ntp -pl agent-rag -am test

2 tests 通过，Failures/Errors/Skipped = 0/0/0，退出码 0。无应用实现时上下文没有
RagRetriever；提供自定义实现时保持唯一。

**T022 结论：通过。**

## T023：ToolRegistry 条件显式化

**变更**：AgentToolAutoConfiguration 的默认 Bean 改为显式
ConditionalOnMissingBean(ToolRegistry.class)，保留真实 InMemoryToolRegistry 空集合语义；
McpToolProvider 仍是普通类，不自动注册。

**验证命令**：./mvnw -B -ntp -pl agent-tool -am test

AgentToolAutoConfigurationTest 3 tests、既有 InMemoryToolRegistryTest 1 test 均通过；
模块总计 4 tests，Failures/Errors/Skipped = 0/0/0，退出码 0。

**T023 结论：通过。**

## T024：Web 可替换端口显式装配

**变更**：

- 从 JpaStepRecorder 和 RedisShortTermMemory 移除 Component/Primary；二者不再因组件扫描
  隐式竞争。
- AgentWebAutoConfiguration 移除这两个类及 NoopStepRecorder 的 Import，保留显式
  JpaStepRecorder Bean，并针对 TaskPlanner、ShortTermMemory、StepRecorder、AgentRuntime
  使用接口级 ConditionalOnMissingBean。
- 删除 NoopStepRecorder；默认记忆为 InMemoryShortTermMemory，Redis memory 仅由应用显式
  提供。
- 旧 Demo 入口测试显式指定主应用配置，避免 T018 测试配置被自动探测为第二个启动配置。

**验证命令**：./mvnw -B -ntp -pl agent-web -am test

Web 模块及依赖模块均 SUCCESS；总计 11 tests，Failures/Errors/Skipped = 0/0/0，退出码 0。
缺 RAG 场景真实启动失败并由测试断言，Planner/Memory/Recorder/Runtime 覆盖和现有安全/
服务测试均通过。

**T024 结论：通过。**

## T025：知识导入显式开关

**变更**：KnowledgeBootstrapConfig 增加
ConditionalOnProperty(name = agentflow.knowledge.bootstrap.enabled, havingValue = true,
matchIfMissing = true)。T018 的 application-test.yml 设置该属性为 false，测试组件扫描
不再排除 KnowledgeBootstrapConfig，仍提供测试作用域 Redis 边界替身。

**验证命令**：./mvnw -B -ntp -pl agent-demo -am test

Demo 完整上下文真实启动，H2 扫描并创建 8 个 JPA Repository；AgentFlowDemoContextTest
和既有 AgentWebEndpointRegistrationTest 均通过，Demo 侧总计 3 tests，Failures/Errors/
Skipped = 0/0/0，退出码 0。没有调用 Qdrant、真实模型或 Redis。

**T025 结论：通过。**


## T026：Phase 4 受影响模块门禁

按任务要求依次执行：

- ./mvnw -B -ntp -pl agent-llm -am test：14 tests，Failures/Errors/Skipped = 0/0/0。
- ./mvnw -B -ntp -pl agent-rag -am test：2 tests，Failures/Errors/Skipped = 0/0/0。
- ./mvnw -B -ntp -pl agent-tool -am test：4 tests，Failures/Errors/Skipped = 0/0/0。
- ./mvnw -B -ntp -pl agent-web -am test：11 tests，Failures/Errors/Skipped = 0/0/0。
- ./mvnw -B -ntp -pl agent-demo -am test：3 tests，Failures/Errors/Skipped = 0/0/0。
- git diff --check：退出码 0。

所有命令退出码均为 0。覆盖结果包括三个 LLM 端口的独立退让、缺 Key/Provider/空响应/
无效 embedding 的明确失败、无默认 RAG、真实空 ToolRegistry、Web 默认与应用覆盖、缺 RAG
启动失败、JPA recorder/in-memory memory 来源，以及正式知识开关下的完整 Demo 上下文。

**T026 结论：通过。Phase 4 T014-T026 已全部取得验证证据，可进入 Phase 4 checkpoint
审查和提交。**


## T027～T030：Phase 5 初始边界、入口与凭据测试

**执行时间**：2026-09-03 UTC（Java 17.0.20.1、Maven Wrapper 3.9.16、Spring Boot 4.1.1）。
行为测试均先于对应生产改动运行；失败内容对应目标行为缺失，不通过放宽断言或删除测试制造通过。

| 任务 | 命令/范围 | 实际结果 | 结论 |
| --- | --- | --- | --- |
| T027 | `./mvnw -B -ntp -pl agent-core -am test` | ArchUnit 1 条边界测试通过，Failures/Errors/Skipped = 0/0/0 | characterization，通过 |
| T028 | `./mvnw -B -ntp -pl agent-demo -am -Dtest=TemporaryDemoEndpointSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test` | 2 tests 通过；精确发现 Auth 4、Chat 2、Agent 4、Knowledge 1 共 11 个操作，并完成四组受控 MockMvc 分发 | characterization，通过 |
| T029（LLM） | `./mvnw -B -ntp -pl agent-llm -am -Dtest=AgentFlowPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test` | 2 tests，Failures 1、Errors 0、Skipped 0；`AgentFlowProperties` 仍返回通用开发 JWT secret | Red，符合待实现行为 |
| T029（Demo） | `./mvnw -B -ntp -pl agent-demo -am -Dtest=InitialDataConfigTest -Dsurefire.failIfNoSpecifiedTests=false test` | 3 tests，Failures 3、Errors 0、Skipped 0；无凭据仍查询/创建 `admin`，半配置不失败，完整显式配置未被使用 | Red，符合待实现行为 |
| T029（Web） | `./mvnw -B -ntp -pl agent-web -am -Dtest=SecurityConfigCredentialTest -Dsurefire.failIfNoSpecifiedTests=false test` | 3 tests，Failures 2、Errors 0、Skipped 0；缺失或少于 32 字节的 JWT secret 当前未拒绝，显式长 secret 场景通过 | Red，符合待实现行为 |

T030 汇总结论：上述失败均能追溯到生产默认凭据或缺少校验；T027/T028 的通过结果记录为
既有边界/入口 characterization。下一步按 T031 实施凭据来源和明确失败语义，测试值只保留在
`src/test`。


## T031：生产凭据来源与明确失败语义

**变更**：

- `AgentFlowProperties` 的 JWT secret 生产默认值改为空，并补齐标准 JavaBean getter，确保
  Boot 4 配置绑定与显式环境变量一致。
- `SecurityConfig` 在 JWT secret 缺失或 UTF-8 长度小于 32 字节时抛出明确配置错误。
- `InitialDataConfig` 只有在 `AGENTFLOW_INITIAL_ADMIN_USERNAME` 和
  `AGENTFLOW_INITIAL_ADMIN_PASSWORD` 同时显式提供时才创建用户；半配置直接失败，未配置
  时不访问用户仓库。
- `agent-demo/src/main/resources/application.yml` 的数据库和 JWT 配置不再含通用默认值；
  `OpenAiCompatibleModelClient` 同时移除残留的历史 `change-me` sentinel。

**验证命令及结果**：

| 命令 | 实际结果 |
| --- | --- |
| `./mvnw -B -ntp -pl agent-llm -am -Dtest=AgentFlowPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test` | 3 tests，Failures/Errors/Skipped = 0/0/0，退出码 0 |
| `./mvnw -B -ntp -pl agent-web -am -Dtest=SecurityConfigCredentialTest,AgentWebAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 9 tests，Failures/Errors/Skipped = 0/0/0，退出码 0 |
| `./mvnw -B -ntp -pl agent-demo -am -Dtest=InitialDataConfigTest,TemporaryDemoEndpointSnapshotTest,AgentFlowDemoContextTest -Dsurefire.failIfNoSpecifiedTests=false test` | 6 tests，Failures/Errors/Skipped = 0/0/0，退出码 0 |

**T031 结论：通过。** 测试值只位于 `src/test` 配置或测试代码，生产启动不再依赖通用
JWT、数据库或管理员凭据。

## T032：非秘密环境变量示例与 Compose/页面治理

**变更**：新增根目录 `.env.example`；Compose 的 MySQL root/application 密码改为必填环境
变量引用；登录页面移除固定管理员用户名/密码并改为配置提示。`.env.example` 只包含
`CHANGE_ME_*` 占位符、连接地址和变量名，不含可用密钥。

**静态检查结果**：

- `docker-compose.yml` 仅使用 `${MYSQL_ROOT_PASSWORD:?...}`、`${MYSQL_PASSWORD:?...}`
  和其他环境引用；健康检查通过容器环境变量读取密码。
- `agent-demo/src/main/resources/static/index.html` 不含固定 `admin`、通用密码或默认 Key。
- `git diff --check` 退出码 `0`。

**T032 结论：通过。**

## T033～T035：正式文档与当前边界同步

已更新 README、SDD 使用说明、测试、架构、模块、配置、REST API、数据库和部署文档：

- 当前 Feature 001 状态、唯一 Wrapper 门禁和外部服务隔离均改为当前事实；移除无激活 Feature
  和旧测试统计。
- 自动配置表、三个 LLM 端口、无默认 RAG/Recorder、Runtime 暂留 Web 的批准例外及
  Feature 002/003/005/006/008 的退出点与源码一致。
- 固定登录/数据库/JWT/模型凭据全部改为环境变量或非秘密占位符；模型缺 Key 的行为写为
  明确失败，不再描述本地成功兜底。
- 11 个 HTTP 操作标记为临时 Demo 快照；SSE 回放/缓存只作为当前实现观察，不作稳定协议或
  高可用承诺。
- `docs/learn/` 学习快照未纳入本阶段修改；其中已有工作区改动按用户文件原样保留。

**T033、T034、T035 结论：通过。**

## T036：Phase 5 边界、入口和凭据门禁

**目标测试**：

- 串行执行 `./mvnw -B -ntp -pl agent-core -am test`：1 test，Failures/Errors/Skipped =
  0/0/0，退出码 0。
- 串行执行 `./mvnw -B -ntp -pl agent-demo -am test`：6 个 Demo 测试，Reactor 总计
  44 tests，Failures/Errors/Skipped = 0/0/0，退出码 0；六个模块均 SUCCESS。
- `git diff --check`：退出码 0。

曾尝试并行启动 core 和 Demo 门禁，因两个 Maven 进程同时写共享 `agent-core/target/surefire`
而出现一次 surefire 临时包启动错误（Demo 路径退出码 1）；这是并行构建竞争，不是测试
断言失败。随后清理并串行重跑成功，最终证据只采用串行结果。

**凭据扫描**：按 `plan.md` 固定范围扫描 `agent-*/src/main/**`、`docker-compose.yml`、
`README.md`、`specs/` 和 `docs/`，排除 `docs/learn/**` 及 Git 忽略的
`docs/.ipynb_checkpoints/**` 生成检查点；检查 API key、JWT secret、数据库/管理员 password
的非空字面量以及已知历史默认指纹。环境变量引用、`CHANGE_ME_*`/`<...>` 等明确非秘密占位符、
动态表达式和 `src/test/**` 测试值允许。

实际扫描输出：

```text
CREDENTIAL_SCAN_EXIT=0
FILES_SCANNED=125
EXCLUDED=docs/.ipynb_checkpoints/**,docs/learn/**
HISTORICAL_FINGERPRINT_HITS=0
NON_EMPTY_LITERAL_HITS=0
```

临时入口证据仍为 Auth 4、Chat 2、Agent 4、Knowledge 1，共 11 个操作；四组代表性
MockMvc 分发和完整 Demo 上下文均通过。

**T036 结论：通过。Phase 5 T027-T036 已全部取得证据，可创建 Phase 5 完成提交。**

## T037：Phase 6 完整验证与环境证据

**执行环境**：

| 项目 | 实际结果 |
| --- | --- |
| 操作系统 | Linux 6.8.0-111-generic, amd64 |
| Java | Eclipse Temurin/OpenJDK 17.0.20.1 |
| Maven | Wrapper 3.9.16（only-script） |
| Spring Boot | 4.1.1 |
| 外部服务 | 未启动 MySQL、Redis、Qdrant；未提供真实模型 Key |

**Wrapper 检查**：./mvnw --version 退出码 0，输出确认 Maven 3.9.16、Java 17 和 Linux
运行环境。

**串行聚焦门禁**（避免多个 Maven 进程共享 target 造成 surefire 临时文件竞争）：

| 命令 | Reactor/测试结果 | 退出码 |
| --- | --- | ---: |
| ./mvnw -B -ntp -pl agent-core -am test | core 1 test；Failures/Errors/Skipped = 0/0/0 | 0 |
| ./mvnw -B -ntp -pl agent-llm -am test | LLM 17 tests；Failures/Errors/Skipped = 0/0/0 | 0 |
| ./mvnw -B -ntp -pl agent-web -am test | Web 14 tests；Failures/Errors/Skipped = 0/0/0 | 0 |
| ./mvnw -B -ntp -pl agent-demo -am test | Demo 6 tests；依赖模块均 SUCCESS；Failures/Errors/Skipped = 0/0/0 | 0 |

**唯一完整门禁**：./mvnw -B -ntp clean verify

- 统一进程开始：2026-09-03T01:39:19+08:00
- 统一进程结束：2026-09-03T01:39:59+08:00
- 墙钟耗时：40 秒（Maven 输出 Total time: 38.236 s）
- Reactor：根 POM + 6 个模块，共 7 个项目，全部 SUCCESS
- 测试：core 1 + LLM 17 + Tool 4 + RAG 2 + Web 14 + Demo 6 = 44 tests
- Failures / Errors / Skipped：0 / 0 / 0
- 命令退出码：0
- SC-009 判定：40 <= 600，**PASS**
- CI 工作流位置：.github/workflows/verify.yml；使用同一 ./mvnw -B -ntp clean verify
- 未验证限制：该分支尚未推送前，GitHub Actions 尚无远端运行记录；推送后需核对工作流结果。

**T037 结论：本地验证通过；远端 CI 结果待推送后确认。**

## T038：最终需求与成功标准证据矩阵

以下矩阵逐项对应 spec.md 的 FR-001～FR-014 与 SC-001～SC-011；“通过”仅基于已执行的
本地证据，远端 CI 仍按 T037 的限制单独确认。

### Functional Requirements

| ID | 证据 | 结果 |
| --- | --- | --- |
| FR-001 | Maven Wrapper、./mvnw -B -ntp clean verify；7 个项目 SUCCESS、44 tests | PASS |
| FR-002 | 完整门禁在无模型 Key、无 Docker/外部运行服务下退出 0；H2/Mock HTTP 仅在测试边界使用 | PASS |
| FR-003 | .github/workflows/verify.yml 与本地均调用同一 Wrapper 命令；远端执行待推送 | PASS（配置证据） |
| FR-004 | AgentFlowDemoContextTest 及 Demo 完整上下文：Controller/Service/8 个 JPA Repository、Runtime 和适配器来源断言 | PASS |
| FR-005 | 各模块精确 AutoConfiguration.imports、Demo 无扩大根包扫描；自动配置测试通过 | PASS |
| FR-006 | LLM 三端口、ToolRegistry、Planner/Memory/Recorder/Runtime 的应用覆盖测试通过 | PASS |
| FR-007 | 缺 Key、Provider 错误、空响应/内容、无效 embedding、缺 RAG 均有明确失败测试 | PASS |
| FR-008 | 生产路径无本地答案、确定性向量、Noop RAG/Recorder；边界测试和扫描通过 | PASS |
| FR-009 | 无默认 RAG、未注册 MCP Provider、真实空 ToolRegistry 的测试和装配结果明确 | PASS |
| FR-010 | CoreDependencyBoundaryTest（ArchUnit）通过，生产 core 禁止框架/存储/HTTP/厂商类型 | PASS |
| FR-011 | TemporaryDemoEndpointSnapshotTest 精确断言 Auth 4、Chat 2、Agent 4、Knowledge 1 共 11 操作，并完成四组 MockMvc 分发 | PASS |
| FR-012 | POM 固定 Java 17/Boot 4.1.1；3.5.x 过渡和迁移证据已记录；完整门禁通过 | PASS |
| FR-013 | 模型适配器使用 Mock HTTP；完整 Demo 使用 H2，未调用付费模型 | PASS |
| FR-014 | 生产范围凭据扫描非空字面量 0，初始化凭据仅环境变量/测试配置 | PASS |

### Success Criteria

| ID | 证据 | 结果 |
| --- | --- | --- |
| SC-001 | Wrapper 单一命令完成 7 个项目编译、测试和打包，44 tests 全通过 | PASS |
| SC-002 | 无真实模型 Key、MySQL、Redis、Qdrant 或 Docker 时完整门禁退出 0 | PASS |
| SC-003 | 本地与 CI 配置使用完全相同的 Wrapper 命令；远端同提交结果待推送确认 | PASS（配置证据） |
| SC-004 | Demo 上下文发现全部已记录组件和 11 个承诺入口 | PASS |
| SC-005 | 每个记录的替换点均有 ApplicationContextRunner/上下文覆盖断言 | PASS |
| SC-006 | 缺凭据、缺提供者和无效响应场景均为真实失败，未返回伪成功 | PASS |
| SC-007 | ArchUnit 核心依赖违规数为 0 | PASS |
| SC-008 | 11/11 HTTP 操作被标记临时 Demo，注册与四组基本分发均通过 | PASS |
| SC-009 | 统一门禁墙钟 40 秒，40 <= 600 | PASS |
| SC-010 | Java 17 + Boot 4.1.1 全仓 44 tests 通过；Spring AI 未声明已验证兼容 | PASS |
| SC-011 | 凭据扫描 NON_EMPTY_LITERAL_HITS=0、HISTORICAL_FINGERPRINT_HITS=0、退出码 0 | PASS |

**T038 凭据/Noop 扫描**：按 plan.md 固定范围扫描 agent-*/src/main/**、docker-compose.yml、
README.md、specs/ 和 docs/，排除 docs/learn/** 与 docs/.ipynb_checkpoints/**；环境变量、
明确非秘密占位符、动态表达式和测试值按规则允许。实际结果：

    FILES_SCANNED=120
    EXCLUDED=docs/learn/**,docs/.ipynb_checkpoints/**,**/src/test/**
    HISTORICAL_FINGERPRINT_HITS=0
    NON_EMPTY_LITERAL_HITS=0
    CREDENTIAL_SCAN_EXIT=0

**T038 结论：本地 FR/SC 矩阵和扫描均通过；SC-003 的远端执行记录留待推送后补充。**

## T039：speckit-analyze 一致性复核

按 speckit-analyze skill 的只读流程重新执行 prerequisites、读取 constitution/spec/plan/tasks，
并检查需求覆盖、任务 ID、阶段依赖、术语和状态元数据。

- 需求清单：14 个 FR + 11 个 SC，共 25 项。
- 任务清单：T001～T041，共 41 个唯一且连续 ID。
- 需求覆盖：25/25，覆盖率 100%；T001/T002 与 T039～T041 由 GOV-001 追踪。
- 初次复核发现 2 项 MEDIUM 事实漂移：spec.md 的分支仍写“尚未创建”，plan.md 仍写工作区
  在 main 且任务树写“待实施”。
- 已修正为当前 feature/001-engineering-baseline-starter 分支、当前工作区归属和 Phase 6
  收尾状态；并将 README 的 agent-core 模块职责、plan 的 core 目录树同步为当前 Runtime 暂在
  agent-web 的事实；未改变产品需求、接口或架构决策。
- 修订后重跑覆盖检查：25/25 需求、41 个任务；CRITICAL：0；HIGH：0；未发现需求零覆盖、
  宪法冲突、未决占位符或无映射任务。
- checklists/ 中没有未勾选条目。

T039 的分析只读约束已遵守；文档修正是任务明确授权的后续动作。**T039 结论：通过。**

## T040：speckit-converge 收敛检查

按 speckit-converge skill 的前置检查和只读意图清单，对照 spec.md、plan.md、tasks.md、
宪法及当前代码范围执行收敛审查。

- 检查需求/验收项：25（14 FR + 11 SC）。
- 检查计划决策：Wrapper/CI、Boot 4.1.1、精确自动配置、端口覆盖、真实失败语义、core
  边界、临时 HTTP 快照、凭据治理和文档同步。
- 检查宪法 MUST：核心纯 Java、测试优先、真实失败、凭据安全、精确装配和 Git/验证门禁。
- 生产路径、自动配置 imports、Noop 删除、临时 Runtime 例外、CI 统一命令、必需测试与
  证据均已找到并符合文档。
- Findings：0（missing 0、partial 0、contradicts 0、unrequested 0）。
- Convergence outcome：CLEAN；没有向 tasks.md 追加新 Phase 或任务。

远端 GitHub Actions 尚未在推送前运行，这是外部验证状态，不是当前代码范围的未实现缺口。
**T040 结论：通过。**

## T041：Feature 001 最终收尾门禁

在 T001～T040 均完成、最终状态元数据同步、analyze 复核和 converge 收敛检查通过后，执行
最终收尾审查。

- `spec.md` 状态已更新为 `VERIFIED`。
- `specs/ROADMAP.md` 的 Feature 001 状态已更新为 `VERIFIED`；README 与 `specs/README.md`
  的状态说明同步为本地验收完成、远端 CI 待核对。
- T001～T041 共 41 个任务均已标记 `[X]`；当前分支为
  `feature/001-engineering-baseline-starter`。
- 最终差异审查确认没有暂存或计划纳入 `docs/learn/`、`.ua/`、密钥、后续 Feature 或无关
  用户文件；用户已有的学习资料、治理文件和未纳入本阶段的工作区改动保持原样。

**最终全仓门禁**：`./mvnw -B -ntp clean verify`

- 统一进程开始：2026-09-03T02:03:14+08:00
- 统一进程结束：2026-09-03T02:03:53+08:00
- 墙钟耗时：39 秒（Maven 输出 `Total time: 37.419 s`）
- Reactor：根 POM + 6 个模块，共 7 个项目，全部 `SUCCESS`
- 测试：core 1 + LLM 17 + Tool 4 + RAG 2 + Web 14 + Demo 6 = 44 tests
- Failures / Errors / Skipped：0 / 0 / 0
- 命令退出码：0
- SC-009 判定：39 <= 600，**PASS**

**差异检查**：`git diff --check` 退出码 0。

**远端限制**：最终分支尚未推送，GitHub Actions 的远端结果尚无记录；推送后必须核对
`.github/workflows/verify.yml` 的实际运行结果。

**T041 结论：本地最终收尾门禁通过，可创建 Phase 6 完成提交；远端 CI 结果待推送确认。**
