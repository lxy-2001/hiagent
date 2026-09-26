# 交付检查

权威执行摘要：[delivery-manifest.json](../samples/contracts/delivery-manifest.json)，结构：[delivery.schema.json](../samples/contracts/delivery.schema.json)。状态为 NOT_RUN 的项没有退出码，不能作为通过证据。

## 可复现门禁

1. Java17 + Wrapper3.9.16：`mvnw.cmd clean verify`，包含纯core/适配/Web/Demo/评测；默认只跳过显式opt-in真实Qdrant实验。
2. `npm --prefix agent-demo test`：SSE解析/恢复/停止、会话、引用、审批与UNKNOWN提示。
3. `pwsh -File scripts/verify-consumers.ps1`：六个新目录的构建与实际调用，依赖隔离检查。失败返回非零，保留临时目录与日志。
4. `pwsh -File scripts/verify-delivery.ps1`：白名单副本中的五场景、来源hash、26case×3和Node。副本不带用户配置或主仓库构建产物。
5. 检查生产Demo JAR：不能包含agent-eval、H2、Mockito、delivery/evaluation/approval测试类或fixture profile。
6. 核对文档相对链接和JSON schema、无真实密钥、所有声明可定位测试与实现提交。

复制脚本保留本次创建的临时目录以便诊断，不执行宽范围清理。需要回收时按日志中的具体目录自行确认，不删除用户目录或数据库卷。

## 边界

- OFFLINE_FIXTURE 是合成模型/检索及回环服务。真实模型与第三方MCP分别记 NOT_RUN，不能用回环适配测试代替真实环境成绩。
- `source-provenance.json` 是本地校验清单，不是可信构建签名或供应链证明。
- `specs/`、`.specify/` 和历史开发笔记不随本次发布。公开文档不依赖这些本地文件。
- 007和008使用独立分支，008以已验证007提交为基线。main未自动合并。
- 外部发行固定 NOT_PUBLISHED：没有Maven Central、标签、Release或站点。

## 2026-09-26 本地验收结果

- `clean verify`：925项测试，0失败、0错误，1项真实Qdrant opt-in跳过。
- Node：21/21通过。
- 六消费者在新的本地检出目录再次执行：6/6通过，均从单独临时目录消费已安装构件。
- 再次复制源码：五流程与研究引用中间证据、26场景×3=78条PASS、前端通过，报告来源COPIED_SOURCE且保留dirty事实。
- 文档手动launcher命令启动成功，随机端口页面HTTP200，真实fixture登录成功，停止后端口关闭。
- 生产JAR无评测/测试夹具/H2/Mockito；公开相对链接与稳定schema一致性检查通过。

该记录对应实现提交81fe743及后续文档/验收清单改动。主仓库`mvnw`和用户HTML/learning已有改动未提交，来源dirty字段不会因此伪报为false。完整本地路径在生成的manifest中，原始日志不进入Git。
