# AgentFlow-Java 项目文档

> 企业级 AI Agent 开发框架

## 项目简介

AgentFlow-Java 是一个基于 Java 17 + Spring Boot 的 AI Agent 开发框架，采用接口驱动架构，支持 LLM 对话、RAG 知识检索、工具调用和 SSE 流式推送。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.14 |
| AI SDK | Spring AI BOM | 1.1.6 |
| 数据库 | MySQL | 8.4 |
| 缓存 | Redis | 7.4 |
| 向量库 | Qdrant | v1.17.0 |
| API 文档 | Springdoc OpenAPI | 2.8.14 |

## 模块结构

```
agent-core    # 纯接口层（零依赖）
agent-llm     # LLM 通信层
agent-tool    # 工具框架层
agent-rag     # RAG 检索层
agent-web     # 运行时组装层
agent-demo    # 演示应用（入口）
```

## 文档目录

| 文档 | 说明 |
|------|------|
| [系统架构说明](architecture.md) | 整体架构、分层设计、依赖关系 |
| [模块设计说明](module-design.md) | 各模块职责、核心接口、实现类 |
| [核心业务流程](business-flow.md) | Chat、Agent、RAG 等关键流程 |
| [API 接口文档](api/rest-api.md) | REST API 端点、请求/响应格式 |
| [数据库设计](database.md) | 表结构、ER 图、索引设计 |
| [配置说明](configuration.md) | application.yml、环境变量、配置项 |
| [部署与运行手册](deployment.md) | Docker、启动步骤、环境要求 |
| [日志与异常处理](logging-exception.md) | 日志规范、异常处理策略 |
| [测试与质量保障](testing.md) | 测试策略、测试用例、覆盖率 |

## 快速开始

```bash
# 1. 启动基础设施
docker-compose up -d

# 2. 设置环境变量
export AGENTFLOW_MODEL_API_KEY=your-api-key
export JWT_SECRET=your-jwt-secret

# 3. 启动应用
mvn spring-boot:run -pl agent-demo

# 4. 访问
# API: http://localhost:8080
# Swagger: http://localhost:8080/swagger-ui.html
```

## 版本信息

| 项目 | 值 |
|------|-----|
| 版本 | 0.1.0-SNAPSHOT |
| GroupId | com.agentflow |
| ArtifactId | agentflow-java |
| License | - |
