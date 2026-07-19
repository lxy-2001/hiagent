# REST API 接口文档

## 1. 概述

### 1.1 基础信息

| 项目 | 值 |
|------|-----|
| 基础路径 | `/api` |
| 协议 | HTTP/HTTPS |
| 数据格式 | JSON |
| 认证方式 | Bearer Token (JWT) |
| 限流 | 120 次/分钟/IP |

### 1.2 通用响应格式

**成功响应**：

```json
{
  "field1": "value1",
  "field2": "value2"
}
```

**错误响应**：

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "用户名或密码错误"
}
```

### 1.3 认证说明

需要认证的接口需在请求头中携带：

```
Authorization: Bearer <access_token>
```

**无需认证的接口**：
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/agent/tasks/{taskId}/events`（SSE）
- Swagger UI 相关路径

**代码依据**：`SecurityConfig.java:41-42`

---

## 2. 认证接口

### 2.1 登录

**端点**：`POST /api/auth/login`

**认证**：无需

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | String | 是 | 用户名 |
| `password` | String | 是 | 密码 |

**请求示例**：

```json
{
  "username": "admin",
  "password": "agentflow123"
}
```

**响应**：`200 OK`

| 字段 | 类型 | 说明 |
|------|------|------|
| `accessToken` | String | JWT 访问令牌 |
| `refreshToken` | String | 刷新令牌 |
| `expiresIn` | long | 过期时间（秒） |

**响应示例**：

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
  "expiresIn": 1800
}
```

**错误响应**：

| 状态码 | 说明 |
|--------|------|
| 401 | 用户名或密码错误 |

**代码依据**：`AuthController.java` → `AuthService.java:44-52`

---

### 2.2 刷新 Token

**端点**：`POST /api/auth/refresh`

**认证**：无需

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `refreshToken` | String | 是 | 刷新令牌 |

**请求示例**：

```json
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4..."
}
```

**响应**：`200 OK`

同登录响应格式。

**错误响应**：

| 状态码 | 说明 |
|--------|------|
| 401 | Refresh token 无效或已过期 |

**说明**：每次刷新都会撤销旧 Token 并签发新 Token（Token 轮换）。

**代码依据**：`AuthController.java` → `AuthService.java:55-68`

---

### 2.3 登出

**端点**：`POST /api/auth/logout`

**认证**：需要

**请求头**：

```
Authorization: Bearer <access_token>
```

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `refreshToken` | String | 否 | 刷新令牌（可选） |

**响应**：`200 OK`

无响应体。

**说明**：
- Access Token 的 JTI 会被加入 Redis 黑名单
- Refresh Token 会被标记为已撤销

**代码依据**：`AuthController.java` → `AuthService.java:71-82`

---

### 2.4 获取当前用户

**端点**：`GET /api/me`

**认证**：需要

**响应**：`200 OK`

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "admin",
  "roles": ["USER"]
}
```

**代码依据**：`AuthController.java`

---

## 3. Chat 接口

### 3.1 同步对话

**端点**：`POST /api/chat`

**认证**：需要

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | 是 | 用户消息 |
| `sessionId` | String | 否 | 会话 ID（不传则自动生成） |
| `systemPrompt` | String | 否 | 系统提示词 |
| `model` | String | 否 | 模型名称 |
| `temperature` | Double | 否 | 温度参数 |
| `maxTokens` | Integer | 否 | 最大 Token 数 |

**请求示例**：

```json
{
  "message": "你好",
  "sessionId": "my-session-001",
  "systemPrompt": "你是一个 Java 开发助手",
  "model": "deepseek-v4-pro",
  "temperature": 0.7,
  "maxTokens": 2000
}
```

**响应**：`200 OK`

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话 ID |
| `provider` | String | 模型提供商 |
| `model` | String | 模型名称 |
| `content` | String | 回复内容 |
| `usage` | Object | Token 使用量 |
| `usage.promptTokens` | int | 提示 Token 数 |
| `usage.completionTokens` | int | 完成 Token 数 |
| `usage.totalTokens` | int | 总 Token 数 |
| `mocked` | boolean | 是否为本地兜底回答 |

**响应示例**：

```json
{
  "sessionId": "my-session-001",
  "provider": "deepseek",
  "model": "deepseek-v4-pro",
  "content": "你好！我是 Java 开发助手，有什么可以帮你的？",
  "usage": {
    "promptTokens": 15,
    "completionTokens": 20,
    "totalTokens": 35
  },
  "mocked": false
}
```

**代码依据**：`ChatController.java:29-31` → `ChatService.java:29-33`

---

### 3.2 流式对话

**端点**：`POST /api/chat/stream`

**认证**：需要

**请求体**：同同步对话

**响应**：`text/event-stream` (SSE)

**事件格式**：

```
event: delta
data: {"content": "你"}

event: delta
data: {"content": "好"}

event: done
data: {"sessionId":"...","provider":"deepseek","model":"...","content":"你好","usage":{...},"mocked":false}
```

**错误事件**：

```
event: error
data: {"message": "Chat stream failed"}
```

**代码依据**：`ChatController.java:33-47`

---

## 4. Agent 接口

### 4.1 创建任务

**端点**：`POST /api/agent/tasks`

**认证**：需要

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `input` | String | 是 | 用户任务描述 |

**请求示例**：

```json
{
  "input": "帮我设计一个秒杀系统的库存扣减接口"
}
```

**响应**：`201 Created`

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 任务 ID |
| `sessionId` | String | 会话 ID |
| `status` | String | 任务状态（RUNNING） |

**响应示例**：

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "660e8400-e29b-41d4-a716-446655440001",
  "status": "RUNNING"
}
```

**说明**：任务创建后异步执行，可通过事件订阅获取进度。

**代码依据**：`AgentController.java:28-30` → `AgentTaskService.java:39-46`

---

### 4.2 查询任务

**端点**：`GET /api/agent/tasks/{taskId}`

**认证**：需要

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 任务 ID |

**响应**：`200 OK`

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 任务 ID |
| `sessionId` | String | 会话 ID |
| `status` | String | 任务状态 |
| `input` | String | 用户输入 |
| `finalAnswer` | String | 最终答案（任务完成后） |

**响应示例**：

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "660e8400-e29b-41d4-a716-446655440001",
  "status": "SUCCEEDED",
  "input": "帮我设计秒杀库存扣减接口",
  "finalAnswer": "基于秒杀系统的需求..."
}
```

**错误响应**：

| 状态码 | 说明 |
|--------|------|
| 404 | 任务不存在 |

**代码依据**：`AgentController.java:33-35` → `AgentTaskService.java:49-55`

---

### 4.3 查询任务步骤

**端点**：`GET /api/agent/tasks/{taskId}/steps`

**认证**：需要

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 任务 ID |

**响应**：`200 OK`

```json
[
  {
    "stepNo": 1,
    "stepType": "PLANNER",
    "toolName": "planner",
    "status": "SUCCESS",
    "output": "识别为 Java 后端设计任务...",
    "errorMessage": null
  },
  {
    "stepNo": 2,
    "stepType": "RAG",
    "toolName": "knowledge-retriever",
    "status": "SUCCESS",
    "output": "[{\"id\":\"...\",\"title\":\"...\",\"content\":\"...\",\"score\":0.95}]",
    "errorMessage": null
  },
  {
    "stepNo": 3,
    "stepType": "TOOL",
    "toolName": "interface-draft",
    "status": "SUCCESS",
    "output": "推荐接口：\n\nPOST /api/seckill/deduct...",
    "errorMessage": null
  },
  {
    "stepNo": 4,
    "stepType": "LLM",
    "toolName": "final-answer",
    "status": "SUCCESS",
    "output": "基于秒杀系统的需求...",
    "errorMessage": null
  }
]
```

**代码依据**：`AgentController.java:37-39` → `AgentTaskService.java:57-63`

---

### 4.4 订阅任务事件（SSE）

**端点**：`GET /api/agent/tasks/{taskId}/events`

**认证**：无需（在 SecurityConfig 中 permitAll）

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `taskId` | String | 任务 ID |

**响应**：`text/event-stream` (SSE)

**事件类型**：

| 事件名 | 说明 |
|--------|------|
| `PLANNER` | 规划完成 |
| `RAG` | 检索完成 |
| `TOOL` | 工具执行完成 |
| `LLM` | LLM 调用完成 |
| `FINAL` | 任务完成 |
| `REPLAY` | 历史事件回放 |

**事件数据格式**：

```json
{
  "taskId": "...",
  "type": "PLANNER",
  "name": "planner",
  "content": "识别为 Java 后端设计任务...",
  "occurredAt": "2024-01-01T12:00:00Z"
}
```

**示例**：

```
event: PLANNER
data: {"taskId":"...","type":"PLANNER","name":"planner","content":"...","occurredAt":"..."}

event: RAG
data: {"taskId":"...","type":"RAG","name":"knowledge-retriever","content":"...","occurredAt":"..."}

event: TOOL
data: {"taskId":"...","type":"TOOL","name":"interface-draft","content":"...","occurredAt":"..."}

event: LLM
data: {"taskId":"...","type":"LLM","name":"final-answer","content":"...","occurredAt":"..."}

event: FINAL
data: {"taskId":"...","type":"FINAL","name":"FINAL","content":"...","occurredAt":"..."}
```

**说明**：
- 支持断线重连，重连后会回放历史事件（Redis 缓存）
- 事件缓存保留 2 小时，最多 100 条

**代码依据**：`AgentController.java:43-45` → `TaskEventPublisher.java:44-60`

---

## 5. 知识管理接口

### 5.1 重载知识库

**端点**：`POST /api/knowledge/reload`

**认证**：需要

**响应**：`200 OK`

| 字段 | 类型 | 说明 |
|------|------|------|
| `importedCount` | int | 导入文件数 |
| `importedFiles` | List<String> | 导入的文件名列表 |

**响应示例**：

```json
{
  "importedCount": 2,
  "importedFiles": ["seckill-design.md", "backend-layering.md"]
}
```

**说明**：
- 重新扫描 `classpath:/knowledge/*.md`
- 已存在的文件（按内容 Hash 判断）会跳过
- 新文件会分块、向量化并存入 Qdrant

**代码依据**：`KnowledgeController.java` → `KnowledgeService.java:44-79`

---

## 6. 错误码

| 状态码 | 说明 | 常见场景 |
|--------|------|---------|
| 200 | 成功 | 正常响应 |
| 201 | 已创建 | 任务创建成功 |
| 400 | 请求错误 | 参数校验失败 |
| 401 | 未认证 | Token 无效或已过期 |
| 403 | 无权限 | 角色权限不足 |
| 404 | 未找到 | 资源不存在 |
| 429 | 请求过多 | 超过限流阈值 |
| 500 | 服务器错误 | 内部异常 |

---

## 7. 待确认项

| # | 项目 | 状态 | 说明 |
|---|------|------|------|
| 1 | API 版本控制 | 待确认 | 是否需要 `/api/v1/` 前缀？ |
| 2 | 分页支持 | 待确认 | 列表接口是否需要分页？ |
| 3 | 接口文档自动生成 | 待确认 | 是否启用 Springdoc OpenAPI？ |
| 4 | WebSocket 支持 | 待确认 | 是否需要 WebSocket 替代 SSE？ |
| 5 | 文件上传 | 待确认 | 是否支持知识文件上传？ |
