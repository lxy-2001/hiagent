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
