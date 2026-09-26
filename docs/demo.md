# 离线演示与复现

## 自动验收

```powershell
pwsh -NoProfile -File scripts/verify-delivery.ps1
```

脚本复制各模块生产源码和 Demo 所需测试源码到新临时目录。原项目全量测试另行执行；这个副本只运行演示和评测，不借用主项目 `target/classes`。不会复制 `.env`、`.git`、`.ua`、用户 HTML、学习资料或已有构建目录。

复制 manifest 包含原 SHA、dirty 状态、diff 摘要及逐文件 SHA-256。评测读回并校验这些文件，报告为 `COPIED_SOURCE`；没有把副本冒充新的 Git 提交。

| 场景 | 操作 | 必须看到 |
|---|---|---|
| happy-path | 登录；创建会话；确认 project_stack=Java17；research；note；批准 | 记忆送达模型、research 有真实绑定 S1、审批前零写、批准后一次写和 receipt |
| rejected | note；拒绝 | FAILED/APPROVAL_REJECTED、零写入、无后续模型调用 |
| cancelled | note；等待审批；取消 | 最终 CANCELLED、零写入、唯一 RUN_TERMINATED |
| empty-evidence | 空检索、requireEvidence=true | FAILED/INSUFFICIENT_EVIDENCE、无引用 |
| dependency-failure | Retriever 抛受控错误 | RAG_SOURCE_INVALID 步骤证据，不能伪装成功或空命中 |

研究 Run 和笔记 Run 属于同一会话，各自有独立引用与终态。笔记完成并不继承前一 Run 的引用数组。合成研究引用和最终笔记证据分别保存，不能把笔记的 citations=0 误读成研究没引用。

`.ua/008-delivery/` 保存五类场景安全摘要、研究中间证据、78 条评测和来源 manifest 位置。`target/` 和 `.ua/` 都是可重新生成的本地产物，不会提交原始模型响应。

## 手动页面

先运行消费者脚本安装构件，然后：

```powershell
.\mvnw.cmd -pl agent-demo -am test-compile
.\mvnw.cmd -pl agent-demo dependency:build-classpath '-Dmdep.outputFile=target/demo-test-classpath.txt' '-DincludeScope=test'
$cp = 'agent-demo/target/test-classes;agent-demo/target/classes;' + (Get-Content agent-demo/target/demo-test-classpath.txt -Raw).Trim()
java -cp $cp com.agentflow.demo.delivery.DeliveryDemoLauncher --offline 18082
```

打开本机 18082 端口。仅此测试实例的登录是 `fixture-admin / fixture-only-password`，不要用于真实环境。端口已占用时选另一空闲端口；传 0 可由系统分配，控制台会显示实际地址。退出 Java 进程会关闭它创建的应用和 MCP fixture。

1. 输入 `remember` 建立会话，在页面记忆区域保存 `project_stack` 为 `Java17`。
2. 同会话输入 `research`，启用需要证据选项，查看 `[S1]` 及来源。
3. 输入 `note`，查看等待审批；批准原始调用后，最终回答为 `RECEIPT_CONFIRMED`。
4. 新任务分别体验拒绝和取消。空检索/故障由自动测试场景控制，不是让用户切换生产假数据配置。

Fixture 模型识别以上固定输入，是运行机制演示，不是任意问题的智能问答。正常生产 JAR 不包含这些 launcher/profile/H2/Mockito。

SSE 使用 Bearer Fetch；未知事件仍推进 cursor，204 停止重连并读终态，410 恢复快照/审批；页面关闭中止请求。APPROVED 只表示已授权，UNKNOWN 明确提示可能已执行，不自动重试写操作。
