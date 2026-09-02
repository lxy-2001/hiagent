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
| [`constitution.md`](../.specify/memory/constitution.md) | 所有 Feature 必须遵守的自研边界、质量和范围规则 | 某一项功能的详细需求 |
| [`AGENTS.md`](../AGENTS.md) | 编码代理如何实施、测试、提交和推送 | Agent Runtime 的产品行为 |
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
- 超过约 20 个耦合度不高的实施任务。
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

当前激活 Feature 由 `.specify/feature.json` 指向
`specs/001-engineering-baseline-starter/`，状态为 `VERIFIED`。Phase 1～6 已完成并有对应
阶段提交；分支推送后仍需核对 GitHub Actions 的远端结果。

Feature 001 只负责让构建、测试、自动配置、凭据治理和模块边界可信，不在同一 Feature 中
开发新的 Runtime、RAG、记忆或 MCP 行为。后续能力仍须按路线图单独建立规格、计划和任务。

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
- 具体 Git 安全和提交格式以 [`AGENTS.md`](../AGENTS.md) 为准。

## 9. 变更规则

- 改变整个产品方向、V1 边界或 Feature 顺序：先改 `ROADMAP.md` 和必要的宪法/模板。
- 改变当前 Feature 的行为或范围：先改当前 `spec.md`。
- 改变技术设计：同步当前 `plan.md`，再修订 `tasks.md`。
- 新需求形成独立结果：加入路线图，后续创建新 Feature，不塞入当前 Feature。
- 发现现有代码已完成部分任务：通过测试和 `converge` 记录证据，不凭印象勾选。
- 发现企业能力可能有价值：先记入 V1.1 候选，不在 V1 Feature 中提前实现。
