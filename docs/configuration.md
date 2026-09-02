# 配置说明

## 1. 配置文件位置

| 文件 | 位置 | 说明 |
|------|------|------|
| `application.yml` | `agent-demo/src/main/resources/` | 主配置文件 |
| `docker-compose.yml` | 项目根目录 | 基础设施配置 |

## 2. application.yml 完整配置

```yaml
spring:
  application:
    name: agent-demo-dev-assistant
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: none
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3s

server:
  port: 8080

agentflow:
  model:
    provider: deepseek
    base-url: ${AGENTFLOW_MODEL_BASE_URL:}
    api-key: ${AGENTFLOW_MODEL_API_KEY}
    chat-model: deepseek-v4-pro
    embedding-model: ${AGENTFLOW_MODEL_EMBEDDING_MODEL:text-embedding-v4}
    embedding-dimensions: 2048
  tools:
    max-steps: 6
  security:
    refresh-token-ttl: 7d
    jwt:
      secret: ${AGENTFLOW_JWT_SECRET}
      access-token-ttl: 30m
  mcp:
    enabled: false
  initial-admin:
    username: ${AGENTFLOW_INITIAL_ADMIN_USERNAME:}
    password: ${AGENTFLOW_INITIAL_ADMIN_PASSWORD:}

app:
  qdrant:
    base-url: http://localhost:6333
    collection: agentflow_knowledge
```

## 3. 配置项详解

### 3.1 Spring 配置

#### 3.1.1 应用名称

```yaml
spring:
  application:
    name: agent-demo-dev-assistant
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.application.name` | `agent-demo-dev-assistant` | 应用名称 |

#### 3.1.2 数据源配置

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `spring.datasource.url` | 无（必须显式配置） | `SPRING_DATASOURCE_URL` | MySQL 连接 URL |
| `spring.datasource.username` | 无（必须显式配置） | `SPRING_DATASOURCE_USERNAME` | 数据库用户名 |
| `spring.datasource.password` | 无（必须显式配置） | `SPRING_DATASOURCE_PASSWORD` | 数据库密码 |

#### 3.1.3 JPA 配置

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.jpa.hibernate.ddl-auto` | `none` | DDL 策略（由 Flyway 管理） |

#### 3.1.4 Redis 配置

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3s
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `spring.data.redis.host` | `localhost` | `SPRING_DATA_REDIS_HOST` | Redis 地址 |
| `spring.data.redis.port` | `6379` | `SPRING_DATA_REDIS_PORT` | Redis 端口 |
| `spring.data.redis.timeout` | `3s` | - | 连接超时 |

### 3.2 AgentFlow 配置

配置前缀：`agentflow`

**Java 类**：`com.agentflow.llm.AgentFlowProperties`

#### 3.2.1 模型配置

```yaml
agentflow:
  model:
    provider: deepseek
    base-url: ${AGENTFLOW_MODEL_BASE_URL:}
    api-key: ${AGENTFLOW_MODEL_API_KEY}
    chat-model: deepseek-v4-pro
    embedding-model: ${AGENTFLOW_MODEL_EMBEDDING_MODEL:text-embedding-v4}
    embedding-dimensions: 2048
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agentflow.model.provider` | `deepseek` | `AGENTFLOW_MODEL_PROVIDER` | 模型提供商（deepseek/openai） |
| `agentflow.model.base-url` | 空 | `AGENTFLOW_MODEL_BASE_URL` | 自定义 API 地址 |
| `agentflow.model.api-key` | 无（调用前必须显式配置） | `AGENTFLOW_MODEL_API_KEY` | API Key |
| `agentflow.model.chat-model` | `deepseek-v4-pro` | `AGENTFLOW_MODEL_CHAT_MODEL` | 对话模型 |
| `agentflow.model.embedding-model` | `text-embedding-v4` | `AGENTFLOW_MODEL_EMBEDDING_MODEL` | Embedding 模型 |
| `agentflow.model.embedding-dimensions` | `2048` | - | 向量维度 |

**代码依据**：`AgentFlowProperties.java` 的 `Model` JavaBean 配置对象

#### 3.2.2 工具配置

```yaml
agentflow:
  tools:
    max-steps: 6
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agentflow.tools.max-steps` | `6` | Agent 最大工具执行步数 |

**代码依据**：`AgentFlowProperties.java` 的 `Tools` JavaBean 配置对象

#### 3.2.3 安全配置

```yaml
agentflow:
  security:
    refresh-token-ttl: 7d
    jwt:
      secret: ${AGENTFLOW_JWT_SECRET}
      access-token-ttl: 30m
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agentflow.security.refresh-token-ttl` | `7d` | - | Refresh Token 有效期 |
| `agentflow.security.jwt.secret` | 无（启动安全组件前必须显式配置） | `AGENTFLOW_JWT_SECRET` | JWT 签名密钥（至少 32 字节） |
| `agentflow.security.jwt.access-token-ttl` | `30m` | - | Access Token 有效期 |

**代码依据**：`AgentFlowProperties.java` 的 `Security.Jwt` JavaBean 配置对象

#### 3.2.4 MCP 配置

```yaml
agentflow:
  mcp:
    enabled: false
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agentflow.mcp.enabled` | `false` | 是否启用 MCP（当前未自动装配，Feature 006 负责） |

### 3.3 应用自定义配置

```yaml
app:
  qdrant:
    base-url: http://localhost:6333
    collection: agentflow_knowledge
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.qdrant.base-url` | `http://localhost:6333` | Qdrant REST API 地址 |
| `app.qdrant.collection` | `agentflow_knowledge` | Qdrant 集合名称 |

**代码依据**：`QdrantClient.java` 中的 `@Value` 注解

## 4. 环境变量清单

| 环境变量 | 必填 | 默认值 | 说明 |
|----------|------|--------|------|
| `AGENTFLOW_MODEL_API_KEY` | 调用模型时必填 | 无 | LLM API Key；缺少时首次调用明确失败 |
| `AGENTFLOW_MODEL_PROVIDER` | 否 | `deepseek` | 模型提供商标识 |
| `AGENTFLOW_MODEL_BASE_URL` | 否 | 空 | 自定义 OpenAI-compatible API 地址 |
| `AGENTFLOW_MODEL_CHAT_MODEL` | 否 | `deepseek-v4-pro` | 对话模型名称 |
| `AGENTFLOW_MODEL_EMBEDDING_MODEL` | 否 | `text-embedding-v4` | Embedding 模型名称 |
| `AGENTFLOW_JWT_SECRET` | 启用 Web 安全时必填 | 无 | JWT 签名密钥，UTF-8 至少 32 字节 |
| `AGENTFLOW_INITIAL_ADMIN_USERNAME` | 否 | 空 | 与密码同时配置才创建初始用户 |
| `AGENTFLOW_INITIAL_ADMIN_PASSWORD` | 否 | 空 | 与用户名同时配置；只接受本地/部署密钥注入 |
| `SPRING_DATASOURCE_URL` | 启动 Demo 时必填 | 无 | MySQL 连接 URL |
| `SPRING_DATASOURCE_USERNAME` | 启动 Demo 时必填 | 无 | MySQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 启动 Demo 时必填 | 无 | MySQL 密码 |
| `SPRING_DATA_REDIS_HOST` | 启动涉及 Redis 的功能时必填 | `localhost` | Redis 地址 |
| `SPRING_DATA_REDIS_PORT` | 否 | `6379` | Redis 端口 |
| `QDRANT_BASE_URL` | 启用 Demo RAG 时必填 | `http://localhost:6333` | Qdrant 地址 |

`.env.example` 只包含上述名称和非秘密占位符；真实值不得写入仓库、日志或提交历史。

## 5. 多环境配置

### 5.1 当前状态

当前只有一个 `application.yml`，通过环境变量覆盖配置。

### 5.2 建议的多环境方案

```
src/main/resources/
├── application.yml           # 公共配置
├── application-dev.yml       # 开发环境
├── application-test.yml      # 测试环境
└── application-prod.yml      # 生产环境
```

**激活方式**：

```bash
# 开发环境
mvn spring-boot:run -Dspring.profiles.active=dev

# 生产环境
java -jar agent-demo.jar --spring.profiles.active=prod
```

## 6. 配置优先级

Spring Boot 配置优先级（从高到低）：

1. 命令行参数
2. 环境变量
3. `application-{profile}.yml`
4. `application.yml`
5. 默认值

## 7. 当前范围与延期

当前配置文件只提供单进程 Demo 所需的环境变量入口。配置中心、密钥托管、多环境文件和
生产级配置加密不属于 Feature 001；需要时应在部署或对应后续 Feature 中单独设计，不能把
未实现能力写成当前保证。
