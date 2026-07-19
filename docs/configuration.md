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
    url: jdbc:mysql://localhost:3307/agentflow
    username: agentflow
    password: agentflow
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
    api-key: ${AGENTFLOW_MODEL_API_KEY:change-me}
    chat-model: deepseek-v4-pro
    embedding-model: text-embedding-v3
    embedding-dimensions: 2048
  tools:
    max-steps: 6
  security:
    refresh-token-ttl: 7d
    jwt:
      secret: ${JWT_SECRET:change-me-change-me-change-me-change-me}
      access-token-ttl: 30m
  mcp:
    enabled: false

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
    url: jdbc:mysql://localhost:3307/agentflow
    username: agentflow
    password: agentflow
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `spring.datasource.url` | `jdbc:mysql://localhost:3307/agentflow` | `SPRING_DATASOURCE_URL` | MySQL 连接 URL |
| `spring.datasource.username` | `agentflow` | `SPRING_DATASOURCE_USERNAME` | 数据库用户名 |
| `spring.datasource.password` | `agentflow` | `SPRING_DATASOURCE_PASSWORD` | 数据库密码 |

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
    api-key: ${AGENTFLOW_MODEL_API_KEY:change-me}
    chat-model: deepseek-v4-pro
    embedding-model: text-embedding-v3
    embedding-dimensions: 2048
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agentflow.model.provider` | `deepseek` | `AGENTFLOW_MODEL_PROVIDER` | 模型提供商（deepseek/openai） |
| `agentflow.model.base-url` | 空 | `AGENTFLOW_MODEL_BASE_URL` | 自定义 API 地址 |
| `agentflow.model.api-key` | `change-me` | `AGENTFLOW_MODEL_API_KEY` | API Key |
| `agentflow.model.chat-model` | `deepseek-v4-pro` | `AGENTFLOW_MODEL_CHAT_MODEL` | 对话模型 |
| `agentflow.model.embedding-model` | `text-embedding-v3` | `AGENTFLOW_MODEL_EMBEDDING_MODEL` | Embedding 模型 |
| `agentflow.model.embedding-dimensions` | `2048` | - | 向量维度 |

**代码依据**：`AgentFlowProperties.java` 内部 `Model` record

#### 3.2.2 工具配置

```yaml
agentflow:
  tools:
    max-steps: 6
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agentflow.tools.max-steps` | `6` | Agent 最大工具执行步数 |

**代码依据**：`AgentFlowProperties.java` 内部 `Tools` record

#### 3.2.3 安全配置

```yaml
agentflow:
  security:
    refresh-token-ttl: 7d
    jwt:
      secret: ${JWT_SECRET:change-me-change-me-change-me-change-me}
      access-token-ttl: 30m
```

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| `agentflow.security.refresh-token-ttl` | `7d` | - | Refresh Token 有效期 |
| `agentflow.security.jwt.secret` | `change-me...` | `JWT_SECRET` | JWT 签名密钥（至少 32 字节） |
| `agentflow.security.jwt.access-token-ttl` | `30m` | - | Access Token 有效期 |

**代码依据**：`AgentFlowProperties.java` 内部 `Security` 和 `Jwt` record

#### 3.2.4 MCP 配置

```yaml
agentflow:
  mcp:
    enabled: false
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agentflow.mcp.enabled` | `false` | 是否启用 MCP（当前未实现） |

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
| `AGENTFLOW_MODEL_API_KEY` | 是 | `change-me` | LLM API Key |
| `AGENTFLOW_MODEL_PROVIDER` | 否 | `deepseek` | 模型提供商 |
| `AGENTFLOW_MODEL_BASE_URL` | 否 | 空 | 自定义 API 地址 |
| `AGENTFLOW_MODEL_CHAT_MODEL` | 否 | `deepseek-v4-pro` | 对话模型 |
| `AGENTFLOW_MODEL_EMBEDDING_MODEL` | 否 | `text-embedding-v3` | Embedding 模型 |
| `JWT_SECRET` | 是 | `change-me...` | JWT 签名密钥 |
| `SPRING_DATASOURCE_URL` | 否 | `jdbc:mysql://localhost:3307/agentflow` | MySQL URL |
| `SPRING_DATASOURCE_USERNAME` | 否 | `agentflow` | MySQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 否 | `agentflow` | MySQL 密码 |
| `SPRING_DATA_REDIS_HOST` | 否 | `localhost` | Redis 地址 |
| `SPRING_DATA_REDIS_PORT` | 否 | `6379` | Redis 端口 |

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

## 7. 待确认项

| # | 项目 | 状态 | 说明 |
|---|------|------|------|
| 1 | 多环境配置 | 待确认 | 是否需要 application-prod.yml？ |
| 2 | 配置加密 | 待确认 | 敏感配置是否需要加密？ |
| 3 | 配置中心 | 待确认 | 是否需要 Nacos Config？ |
| 4 | 密钥管理 | 待确认 | 是否需要 Vault/AWS Secrets Manager？ |
