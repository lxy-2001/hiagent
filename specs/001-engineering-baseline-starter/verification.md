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
