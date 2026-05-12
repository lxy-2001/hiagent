# AgentFlow-Java

AgentFlow-Java is a Spring Boot based MVP for a Java Agent Runtime framework. It combines an enterprise-style backend foundation with a minimal AI developer assistant demo:

- Java 17 + Spring Boot 3.5.x + Spring AI BOM.
- MySQL for users, auth tokens, agent sessions, tasks, steps, tools, memory, and knowledge metadata.
- Redis for token blacklist, rate counters, short memory cache, and task event buffering.
- Qdrant for 2048-dimension RAG vectors.
- DeepSeek-first OpenAI-compatible chat endpoints, with OpenAI and custom compatible providers configurable.

Docker maps MySQL to host port `3307` to avoid conflicts with a local Windows MySQL service on `3306`.

## Quick Start

```bash
docker compose up -d
mvn clean verify
mvn -pl agent-demo-dev-assistant -am spring-boot:run
```

Open http://localhost:8080 and log in with:

- Username: `admin`
- Password: `agentflow123`

Set these environment variables to use DeepSeek as the first real OpenAI-compatible provider:

```bash
AGENTFLOW_MODEL_PROVIDER=deepseek
AGENTFLOW_MODEL_API_KEY=your_api_key
AGENTFLOW_MODEL_CHAT_MODEL=deepseek-v4-pro
AGENTFLOW_MODEL_EMBEDDING_MODEL=text-embedding-v4
```

Switch to OpenAI by overriding the provider and base URL:

```bash
AGENTFLOW_MODEL_PROVIDER=openai
AGENTFLOW_MODEL_BASE_URL=https://api.openai.com/v1
AGENTFLOW_MODEL_API_KEY=your_api_key
AGENTFLOW_MODEL_CHAT_MODEL=your_openai_chat_model
```

Without an API key the demo still runs with deterministic local fallback output, which is useful for checking the runtime, persistence, and UI flow.

## Basic Chat API

After logging in, call the synchronous chat endpoint:

```bash
POST /api/chat
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "sessionId": null,
  "message": "你好，介绍一下你自己",
  "systemPrompt": "你是 AgentFlow-Java 的开发助手",
  "temperature": 0.7,
  "maxTokens": 2048
}
```

For streaming output, use `POST /api/chat/stream`. It emits `delta`, `done`, and `error` SSE events. Passing the returned `sessionId` into the next request reuses the recent Redis-backed conversation history.
