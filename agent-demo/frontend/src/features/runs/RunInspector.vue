<script setup lang="ts">
import { X, Pause, RefreshCw } from '@lucide/vue';
import type { RunSnapshot, Step } from '../../domain/contracts';
import type { ObserverState } from '../../composables/useRunObserver';
import StepTimeline from './StepTimeline.vue';
import RunStatus from './RunStatus.vue';
defineProps<{ snapshot: RunSnapshot | null; steps: Step[]; summaries: string[]; state: ObserverState }>();
const emit = defineEmits<{ close: []; pause: []; resume: [] }>();
</script>
<template><aside class="run-inspector" aria-label="任务执行详情"><header><h2>执行详情</h2><button class="icon-button" aria-label="关闭详情" @click="emit('close')"><X :size="20"/></button></header><template v-if="snapshot"><RunStatus :status="snapshot.status" :cancel-requested="snapshot.cancelRequested"/><p class="small muted inspector-note">执行过程只展示已记录步骤，不展示模型内部思考。</p><div class="observer-actions"><button v-if="['connecting','live','polling','reconnecting'].includes(state)" class="text-button" @click="emit('pause')"><Pause :size="14"/>停止观察</button><button v-else-if="state !== 'closed'" class="text-button" @click="emit('resume')"><RefreshCw :size="14"/>恢复观察</button><span class="small muted">{{ state }}</span></div><p v-if="['paused','manual-refresh','reconnecting'].includes(state)" class="small warning-text">当前状态可能不是最新。停止观察不会取消任务。</p><StepTimeline :steps="steps" :summaries="summaries"/><div class="usage-panel"><h3>服务端报告用量</h3><p v-if="snapshot.usage?.totalTokens" class="small muted">输入 {{ snapshot.usage.promptTokens }} · 输出 {{ snapshot.usage.completionTokens }} · 合计 {{ snapshot.usage.totalTokens }} tokens</p><p v-else class="small muted">未提供可确认用量</p><p v-if="snapshot.terminationReason" class="small muted">结束原因：{{ snapshot.terminationReason }}</p></div></template><p v-else class="muted">选择一轮任务以查看详情。</p></aside></template>
