# 部署与运行手册

> 本页是当前单进程 Demo 的本地运行参考，不是 Feature 001 对生产部署、高可用、Kubernetes
> 或稳定 SSE 的承诺。默认验收仍以 `./mvnw -B -ntp clean verify` 为准，不需要启动这些服务。

## 1. 环境要求

### 1.1 开发环境

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17+ | 推荐 OpenJDK 17 |
| Maven | 3.8+ | 构建工具 |
| Docker | 20+ | 运行基础设施 |
| Docker Compose | 2.0+ | 编排基础设施 |

### 1.2 基础设施

| 服务 | 版本 | 端口 | 用途 |
|------|------|------|------|
| MySQL | 8.4 | 3307 | 持久化存储 |
| Redis | 7.4 | 6379 | 缓存、事件、限流 |
| Qdrant | v1.17.0 | 6333 | 向量存储 |

## 2. 快速启动

### 2.1 克隆项目

```bash
git clone https://github.com/lxy-2001/hiagent.git
cd hiagent
```

### 2.2 启动基础设施

```bash
docker-compose up -d
```

**检查服务状态**：

```bash
docker-compose ps
```

**预期输出**：

```
NAME                STATUS              PORTS
agentflow-mysql     Up (healthy)        0.0.0.0:3307->3306/tcp
agentflow-redis     Up (healthy)        0.0.0.0:6379->6379/tcp
agentflow-qdrant    Up                  0.0.0.0:6333->6333/tcp
```

### 2.3 设置环境变量

**Windows (PowerShell)**：

```powershell
$env:AGENTFLOW_MODEL_API_KEY = "<local-model-key>"
$env:AGENTFLOW_JWT_SECRET = "<random-secret-at-least-32-bytes>"
$env:AGENTFLOW_INITIAL_ADMIN_USERNAME = "<local-admin-username>"
$env:AGENTFLOW_INITIAL_ADMIN_PASSWORD = "<local-admin-password>"
```

**Linux/macOS**：

```bash
export AGENTFLOW_MODEL_API_KEY=<local-model-key>
export AGENTFLOW_JWT_SECRET=<random-secret-at-least-32-bytes>
export AGENTFLOW_INITIAL_ADMIN_USERNAME=<local-admin-username>
export AGENTFLOW_INITIAL_ADMIN_PASSWORD=<local-admin-password>
```

### 2.4 启动应用

```bash
./mvnw -B -ntp clean verify
./mvnw spring-boot:run -pl agent-demo
```

**预期输出**：

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

...
Started AgentFlowDemoApplication in X.XXX seconds
```

### 2.5 验证服务

```bash
# 测试登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<configured-username>","password":"<configured-password>"}'

# 测试对话（使用返回的 accessToken）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <accessToken>" \
  -d '{"message":"你好"}'
```

## 3. Docker Compose 配置

### 3.1 完整配置

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8.4
    container_name: agentflow-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD must be set}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-agentflow}
      MYSQL_USER: ${MYSQL_USER:-agentflow}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?MYSQL_PASSWORD must be set}
    ports:
      - "3307:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -u$${MYSQL_USER} -p$${MYSQL_PASSWORD}"]
      interval: 10s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7.4
    container_name: agentflow-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: ["redis-server", "--appendonly", "yes"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10

  qdrant:
    image: qdrant/qdrant:v1.17.0
    container_name: agentflow-qdrant
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant-data:/qdrant/storage

volumes:
  mysql-data:
  redis-data:
  qdrant-data:
```

### 3.2 数据持久化

| 卷名 | 用途 |
|------|------|
| `mysql-data` | MySQL 数据文件 |
| `redis-data` | Redis AOF 文件 |
| `qdrant-data` | Qdrant 向量数据 |

### 3.3 常用命令

```bash
# 启动所有服务
docker-compose up -d

# 停止所有服务
docker-compose down

# 查看日志
docker-compose logs -f mysql
docker-compose logs -f redis
docker-compose logs -f qdrant

# 重建服务
docker-compose down -v  # 删除数据卷
docker-compose up -d
```

## 4. 项目构建

### 4.1 Maven 命令

```bash
# 编译
./mvnw -B -ntp compile

# 运行测试
./mvnw -B -ntp test

# 打包
./mvnw -B -ntp clean package -DskipTests

# 运行
./mvnw spring-boot:run -pl agent-demo

# 清理
./mvnw -B -ntp clean
```

### 4.2 模块构建顺序

```
agent-core → agent-llm / agent-tool / agent-rag → agent-web → agent-demo
```

**代码依据**：`pom.xml` 中的 `<modules>` 定义

## 5. 应用配置

### 5.1 端口配置

默认端口：`8080`

修改方式：

```yaml
# application.yml
server:
  port: 9090
```

或环境变量：

```bash
$env:SERVER_PORT = "9090"
```

### 5.2 数据库配置

**生产环境建议**：

```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-mysql:3306/agentflow?useSSL=true&requireSSL=true
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### 5.3 Redis 配置

**生产环境建议**：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
```

### 5.4 LLM 配置

**DeepSeek**：

```yaml
agentflow:
  model:
    provider: deepseek
    api-key: ${AGENTFLOW_MODEL_API_KEY}
    chat-model: deepseek-v4-pro
    embedding-model: text-embedding-v4
```

**OpenAI**：

```yaml
agentflow:
  model:
    provider: openai
    api-key: ${AGENTFLOW_MODEL_API_KEY}
    chat-model: gpt-4o
    embedding-model: text-embedding-3-small
    embedding-dimensions: 1536
```

**自定义 API 地址**：

```yaml
agentflow:
  model:
    base-url: https://your-proxy.com/v1
```

## 6. 生产部署

### 6.1 打包

```bash
./mvnw -B -ntp clean package -DskipTests
```

生成文件：`agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar`

### 6.2 运行

```bash
java -jar agent-demo-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --agentflow.model.api-key=$AGENTFLOW_MODEL_API_KEY \
  --agentflow.security.jwt.secret=$AGENTFLOW_JWT_SECRET
```

### 6.3 Systemd 服务

```ini
# /etc/systemd/system/agentflow.service
[Unit]
Description=AgentFlow Java Application
After=syslog.target network.target mysql.service redis.service

[Service]
User=agentflow
Group=agentflow
WorkingDirectory=/opt/agentflow
ExecStart=/usr/bin/java -jar agent-demo-0.1.0-SNAPSHOT.jar
EnvironmentFile=/etc/agentflow/agentflow.env
# agentflow.env must be permission-restricted and contain the required environment names.
SuccessExitStatus=143
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable agentflow
sudo systemctl start agentflow
sudo systemctl status agentflow
```

### 6.4 Dockerfile（待创建）

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**说明**：当前项目未提供 Dockerfile。

## 7. 健康检查

### 7.1 应用健康

```bash
curl http://localhost:8080/actuator/health
```

**注意**：需要添加 `spring-boot-starter-actuator` 依赖。

### 7.2 基础设施健康

```bash
# MySQL
docker exec agentflow-mysql sh -c 'mysqladmin ping -h localhost -u"$MYSQL_USER" -p"$MYSQL_PASSWORD"'

# Redis
docker exec agentflow-redis redis-cli ping

# Qdrant
curl http://localhost:6333/healthz
```

## 8. 日志查看

### 8.1 应用日志

```bash
# 实时查看
tail -f logs/application.log

# 或 Docker 日志
docker-compose logs -f
```

### 8.2 基础设施日志

```bash
docker-compose logs -f mysql
docker-compose logs -f redis
docker-compose logs -f qdrant
```

## 9. 故障排查

### 9.1 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 连接 MySQL 失败 | MySQL 未启动 | `docker-compose up -d mysql` |
| 连接 Redis 失败 | Redis 未启动 | `docker-compose up -d redis` |
| Qdrant 连接超时 | Qdrant 未启动 | `docker-compose up -d qdrant` |
| JWT 验证失败 | `AGENTFLOW_JWT_SECRET` 不一致或长度不足 | 检查受限环境变量 |
| LLM 调用失败 | API Key 无效 | 检查 AGENTFLOW_MODEL_API_KEY |
| 429 Too Many Requests | 超过限流 | 等待或调整限流配置 |

### 9.2 日志级别调整

```yaml
logging:
  level:
    com.agentflow: DEBUG
    org.springframework.security: DEBUG
```

## 10. 当前范围与延期

当前只提供本地单进程 Demo 的启动参考。Dockerfile、生产密钥托管、监控告警、Kubernetes、
微服务和高可用均未在 Feature 001 中实现或验证；如纳入项目，应先建立独立规格和验收条件。
