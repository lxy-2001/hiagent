# 本机配置与真实服务

离线验收不用任何真实凭据。下面只用于显式启动真实 Demo，本次007/008验收未执行真实付费模型或外部 MCP 实验。

## 谁读取配置

- Docker Compose 自动读取项目目录 `.env` 做变量替换，并按 compose 文件向容器传入所列变量。
- 另行启动的 Java 进程不会自动读取该 `.env`；可在 IDE 配置环境变量，或用 Spring Boot 外部 YAML。
- `.env.example` 是模板，Compose 默认不会读取它。不要把真实值写进 Git 中的样例。
- Spring 配置以 `agent-demo/src/main/resources/application.yml` 为权威。引用地址填写纯 URL，不要包含 Markdown 链接语法。

可以把私有 `real.yml` 放在仓库之外，例如 `E:/hiagent-private/`。按需要填写这些键，不要原样运行占位值：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3307/agentflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: agentflow
    password: '填写应用数据库密码'
  data:
    redis:
      host: 127.0.0.1
      port: 6379
agentflow:
  model:
    provider: openai
    base-url: '填写服务商兼容基础URL'
    api-key: '填写聊天API密钥'
    chat-model: '填写准确模型ID'
  security:
    jwt:
      secret: '填写至少32字节的独立随机密钥'
  initial-admin:
    username: '填写登录账号'
    password: '填写登录密码'
  rag:
    enabled: false
  mcp:
    enabled: false
```

使用 README 的 `--spring.config.additional-location=file:...` 启动。初始管理员仅在该用户名不存在时创建，修改配置不会替换已有账户密码。JWT、登录密码、数据库密码、模型密钥是不同用途，不能互相代替。

## 环境变量对应

| 用途 | 变量 |
|---|---|
| 聊天 | AGENTFLOW_MODEL_PROVIDER、AGENTFLOW_MODEL_BASE_URL、AGENTFLOW_MODEL_API_KEY、AGENTFLOW_MODEL_CHAT_MODEL |
| Embedding | AGENTFLOW_MODEL_EMBEDDING_BASE_URL、AGENTFLOW_MODEL_EMBEDDING_API_KEY、AGENTFLOW_MODEL_EMBEDDING_MODEL、AGENTFLOW_MODEL_EMBEDDING_DIMENSIONS |
| 数据库 | SPRING_DATASOURCE_URL、SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD |
| Redis | SPRING_DATA_REDIS_HOST、SPRING_DATA_REDIS_PORT |
| 登录 | AGENTFLOW_JWT_SECRET、AGENTFLOW_INITIAL_ADMIN_USERNAME、AGENTFLOW_INITIAL_ADMIN_PASSWORD |
| RAG | AGENTFLOW_RAG_ENABLED、AGENTFLOW_RAG_STORE_DIRECTORY、AGENTFLOW_RAG_EMBEDDING_SPACE_ID、AGENTFLOW_RAG_ALLOW_KEYWORD_FALLBACK |
| Qdrant | QDRANT_BASE_URL、QDRANT_API_KEY（Demo YAML 显式映射） |

Embedding 维度必须与模型实际返回一致，不能仅凭模型名猜测。聊天和 Embedding 可选择不同服务商。当前聊天适配为 OpenAI Chat Completions 兼容路径，不声明支持 Responses API。

默认 RAG 读取已导入的本地快照；原始 UTF-8 文档目录是导入输入，不会仅因设置 SOURCE_DIRECTORY 自动完成导入。默认 Retriever 需要快照、对应 embedding space/model/dimension 和 Qdrant；自定义证据 Retriever 可独立于 LLM 模块。关键词降级必须明确启用，失败不会静默冒充有证据。

## 基础设施与端口

当前 compose 服务为 mysql、redis、qdrant：主机端口分别3307、6379、6333/6334。只需要完整 Web 时可在已经配置 Compose 必需密码后运行：

```powershell
docker compose up -d mysql redis
```

需要默认 RAG 才另行启动 qdrant。离线验收不执行以上命令。Compose 不会启动 Java Demo；也不会修改已初始化 MySQL 卷中的账户密码。不要为配置不一致直接删除数据卷。

MCP 默认关闭。开启时逐项配置服务 ID、URL、allowed-tools 和应用侧 tool-policy；高风险/写工具必须审批。版本支持不放宽 Schema，Microsoft Learn 的工具定义仍可能不满足当前受限 Schema。未选能力不应产生连接，选择了但缺配置则明确失败。
