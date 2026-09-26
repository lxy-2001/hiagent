const messages: Record<string, string> = {
  INVALID_REQUEST: '请检查填写内容后再试。', UNAUTHORIZED: '登录已失效，请重新登录。',
  NOT_FOUND: '会话或任务不存在，或你没有访问权限。', RATE_LIMITED: '请求较多，请稍后再试。',
  SESSION_BUSY: '这个会话仍有任务运行，正在恢复任务状态。',
  MEMORY_VERSION_CONFLICT: '记忆已在其他页面更新，请核对最新值。',
  SERVICE_RECOVERING: '服务正在恢复历史记录，请稍后再试。',
  RUN_CAPACITY_EXCEEDED: '当前任务较多，请稍后提交。',
  PERSISTENCE_UNAVAILABLE: '暂时无法读取完整状态，请稍后恢复观察。',
  DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用。', INVALID_RESPONSE: '服务返回了无法识别的数据。',
  NETWORK_ERROR: '连接暂时中断，请检查网络。', UNKNOWN_SUBMISSION: '提交结果未确认，请先核对会话，避免重复执行。',
};
export function errorMessage(code: string): string { return messages[code] ?? '操作暂未完成，请核对状态后再试。'; }
