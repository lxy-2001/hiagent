# H01 Responses Adapter Contract

## 1. 请求

默认模型gpt-6.1-sol，base-url与model从agentflow.harness.model读取、encoding从context配置读取，Run开始冻结配置指纹。Agent接口POST /responses；旧普通chat/embedding端口及其agentflow.model配置不改；不能把仅支持Responses的默认模型强塞给旧chat接口。

发送完整有效input与稳定工具顺序；store=false、parallel_tool_calls=false、max_output_tokens=min(32768,本Run输出剩余)。不传previous_response_id，不启用远端自动截断或自动compaction来绕过本地检查点。工具Schema沿用现有参数约束，不扩展并发工具能力。

provider opaque续接由ResponsesContinuationStore保存；Core仅持不可解释引用，agent-llm在构造下一次请求时恢复必要reasoning/function项。store=false所需encrypted_content按实际API能力请求/保留；不能只留下call_id丢掉协议要求的续接项。不得把隐藏推理解密、改写为公开思考或写入普通日志。公开正文不含该payload。

主模型和摘要使用同一配置快照。HTTP连接超时10秒，单请求默认120秒且不超过Run剩余时间；取消尽力关闭请求，迟到响应不能引发工具。响应体硬上限8MiB；超限/截断为明确错误。

## 2. 输出映射

- 按output顺序读取message文本、function_call及必要reasoning引用，不能仅取output_text。
- function_call的call_id原样关联function_call_output；name经既有ProviderToolNames映射还原。arguments必须是完整合法JSON并经过现有Schema校验。
- message.phase等provider标识保留在适配信息中；final_answer或可确认的结束输出映射FINAL，commentary映射工作消息。未结束而无工具时可CONTINUE并占一次既有决策迭代；不引入阶段Judge。
- 多于一个function_call整体INVALID_DECISION，执行次数为0；不会静默挑第一个。
- incomplete、error、refusal与completed分开；不把部分答案/截断JSON作为成功决策。不能因一个同响应文本缺失而丢弃已确认的工具关联。
- 使用usage.input_tokens/output_tokens及已提供子项；cached_tokens是输入子集，reasoning_tokens是输出子集。缺失为未知，显式0为0；同一request_key只记一次。失败响应如有usage也要记。

## 3. 摘要请求

使用ResponsesCompactionModel及固定Prompt，不传工具；指定有界JSON对象栏目并严格解析。覆盖序号、checkpointId、pin集合由程序掌握，不让模型填充可信状态。payload里的“测试已通过”仍只是引用历史事实，格式校验不等于语义认证。

32,768是生成请求总输出上限，8,192为可见摘要上限；二者与reasoning预算分开检查。摘要响应的incomplete不提交。取消/超时/usage与正常模型调用走统一账本，不能做“免费不计数”的隐藏请求。

## 4. 编码、缓存及降级

JTokkit1.1.0/o200k_base为显式可配置估算；无该模型精确映射时保留估算标识和安全余量，禁止伪称精确计费。计数覆盖实际alias后的tools JSON、协议边界和长参数；opaque量使用可用计数提示或保守上界，不能忽略。

缓存只做稳定前缀及真实cached_tokens记录；不复用请求ID假装命中。模型切换后重新估算/清理不兼容续接，不把旧远端历史接回窗口。接口不支持必需能力时明确报错，不静默改用Chat Completions或换模型。

## 5. 验证Fixture

同响应text+call；commentary→call→output→final；两次工具交互所需opaque重放；complete/incomplete/refusal；非法JSON/未知name/多调用；usage缺失/0/缓存子项；摘要用量；取消后的迟到响应；8MiB截断；重启本地恢复不依赖previous_response_id。所有默认测试HTTP Mock且不读取真实Key。
