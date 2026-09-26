<script setup lang="ts">
import { ref, computed } from 'vue';
import { useMedia, useFocusScope } from '../../composables/useFocusScope';
import { X, Pause, RefreshCw } from '@lucide/vue';
import type { RunSnapshot, Step } from '../../domain/contracts';
import type { ObserverState } from '../../composables/useRunObserver';
import StepTimeline from './StepTimeline.vue';
import SourcePanel from '../evidence/SourcePanel.vue';
import MemoryPanel from '../memory/MemoryPanel.vue';
import RunStatus from './RunStatus.vue';
const props = defineProps<{ loading?: boolean; snapshot: RunSnapshot | null; steps: Step[]; summaries: string[]; state: ObserverState; sessionId: string | null; tab: 'execution' | 'sources' | 'memory'; selectedSource?: string }>();
const sourceState = computed(() => {
  const retrieval = props.steps.filter(step => step.toolName === 'knowledge.search');
  if (retrieval.some(step => step.errorCode?.startsWith('RAG_')) || props.snapshot?.errorCode?.startsWith('RAG_')) return 'failed';
  if (retrieval.some(step => /(?:^|\s)kept=0(?:\s|$)/.test(step.output ?? ''))) return 'empty';
  return 'none';
});
const emit = defineEmits<{ close: []; pause: []; resume: []; tab: [value: 'execution' | 'sources' | 'memory'] }>();
const panel = ref<HTMLElement>();
const overlay = useMedia('(max-width: 1279px)');
useFocusScope(panel, overlay, () => emit('close'));
const observerLabels: Record<ObserverState, string> = { connecting: '正在连接', live: '实时观察', reconnecting: '正在重连', polling: '查询最新状态', paused: '观察已暂停', 'manual-refresh': '请手动恢复观察', unauthorized: '需要重新登录', closed: '观察已结束' };
function changeTab(event: KeyboardEvent) {
  const tabs = ['execution', 'sources', 'memory'] as const;
  const index = tabs.indexOf(props.tab);
  const next = event.key === 'ArrowRight' ? (index + 1) % 3 : event.key === 'ArrowLeft' ? (index + 2) % 3 : event.key === 'Home' ? 0 : event.key === 'End' ? 2 : -1;
  if (next < 0) return; event.preventDefault(); emit('tab', tabs[next]!); panel.value?.querySelectorAll<HTMLButtonElement>('[role=tab]')[next]?.focus();
}
</script>
<template><aside ref="panel" class="run-inspector" :role="overlay ? 'dialog' : undefined" :aria-modal="overlay ? true : undefined" aria-label="任务执行详情"><header><h2>执行详情</h2><button class="icon-button" aria-label="关闭详情" @click="emit('close')"><X :size="20"/></button></header><div class="inspector-tabs" role="tablist" aria-label="任务详情分类" @keydown="changeTab"><button v-for="item in ([{ key: 'execution', label: '执行' }, { key: 'sources', label: '来源' }, { key: 'memory', label: '记忆' }] as const)" :key="item.key" role="tab" :aria-selected="tab === item.key" :tabindex="tab === item.key ? 0 : -1" @click="emit('tab', item.key)">{{ item.label }}</button></div><p v-if="loading" role="status" class="loading-state">正在读取任务详情…</p><SourcePanel v-else-if="tab === 'sources'" :empty-state="sourceState" :citations="snapshot?.status === 'SUCCEEDED' ? snapshot.citations : []" :run-id="snapshot?.runId ?? ''" :selected="selectedSource"/><MemoryPanel v-else-if="tab === 'memory'" :session-id="sessionId"/><template v-else-if="snapshot"><RunStatus :status="snapshot.status" :cancel-requested="snapshot.cancelRequested"/><p class="small muted inspector-note">执行过程只展示已记录步骤，不展示模型内部思考。</p><div class="observer-actions"><button v-if="['connecting','live','polling','reconnecting'].includes(state)" class="text-button" @click="emit('pause')"><Pause :size="14"/>停止观察</button><button v-else-if="state !== 'closed'" class="text-button" @click="emit('resume')"><RefreshCw :size="14"/>恢复观察</button><span class="small muted">{{ observerLabels[state] }}</span></div><p v-if="['paused','manual-refresh','reconnecting'].includes(state)" class="small warning-text">当前状态可能不是最新。停止观察不会取消任务。</p><StepTimeline :steps="steps" :summaries="summaries"/><div class="usage-panel"><h3>服务端报告用量</h3><p v-if="snapshot.usage !== null" class="small muted">输入 {{ snapshot.usage.promptTokens }} · 输出 {{ snapshot.usage.completionTokens }} · 合计 {{ snapshot.usage.totalTokens }} tokens</p><p v-else class="small muted">未提供可确认用量</p><p v-if="snapshot.terminationReason" class="small muted">结束原因：{{ snapshot.terminationReason }}</p></div></template><p v-else class="muted">选择一轮任务以查看详情。</p></aside></template>
