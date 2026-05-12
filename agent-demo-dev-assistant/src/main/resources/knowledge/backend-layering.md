# Java 后端分层约定

Controller 层只处理协议、参数校验、认证信息提取和响应封装。

Service 层负责编排业务流程、事务边界、幂等校验和领域规则。

Repository 或 Mapper 层只负责数据访问，不直接处理跨资源一致性。

复杂并发场景中，应明确 Redis、MySQL、消息补偿和任务重试的职责边界，避免把所有逻辑写在 Controller 中。
