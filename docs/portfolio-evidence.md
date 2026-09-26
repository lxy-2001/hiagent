# 可讲解的证据与边界

实现来源：`81fe7439c40beed4ace27ba1652587261080de7f`（Feature008 Phase3）；Phase4整理公开文档和验收记录。最终逐项状态见 [机器可读清单](../samples/contracts/delivery-manifest.json)。不要把未执行项目写成已验收。

| 可以表述的能力 | 代码 / 实验 | 样本与限制 |
|---|---|---|
| 自研受预算与取消控制的 Agent 决策循环 | DefaultAgentRuntime；核心单元测试与007评分 | 机制测试，不是通用任务准确率 |
| 按需 Spring 接入并允许覆盖模型/工具/上下文 | AgentRuntimeAutoConfiguration；StarterOverrideTest；verify-consumers.ps1 | 六种不同类路径与不同根包消费者；本地已安装坐标 |
| 同会话确认记忆和可信引用 | DeliveryFlowTest；ContextAssembler；EvidenceLedger | 五类流程中的 happy-path，记忆和引用通过实际模型请求/持久快照检查 |
| 高风险工具先审批再执行，拒绝/取消不重放 | DefaultAgentRuntime；WebApprovalGate；DeliveryFlowTest；007 C19 | 审批前0写入，批准后1次；断连/取消后可能UNKNOWN，不承诺跨网络恰好一次 |
| 可复现评分和窗口退化对比 | agent-eval；AgentEvaluationSuiteTest / CompareTest | 26场景×3=78记录；较小窗口候选的12次失败被正确识别 |
| 可复制验证的工程交付 | verify-delivery.ps1；CodeProvenance | 逐文件hash、原SHA和dirty记录；不是伪造一个干净commit |

007来源：`8b41680`，全仓构建、78次离线基线及退化比较已通过。008 Starter来源`c1b506a`，消费者来源`d1451f1`，演示来源`81fe743`。报告运行时的工作区含未提交文件，所以原报告诚实记录dirty；最终提交不会追改历史报告的来源字段。

可使用的简历表达：

> 基于 Java 实现有预算与取消语义的 Agent Runtime，支持结构化 Tool 调用、会话确认记忆、带引用检索和 MCP 人工审批；提供按需 Spring Boot Starter、六类独立消费者与26场景离线评测，验证失败、取消和副作用未知状态不被伪装为成功。

不要增加没有数据支持的吞吐量、可用性、成本节省、真实模型准确率或生产用户规模。Fixture token 不是真实计费；延迟依赖本机/H2/回环服务，不能外推生产。真实付费模型、外部Qdrant、第三方MCP在008未重测。Microsoft Learn初始化/发现曾通过，但Schema兼容限制仍在。

本地 install、Git 分支推送、main 合入、外部发行是四个不同状态。本任务只授权完成并推送007/008分支，不自动合main，不发布Maven Central、Release、标签或站点。
