# 测试与质量保障说明

本文档描述当前 Feature 001 已验证的测试入口和边界。它不把未来 Feature 的测试能力
（例如 Runtime 新语义、真实 RAG、MCP 或端到端基础设施）提前算作完成。

## 1. 当前工程基线

| 项目 | 已验证值 |
| --- | --- |
| Java | 17 |
| Spring Boot | 4.1.1 |
| Maven | Wrapper 3.9.16 |
| Maven 模块 | 6 个：agent-core、agent-llm、agent-tool、agent-rag、agent-web、agent-demo |
| 最近一次完整门禁 | 11 个测试通过；Failures/Errors/Skipped = 0/0/0 |
| 完整门禁 | `./mvnw -B -ntp clean verify` |

首次使用 Wrapper 或本地缓存为空时，需要访问 Maven Central 下载 Maven 发行包和项目
依赖。依赖缓存完成后，默认测试不需要 Docker、MySQL、Redis、Qdrant、真实模型 API Key
或其他外部运行服务。

## 2. 唯一完整验证入口

在仓库根目录运行：

```bash
./mvnw --version
./mvnw -B -ntp clean verify
```

第一条命令应显示 Maven 3.9.16 和 Java 17。第二条命令由本地和 GitHub Actions 共用，
会构建六个模块并执行可发现的测试；任一失败都返回非零状态。

当前已验证的 Phase 2 结果：

- 根 POM 和六个模块均构建成功。
- 11 个测试通过，Failures、Errors、Skipped 均为 0。
- agent-core 与 agent-rag 当前没有测试源，构建仍纳入完整门禁。
- Demo 上下文使用 H2 测试数据库；模型 HTTP 使用 Mock；不访问真实付费服务。
- 详细命令、耗时和迁移证据见 [Feature 001 验证记录](../specs/001-engineering-baseline-starter/verification.md)。

开发单个模块时可先运行聚焦门禁：

```bash
./mvnw -pl agent-core -am test
./mvnw -pl agent-llm -am test
./mvnw -pl agent-web -am test
./mvnw -pl agent-demo -am test
```

聚焦命令用于缩短反馈时间，不能替代 Phase 或 Feature 收尾时的 `clean verify`。

## 3. 当前测试清单

当前有 7 个测试类、11 个测试方法：

| 测试类 | 模块 | 已验证内容 |
| --- | --- | --- |
| `OpenAiCompatibleModelClientTest` | agent-llm | 受控 Mock HTTP 下的同步、流式和 embedding 请求 |
| `InMemoryToolRegistryTest` | agent-tool | 工具注册、查找和启用/禁用 |
| `ChatControllerSecurityTest` | agent-web | Chat 入口未认证请求的 401 安全行为 |
| `ChatServiceTest` | agent-web | Chat Service 的当前同步/流式协作 |
| `SimpleTaskPlannerTest` | agent-web | 当前规划器规则 |
| `DefaultAgentRuntimeTest` | agent-web | 当前 Runtime 循环、步骤记录和事件发布 |
| `AgentWebEndpointRegistrationTest` | agent-demo | H2 完整上下文、Web 入口注册和认证任务分发 |

测试文件位于各模块的 `src/test` 目录。测试名称和覆盖范围以源代码为准；文件存在不等于
未来 Agent 能力已经实现。

## 4. 测试类型与边界

### 4.1 单元测试

单元测试在不启动完整 Spring 应用的情况下验证一个类或一个协作边界，例如
`InMemoryToolRegistryTest`、`SimpleTaskPlannerTest` 和当前 LLM 客户端测试中的
请求解析。外部 HTTP 使用 Mock，不发送真实模型请求。

### 4.2 Spring 组件/切片测试

`ChatControllerSecurityTest` 使用 Boot 4 的 `@WebMvcTest` 和 `MockMvc`，只加载
Web MVC 测试切片与测试专用安全配置。它验证当前 Chat 入口的安全响应，不冻结最终 Run/SSE
契约。

### 4.3 完整应用上下文测试

`AgentWebEndpointRegistrationTest` 使用 `@SpringBootTest`、H2 和测试替身启动
Demo 上下文，检查当前模块自动配置、Controller、Repository 和接口分发。测试不会连接
生产数据库、Redis、Qdrant 或真实模型。

### 4.4 尚未纳入本基线的测试

agent-core 的架构依赖检查、各端口条件装配覆盖、真实失败语义、11 个临时操作完整快照、
凭据扫描和后续 Agent 能力测试，会在 Feature 001 的后续 Phase 或路线图 Feature 中按
任务单独加入。当前不能用本节的 11 个测试代表这些内容已完成。

## 5. 测试编写规则

- 行为变更先写一个在现状下失败的测试，再实现最小行为，最后在测试保护下重构。
- 默认测试只使用确定性 Fixture、Mock HTTP 或 H2；不得读取真实 Key、生产数据或付费服务。
- 不删除、跳过或弱化失败测试来获得绿色构建。
- 失败、取消、超时、预算耗尽和未配置能力必须有可观察结果，不能用 Noop、Fake 或固定
  成功内容掩盖问题。
- 测试应说明被验证的需求或边界；纯构建、Wrapper 和文档变更使用直接命令校验。
- Phase 完成时扩大到受影响模块及依赖模块；Feature 完成时运行全仓 `clean verify`。

## 6. GitHub Actions

工作流文件为 `.github/workflows/verify.yml`，当前配置：

- 在 `push` 和 `pull_request` 触发。
- 使用只读 `contents` 权限。
- 使用 Temurin Java 17 和 Maven 缓存。
- 单个 Job 超时 15 分钟。
- 只运行 `./mvnw -B -ntp clean verify`。
- 不配置模型 secrets、数据库/Redis/Qdrant service container，也不执行真实模型调用。

本地与 CI 使用同一个 Wrapper 命令，避免两套不一致的测试清单。远端工作流是否通过，以
GitHub Actions 的实际运行结果为准。

## 7. 覆盖率和端到端测试状态

当前 Feature 001 尚未引入覆盖率门槛、Testcontainers 或产品级端到端环境。它们是否需要、
以及适用的指标，应在对应 Feature 的规格中单独决定；不能把未运行的报告写成当前质量
证据。

## 8. 相关文档

- [Feature 001 快速验证](../specs/001-engineering-baseline-starter/quickstart.md)
- [Feature 001 规格](../specs/001-engineering-baseline-starter/spec.md)
- [项目宪法](../.specify/memory/constitution.md)
- [代理实施规则](../AGENTS.md)
