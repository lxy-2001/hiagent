<script setup lang="ts">
import type { RunStatus } from '../../domain/contracts';
defineProps<{ status: RunStatus; cancelRequested?: boolean }>();
const labels: Record<RunStatus, string> = { QUEUED: '等待执行', RUNNING: '正在处理', WAITING_APPROVAL: '等待你的确认', SUCCEEDED: '已完成', FAILED: '执行失败', CANCELLED: '已取消', TIMED_OUT: '执行超时', BUDGET_EXCEEDED: '达到预算上限' };
</script>
<template><span class="run-status" :data-status="status"><span class="status-dot"/>{{ labels[status] }}<span v-if="cancelRequested && ['QUEUED','RUNNING','WAITING_APPROVAL'].includes(status)"> · 已请求取消</span></span></template>
