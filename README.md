# AgentFlow-Java

AgentFlow-Java is a Spring Boot based MVP for a Java Agent Runtime framework. It combines an enterprise-style backend foundation with a minimal AI developer assistant demo:

- Java 17 + Spring Boot 3.5.x + Spring AI BOM.
- MySQL for users, auth tokens, agent sessions, tasks, steps, tools, memory, and knowledge metadata.
- Redis for token blacklist, rate counters, short memory cache, and task event buffering.
- Qdrant for 2048-dimension RAG vectors.
- OpenAI-compatible chat and embedding endpoints, with Ollama left as a later provider.

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

Set these environment variables to use a real OpenAI-compatible provider:

```bash
AGENTFLOW_MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AGENTFLOW_MODEL_API_KEY=your_api_key
AGENTFLOW_MODEL_CHAT_MODEL=qwen-plus
AGENTFLOW_MODEL_EMBEDDING_MODEL=text-embedding-v4
```

Without an API key the demo still runs with deterministic local fallback output, which is useful for checking the runtime, persistence, and UI flow.
