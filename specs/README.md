# HiAgent SDD 使用说明

本目录采用“项目路线图 + 单 Feature SDD”的方式交付 HiAgent。路线图回答整个项目最终
证明什么和先后顺序；一个 Feature 只回答本轮独立交付什么。这样可以避免把所有愿望写进
一个无法实施和验收的大 SDD。

## 1. 两层规划

### 项目级规划

项目级文件长期存在，不直接交给 `speckit-implement`：

| 文件 | 回答的问题 | 不负责的内容 |
| --- | --- | --- |
| [`ROADMAP.md`](ROADMAP.md) | V1 做到哪里、8 个 Feature 的结果、依赖、顺序和状态 | 类、方法和逐文件任务 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 跨 Feature 的执行顺序、数据负责人、预算和交接 | 代替 Feature 任务提前实现 |
| [`REMAINING-FLOW.md`](REMAINING-FLOW.md) | 006–008的职责、事实数据、失败语义与验收交接 | 一次性冻结后续全部实现任务 |
| [`DESIGN-REVIEW.md`](DESIGN-REVIEW.md) | 本轮设计问题、修订和检查证据 | Java 实现完成声明 |
| [`constitution.md`](../.specify/memory/constitution.md) | 所有 Feature 必须遵守的自研边界、质量和范围规则 | 某一项功能的详细需求 |
| `AGENTS.md` | 编码代理如何实施、测试、提交和推送 | Agent Runtime 的产品行为 |
| [`_project/complete-agent-backend-v1/`](_project/complete-agent-backend-v1/) | 早期企业化目标和候选需求来源 | 当前可直接实施的规格 |

历史蓝图不能覆盖路线图或当前 Feature。身份、分布式任务、生产部署等历史设计默认属于
V1.1 候选。

### Feature 级 SDD

每次只选择一个可独立演示、测试和收尾的能力，建立：

```text
specs/NNN-feature-name/
├── spec.md             # 唯一结果、场景、需求、范围和验收
├── plan.md             # 自研/复用边界、架构取舍和验证方案
├── research.md         # 存在待决技术问题时创建
├── data-model.md       # Feature 引入数据模型时创建
├── contracts/          # 稳定公开 HTTP、Tool 或模块契约
├── quickstart.md       # 可复现的独立验收路径
├── tasks.md            # 测试优先、依赖有序、按 Phase 分组的任务
├── checklists/         # 需求文本质量检查
└── verification.md     # 命令、结果、简历证据和已知限制
```

Feature 可以修改多个 Maven 模块。它代表一个可交付能力，不等于模块、接口或任意代码
动作。

## 2. 标准流程

```text
维护 ROADMAP 的项目目标和 Feature 顺序
        ↓
选择唯一 NEXT Feature
        ↓
specify：写唯一结果、需求、范围和验收
        ↓
clarify：解决会影响行为、范围或验收的模糊点
        ↓
plan：设计自研机制、复用基础设施、模块边界和验证
        ↓
tasks：生成测试优先、依赖有序、含明确路径的 Phase
        ↓
analyze：检查 spec / plan / tasks 的一致性和完整性
        ↓
implement：按 Red → Green → Refactor 实施
        ↓
verify：运行任务、Phase 和 Feature 分层验证
        ↓
converge：把代码与文档之间的剩余差距补回 tasks
        ↓
更新为 VERIFIED，推送 Feature 分支，选择下一个
```

`analyze` 在实现前检查文档之间是否一致；`converge` 在实现后检查代码是否真正完成
文档要求。两者不能互相替代。

## 3. 前两步的边界

### 维护项目总规划

这里只决定：

- 项目定位和 V1 完成形态。
- 必须自研和应复用的边界。
- Feature 的唯一结果、先后依赖和延期范围。
- 哪些能力属于 V1.1，当前不能顺手做。

这里不设计具体类、方法、表、接口字段或逐文件任务。

### 选择下一个 Feature

选择的不是“下一批想做的代码”，而是一个可以独立回答以下问题的交付单位：

1. 做完后新增的唯一可观察结果是什么？
2. 为证明结果，哪些行为必须在本轮实现？
3. 哪些相邻能力明确不做？
4. 它依赖哪个已经验证的 Feature 或稳定契约？
5. 用什么测试、演示和测量结果证明完成？

选择完成只把该条目标记为 `NEXT`；创建规格并确认开始后才进入 `ACTIVE`。单人流程同时
最多一个 `ACTIVE` Feature。

## 4. Agent 核心 Feature 的额外要求

每个 Runtime、Tool、上下文、记忆、RAG、MCP 或评测 Feature 的 `plan.md` 必须明确：

- **Project-Owned**：本项目具体实现哪些 Agent 机制。
- **Reused Infrastructure**：复用哪些通用库、协议或存储。
- **Framework Boundary**：如果直接使用 Spring AI/其他框架会怎么做，本项目为何保留这层
  自研，以及第三方类型停在哪里。
- **Portfolio Evidence**：准备讲解的核心代码路径、可复现演示和量化证据。

“自己实现”不是重写 HTTP、JSON、数据库或向量库；“复用框架”也不能把主 Agent 循环
交给黑盒。

## 5. 如何划定一个 Feature

合理 Feature 通常：

- 只有一个可以独立表述和演示的结果。
- 有正常、失败、取消/超时/预算等相关边界场景。
- 默认测试不依赖真实付费模型或不可控外部服务。
- 有一条核心代码路径和一组可保留的简历证据。
- 通常能在数天到两周内完成，而不是持续数月。

需要继续拆分的信号：

- 同时出现两个可独立演示的结果。
- 存在多组可独立交付的任务，且结果/风险边界不同；测试与实现分列造成的条数多，不能仅凭“超过20项”要求拆Feature。
- 同时跨越 Agent 算法、身份、分布式运行和部署等不同风险主题。
- 大部分任务只是“以后可能用到”的基础设施或抽象。

不合理示例：

- “完善整个 Agent 后端”：结果太多。
- “创建一个接口类”：只有代码动作，没有独立能力。
- “完成 agent-rag 模块”：按目录而不是结果划分。
- “顺便加 Redis、K8s 和 RBAC”：没有当前验收需求。

合理示例：

- “模型在预算限制的循环中选择本地 Tool 并返回最终答案。”
- “晚订阅客户端能补齐缓冲事件并继续接收实时事件。”
- “最终答案的每条引用都能定位到真实知识片段。”

## 6. 当前状态与下一步

2026-09-26：001–008 VERIFIED；007/008已在独立分支交付，008包含007，尚未自动合main。main的006基线与各Feature记录见ROADMAP，不以本地设计状态冒充远端交付。

2026-10-07：[010 / H01上下文与会话历史升级](010-context-history-compaction/spec.md)已VERIFIED，4个Phase、59/59项任务完成。复用原Runtime，已实现H2本地存储、可靠消息历史、有界恢复/压缩、Responses适配及两级附件LRU；全仓clean verify为1,019项测试、0失败、0错误、1项既有可选Qdrant跳过，主线用量断言单独通过。analyze/converge通过，阶段提交已完成；最终状态文档随Feature分支交付，实际SHA、命令与限制见[verification](010-context-history-compaction/verification.md)。SDD指针保留010便于查证，尚未激活下一Feature。

[009 产品化Agent工作台](009-agent-workbench/spec.md)已按既有[验证记录](009-agent-workbench/verification.md)完成 Phase1–8/62项；本次同步旧状态，不代表重新运行其测试或将009代码合入当前008 checkout。

009属于用户明确选定的V1.1前端扩展，不是补写008原验收。前端美观/完整交互包含在内，后台管理、企业运营和实际部署仍排除。代码基线必须包含已验证007/008；不在旧main直接实施，不自动合并。

mvnw、learning和既有HTML用户文件保留。010规格/契约/验收及本次项目状态文档纳入Feature分支；其余历史specs/.specify仍按原忽略规则管理。远端交付以真实推送回执确认。

## 7. 状态规则

| 状态 | 含义 |
| --- | --- |
| `PLANNED` | 已进入路线图，依赖尚未满足或还不是下一项 |
| `NEXT` | 推荐下一项，尚未激活实施 |
| `ACTIVE` | 当前唯一正在走 SDD 或实施的 Feature |
| `VERIFIED` | 验收、全仓测试、文档、analyze 和 converge 均通过 |
| `DEFERRED` | 明确延期，不属于当前 V1 路径 |

只写完代码、只跑一个测试或只勾完 `tasks.md`，都不能单独改为 `VERIFIED`。

## 8. Phase、提交和推送

- `tasks.md` 中一个 `## Phase N` 才是阶段，不是单个任务或一次对话。
- 每个任务先运行目标测试；Phase 完成运行受影响模块和依赖模块；Feature 完成运行全仓
  验证。
- Phase 的所有任务和验证通过后创建一个阶段完成提交。
- 一个 Feature 完成 `analyze`、`converge`、全仓验证和路线图更新后，推送它的独立分支。
- 默认不直接合并 `main`，不自动创建 Release 或标签。
- 具体 Git 安全和提交格式以 `AGENTS.md` 为准。

## 9. 变更规则

- 改变整个产品方向、V1 边界或 Feature 顺序：先改 `ROADMAP.md` 和必要的宪法/模板。
- 改变当前 Feature 的行为或范围：先改当前 `spec.md`。
- 改变技术设计：同步当前 `plan.md`，再修订 `tasks.md`。
- 新需求形成独立结果：加入路线图，后续创建新 Feature，不塞入当前 Feature。
- 发现现有代码已完成部分任务：通过测试和 `converge` 记录证据，不凭印象勾选。
- 发现企业能力可能有价值：先记入 V1.1 候选，不在 V1 Feature 中提前实现。

## 10. 本轮设计文件和版本管理

先读 [全流程设计](ARCHITECTURE.md)，再按ROADMAP进入当前Feature的spec→plan→tasks。
已有功能回归属于后续Feature验收，不回写001/002规格或修改其完成记录。
这不限制对已有源码的必要修改或重构；用户已明确允许为后续Feature演进前面的代码。
如需改变既有行为或契约，应在当前Feature文档中明确变更并同步任务和验证，不能因保留旧代码而重复实现。

当前Git忽略specs和.specify，这些文档是本地工作资料，不会随代码普通提交同步GitHub。
本轮不擅自改变忽略规则或强制暂存；如希望SDD与代码一起交付，需要另行明确文档版本管理。
不要把“本地文档已更新”写成“远端设计已发布”。

> 2026-09-25：用户要求同时设计007/008，允许008先完成预设计，但不同时实施。下一步实施007 Phase1；008开始前必须复核007真实交付。Microsoft Learn初始化/发现已通过，工具调用仍受Schema子集限制。

2026-09-26实施状态：007 Phase1–5 VERIFIED，全仓与78次离线基线通过；008随后实施。SDD仍为本地忽略文档。

2026-09-26最终更新：007 VERIFIED；008 Phase1–4 VERIFIED，全仓925/Node21/六消费者/复制78基线通过。main未自动合入，外部发行NOT_PUBLISHED；旧段落为历史设计时状态。

2026-09-26当前设计更新：009 ACTIVE / DESIGN_READY（仅文档）；原007/008设计/实施段落是历史记录，当前实施任务入口见第6节。

2026-09-27当前实施状态：009 Phase1–7已提交，Phase8收敛修复进入最终验收；62项任务，代码在独立工作区，尚未推送/合main/部署。以009 verification后续记录为准。

009最终验收：VERIFIED，Phase1–8/62项完成。工作台指南见 ../docs/workbench.md；实际提交和推送SHA见009 verification；未合main/部署。
