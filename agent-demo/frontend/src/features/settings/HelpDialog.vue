<script setup lang="ts">
import ModalDialog from '../../components/ui/ModalDialog.vue';
const emit = defineEmits<{ close: [] }>();
</script>
<template><ModalDialog title="使用工作台" @close="emit('close')"><div class="help-copy"><h3>从一个具体问题开始</h3><p>输入任务后，HiAgent 会调用已配置的模型，并按需使用工具。执行过程中可以查看步骤；这些是步骤事件，不是逐字生成的回答。</p><h3>关键操作由你决定</h3><p>需要审批的工具会先展示操作内容。批准只是授权执行，不代表操作已成功。结果未知时，不要重复提交。</p><h3>来源与记忆</h3><p>知识库来源来自服务端返回的引用。会话记忆仅保存你确认的语言与技术栈偏好。演示草稿工具不会修改你的项目文件。</p><h3>快捷键</h3><p><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行 · <kbd>Esc</kbd> 关闭弹窗。中文输入法选词时不会发送。</p><p class="muted">离线演示使用确定性测试数据；“已配置模型”仅说明配置存在，不表示服务已经连通。</p></div></ModalDialog></template>
