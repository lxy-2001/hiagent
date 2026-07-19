# 测试与质量保障说明

## 1. 测试概览

### 1.1 测试统计

| 指标 | 值 |
|------|-----|
| 测试类数量 | 6 |
| 测试框架 | JUnit 5 + AssertJ + Mockito |
| 测试类型 | 单元测试 + 组件测试 |
| 覆盖率工具 | 无（待集成） |

### 1.2 测试文件清单

| 测试类 | 模块 | 测试内容 |
|--------|------|---------|
| `OpenAiCompatibleModelClientTest` | agent-llm | LLM 客户端 |
| `InMemoryToolRegistryTest` | agent-tool | 工具注册表 |
| `ChatServiceTest` | agent-web | 聊天服务 |
| `ChatControllerSecurityTest` | agent-web | 聊天控制器安全 |
| `SimpleTaskPlannerTest` | agent-web | 任务规划器 |
| `DefaultAgentRuntimeTest` | agent-web | Agent 运行时 |

## 2. 测试详情

### 2.1 OpenAiCompatibleModelClientTest

**文件**：`agent-llm/src/test/java/com/agentflow/llm/OpenAiCompatibleModelClientTest.java`

**测试框架**：JUnit 5 + AssertJ + MockRestServiceServer

**测试内容**：
- 同步对话调用
- 流式对话调用
- 无 API Key 时的兜底回答
- Embedding 调用

**测试方式**：
- 使用 `MockRestServiceServer` 模拟 HTTP 请求
- 验证请求体和响应解析

### 2.2 InMemoryToolRegistryTest

**文件**：`agent-tool/src/test/java/com/agentflow/tool/InMemoryToolRegistryTest.java`

**测试框架**：JUnit 5

**测试内容**：
- 工具注册
- 工具查找
- 启用/禁用工具

### 2.3 ChatServiceTest

**文件**：`agent-web/src/test/java/com/agentflow/web/chat/ChatServiceTest.java`

**测试框架**：JUnit 5 + AssertJ

**测试内容**：
- 同步对话
- 流式对话
- 会话历史管理

### 2.4 ChatControllerSecurityTest

**文件**：`agent-web/src/test/java/com/agentflow/web/chat/ChatControllerSecurityTest.java`

**测试框架**：JUnit 5 + MockMvc + @WebMvcTest + Mockito

**测试内容**：
- 未认证请求返回 401
- 认证后正常访问

**测试方式**：
- 使用 `@WebMvcTest` 切片测试
- 使用 `@MockitoBean` 模拟依赖
- 使用 `MockMvc` 发送请求

### 2.5 SimpleTaskPlannerTest

**文件**：`agent-web/src/test/java/com/agentflow/web/autoconfigure/SimpleTaskPlannerTest.java`

**测试框架**：JUnit 5

**测试内容**：
- 关键词匹配规则
- 工具选择逻辑

### 2.6 DefaultAgentRuntimeTest

**文件**：`agent-web/src/test/java/com/agentflow/web/autoconfigure/DefaultAgentRuntimeTest.java`

**测试框架**：JUnit 5

**测试内容**：
- Agent 执行循环
- 步骤记录
- 事件发布

## 3. 测试类型

### 3.1 单元测试

**定义**：测试单个类或方法，不依赖外部系统。

**示例**：
- `InMemoryToolRegistryTest`：测试工具注册表的内存操作
- `SimpleTaskPlannerTest`：测试关键词匹配逻辑

**特点**：
- 执行速度快
- 不需要启动 Spring 容器
- 使用 Mock 替代依赖

### 3.2 组件测试

**定义**：测试 Spring 组件的集成行为。

**示例**：
- `ChatControllerSecurityTest`：测试 Spring Security 配置
- `ChatServiceTest`：测试 Service 层逻辑

**特点**：
- 需要启动部分 Spring 容器
- 使用 `@WebMvcTest`、`@SpringBootTest` 等注解
- 可以测试依赖注入和配置

### 3.3 集成测试

**定义**：测试多个组件的协作。

**当前状态**：无

**建议**：
- 测试完整的 Agent 执行流程
- 测试数据库操作
- 测试外部服务调用

### 3.4 端到端测试

**定义**：测试完整的用户场景。

**当前状态**：无

**建议**：
- 测试完整的 API 调用链
- 使用 Testcontainers 启动真实基础设施

## 4. 测试框架

### 4.1 JUnit 5

**依赖**：

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

**使用方式**：

```java
@Test
void testSomething() {
    // given
    // when
    // then
}

@ParameterizedTest
@ValueSource(strings = {"sql", "表", "mysql"})
void testKeywordMatching(String keyword) {
    // ...
}
```

### 4.2 AssertJ

**依赖**：

```xml
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

**使用方式**：

```java
assertThat(result).isNotNull();
assertThat(result.toolNames()).containsExactly("interface-draft", "sql-draft");
assertThat(response.getStatus()).isEqualTo(200);
```

### 4.3 Mockito

**依赖**：

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
```

**使用方式**：

```java
@MockitoBean
private ChatModelClient chatModelClient;

when(chatModelClient.complete(any())).thenReturn(mockResponse);
verify(chatModelClient).complete(any());
```

### 4.4 Spring Boot Test

**依赖**：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

**使用方式**：

```java
@WebMvcTest(ChatController.class)
class ChatControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;
}
```

## 5. 测试覆盖率

### 5.1 当前状态

未集成覆盖率工具。

### 5.2 建议集成 JaCoCo

**pom.xml**：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**运行**：

```bash
mvn test jacoco:report
```

**报告位置**：`target/site/jacoco/index.html`

### 5.3 覆盖率目标

| 模块 | 目标覆盖率 |
|------|-----------|
| agent-core | 90%+ |
| agent-llm | 80%+ |
| agent-tool | 80%+ |
| agent-rag | 80%+ |
| agent-web | 70%+ |
| agent-demo | 60%+ |

## 6. 测试最佳实践

### 6.1 命名规范

```java
// 推荐：方法名_场景_预期结果
@Test
void login_withValidCredentials_returnsToken() { ... }

@Test
void login_withInvalidPassword_throwsException() { ... }
```

### 6.2 测试结构

```java
@Test
void testSomething() {
    // given - 准备数据
    String input = "test";

    // when - 执行操作
    Result result = service.doSomething(input);

    // then - 验证结果
    assertThat(result).isNotNull();
    assertThat(result.getValue()).isEqualTo("expected");
}
```

### 6.3 Mock 使用原则

- 只 Mock 外部依赖（数据库、外部服务）
- 不 Mock 被测试类的内部逻辑
- 使用 `@Spy` 部分 Mock

## 7. CI/CD 集成

### 7.1 当前状态

未配置 CI/CD。

### 7.2 建议的 GitHub Actions

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Start infrastructure
        run: docker-compose up -d

      - name: Wait for services
        run: sleep 30

      - name: Run tests
        run: mvn clean test

      - name: Generate coverage report
        run: mvn jacoco:report

      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## 8. 待确认项

| # | 项目 | 状态 | 说明 |
|---|------|------|------|
| 1 | 覆盖率工具 | 待确认 | 是否集成 JaCoCo？ |
| 2 | 集成测试 | 待确认 | 是否需要集成测试？ |
| 3 | 端到端测试 | 待确认 | 是否需要 E2E 测试？ |
| 4 | CI/CD | 待确认 | 是否需要 GitHub Actions？ |
| 5 | 测试数据库 | 待确认 | 是否使用 H2 内存数据库？ |
| 6 | 性能测试 | 待确认 | 是否需要 JMeter/Gatling？ |
