## 1. 运行时模型与网关

- [x] 1.1 定义外部智能体请求、规范化事件、结果与错误分类模型。
- [x] 1.2 在 `core/ai` 实现平台无关 `ExternalAgentGateway`：加载用户名下已启用配置并选择适配器。
- [x] 1.3 在发起任何第三方 HTTP 调用前，校验归属、启用状态、平台支持与配置是否存在。
- [x] 1.4 为 userId、configId、platform、runId、关联 id 等补充结构化日志字段。
- [x] 1.5 明确 `core/ai` 只保留通用第三方调用骨架，不包含 AIDev/Knot 协议字段、header 名称或内部域名。
- [x] 1.6 移除面向前端的外部智能体直连 Resource/API，仅保留后端工具调用用网关。

## 2. 平台适配器流式化

- [x] 2.1 在 `core/ai` 引入返回规范化外部智能体事件的流式平台适配器契约。
- [x] 2.2 在 `ext/tencent/ai` 实现 Knot 流式适配器：`stream=true`，解析 AG-UI 风格 SSE 的文本、生命周期、会话 id、错误事件。
- [x] 2.3 在 `ext/tencent/ai` 实现 AIDev 流式适配器：支持应用态/用户态 `chat_completion`，解析标准 AG-UI SSE，并用增量解析替代整包响应聚合。
- [x] 2.4 保留同步「收集流为最终结果」的辅助方法，供测试与兼容回退使用。
- [x] 2.5 明确 AIDev 蓝鲸插件调用接口不支持流式，仅作为非流式兼容或拒绝流式配置处理。
- [x] 2.6 为文本增量、错误、畸形事件、会话 id 提取、正常结束等场景补充 Mock SSE 单元测试。
- [x] 2.7 确保 AIDev/Knot 平台常量、header 校验、请求体构造和 SSE 字段解析均不落入 `core/ai`。

## 3. Supervisor 工具调用

- [x] 3.1 新增 Supervisor 路由用轻量外部智能体工具，直连 `ExternalAgentGateway`。
- [x] 3.2 将用户已启用外部智能体注册到 Supervisor Toolkit，供主智能体自动决策调用。
- [x] 3.3 支持普通聊天中 `@某个外部智能体` 作为强提示，引导主智能体优先调用对应工具。
- [x] 3.4 向 Supervisor 返回结构化最终结果（含内容与外部 conversationId，若有）。
- [x] 3.5 生产路由关闭既有外部智能体调度子智能体路径，或由回退配置保护，避免额外创建 ReActAgent。
- [x] 3.6 外部智能体调用结果纳入现有主智能体回复与 `T_AI_MESSAGE` 持久化链路。

## 4. AG-UI 事件与前端交互

- [x] 4.1 前端继续使用普通 `/user/ai/chat/run`，不新增外部智能体直连聊天入口。
- [x] 4.2 明确 `@` 外部智能体在消息文本或 AG-UI context 中的传递协议。
- [x] 4.3 路由模式下复用现有 AG-UI 事件管道推送主智能体最终回复。
- [x] 4.4 若需要展示外部智能体来源，补充工具事件或最终回复元数据约定。

## 5. 配置校验与迁移安全

- [x] 5.1 创建/更新时增加平台白名单校验。
- [x] 5.2 按适配器要求校验 URL 与 header 必填项，日志不输出密钥。
- [x] 5.3 在 `ext/tencent/ai` 校验 AIDev 应用态所需 `X-Bkapi-Authorization`、`X-BKAIDEV-USER` 及用户态 access_token 配置。
- [x] 5.4 在 `ext/tencent/ai` 校验 Knot 所需 `x-knot-api-token`、`x-knot-api-user` 及 AG-UI 端点 URL。
- [x] 5.5 兼容仅含 platform、API URL、headers、agentId 的既有数据行。
- [x] 5.6 新 UI 相关配置响应中对敏感 header 脱敏或不再返回明文。
- [x] 5.7 文档记录协议元数据、超时、密钥引用等后续迁移选项。

## 6. 超时、可观测性与验证

- [x] 6.1 支持可配置的首 token 超时、流总超时、响应大小上限及平台默认值。
- [x] 6.2 在 Supervisor Toolkit 显式覆盖 AgentScope 默认 `PT5M` 工具超时，避免长时外部调用被框架误杀。
- [x] 6.3 记录平台、configId、首 token 耗时、总耗时、响应大小、取消与失败类别等指标。
- [ ] 6.4 集成测试：主智能体自动调用、`@` 强提示调用、禁用配置拒绝、不支持平台拒绝、超时、取消。
- [ ] 6.5 路由模式测试：Supervisor 使用轻量工具且未创建外部调度 ReActAgent。
- [x] 6.6 针对网关、适配器、Supervisor 工具注册与工具调用跑通后端定向测试。
