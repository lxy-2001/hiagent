# Feature004：有界上下文、会话和显式记忆

每个 Run 准备时读取一次持久快照，包含最近最多 20 个成功轮次和最多两条确认记忆。
`agent-core` 的 `ContextAssembler` 在每次模型决策前重新选择上下文：先删最旧完整历史，
再删语言偏好和项目技术栈；当前输入和完整 Tool Call/Result 链不可拆分。必要内容超过
窗口或硬上限时返回 `CONTEXT_BUDGET_EXCEEDED`，不再调用模型。

估算与真实 usage 分开。`CONTEXT_ASSEMBLY` 步骤只记录策略版本、保留/裁剪数量、估算、
预留和原因，不存正文；仍使用既有四种 SSE 外层事件。

## 使用

在已配置数据库、Redis、模型和认证信息的 Demo 中登录：

1. 新建会话并运行任务。POST `/api/agent/tasks` 不传 `sessionId` 时创建会话。
2. 选择会话继续，创建请求携带 `sessionId`。上一 Run 终态已提交且 worker 已退出后才
   释放会话；忙时返回 `409 SESSION_BUSY`，页面不自动重复 POST。
3. 显式保存 `preferred_language`（编程语言偏好）或 `project_stack`。写入带字符串
   `expectedVersion`，版本冲突返回 409，需重新读取后手动提交。
4. 显式删除记忆会清正文并递增版本；删除后新 Run 不再使用它，已经准备的 Run 保留其
   不可变快照。删除再重建不会重置版本。
5. 历史分页固定 `untilSequence`；失败输入可查询，失败轮次不进入模型历史。

API 的序号和版本是十进制字符串。全部新接口验证归属并返回 `Cache-Control: no-store`。
契约见 [Feature004 OpenAPI](../agent-web/src/test/resources/contracts/feature004-openapi.yaml)
及 [Run/SSE OpenAPI](../agent-web/src/test/resources/contracts/run-lifecycle.yaml)。

## 离线验证

在仓库根目录执行，Windows 使用 `mvnw.cmd`：

```bash
./mvnw -pl agent-demo -am -Dtest=ConversationEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix agent-demo test
./mvnw clean verify
```

`ConversationEndToEndTest` 使用真实 HTTP、DefaultAgentRuntime、事务和 Flyway 自动迁移，
模型及 Redis 使用明确的测试替身。它捕获真实装配后的模型请求，验证成功/失败/成功三轮、
记忆写入与删除、来源失败零模型调用、取消期间会话仍忙、SSE 晚订阅与重连。

其他测试覆盖 CAS 并发与墓碑、固定分页、启动恢复、已知凭据脱敏、自动配置覆盖和
多语言/Tool 链/窗口硬限额。前端测试执行页面脚本，验证切换会话后旧回调隔离及安全文本渲染。
V3 分配会话轮次，V4 建立确认记忆表；V1/V2 保持原样，旧 `agent_memory` 不自动导入。
Spring Boot 4 的 Flyway Starter 负责启动迁移，测试不靠 Hibernate 建表替代迁移证据。

## 边界

- 单进程会话准入；没有分布式锁、故障转移或跨会话画像。
- 取消和来源超时是协作式，不能保证驱动 I/O 被即时中断。
- UTF-8 启发式不是厂商 Tokenizer 或计费数据；只识别已声明的凭据模式。
- 默认测试不调用付费模型或外部生产服务。真实 MySQL、Redis 和模型质量/计费未验收，
  H2 与确定性模型测试不能代替这些证据。
- 本地 SDD 位于被忽略的 `specs/004-context-session-memory/`；该目录不随分支推送。

## 本次验证记录

2026-09-22：`mvnw.cmd clean verify`通过，574项Java测试零失败/错误/跳过；
`npm --prefix agent-demo test`通过12项。SDD静态检查190项（含45个Schema正反例）通过，
实现收敛补充三项已完成。上述结果均为本地离线证据；未运行外部生产服务。
