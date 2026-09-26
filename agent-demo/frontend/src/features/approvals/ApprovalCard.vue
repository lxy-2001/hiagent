<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue';
import { ShieldCheck } from '@lucide/vue';
import type { Approval } from '../../domain/contracts';
const props = defineProps<{ approval: Approval; decide: (decision: 'APPROVE' | 'REJECT') => Promise<void> }>();
const busy = ref(false), attempted = ref(false), error = ref(''), now = ref(Date.now());
const timer = setInterval(() => { now.value = Date.now(); }, 1000); onBeforeUnmount(() => clearInterval(timer));
const remaining = computed(() => Math.max(0, Math.ceil((Date.parse(props.approval.expiresAt) - now.value) / 1000)));
const eligible = computed(() => props.approval.status === 'PENDING' && remaining.value > 0);
const outcome = computed(() => {
  if (props.approval.outcome === 'UNKNOWN') return '执行结果未知：操作可能已经发生，请先核对实际结果。';
  if (props.approval.outcome === 'SUCCEEDED') return '工具执行成功。';
  if (props.approval.outcome === 'FAILED') return '工具执行失败，请查看任务结果。';
  if (props.approval.status === 'APPROVED') return '已批准，等待工具执行结果。';
  return ({ PENDING: remaining.value ? '此操作需要你确认。' : '本地倒计时已结束，请刷新审批状态。', REJECTED: '你已拒绝本次操作。', EXPIRED: '审批已过期。', CANCELLED: '审批已取消。', INVALIDATED: '审批已失效。', INTERRUPTED: '审批已中断。' } as Record<string, string>)[props.approval.status] ?? '请核对最新审批状态。';
});
async function submitDecision(value: 'APPROVE' | 'REJECT') {
  if (busy.value || attempted.value || !eligible.value) return; busy.value = true; attempted.value = true;
  try { await props.decide(value); }
  catch { error.value = '未能确认决定结果。请读取最新状态，不要重复提交。'; }
  finally { busy.value = false; }
}
</script>
<template><section class="approval-card" aria-label="工具审批"><div class="approval-heading"><ShieldCheck :size="19"/><strong>需要你的确认</strong><span>{{ approval.risk === 'HIGH' ? '高风险操作' : approval.risk }}</span></div><h3>{{ approval.actionSummary }}</h3><p class="small muted">工具：{{ approval.toolName }}</p><dl><template v-for="arg in approval.argumentPreview" :key="arg.name"><dt>{{ arg.name }} <span v-if="arg.masked">已遮蔽</span><span v-if="arg.truncated"> · 已截断</span></dt><dd>{{ arg.value }}</dd></template></dl><p class="approval-outcome" role="status">{{ outcome }}</p><p class="small muted">批准仅授权本次操作；取消不能撤销已发生的工具执行。</p><p v-if="error" role="alert" class="error-text small">{{ error }}</p><div v-if="eligible" class="approval-actions"><button data-decision="REJECT" :disabled="busy || attempted" @click="submitDecision('REJECT')">拒绝</button><button class="primary" data-decision="APPROVE" :disabled="busy || attempted" @click="submitDecision('APPROVE')">{{ busy ? '正在确认…' : '批准本次操作' }}</button><span class="small muted">约 {{ remaining }} 秒后到期</span></div></section></template>
