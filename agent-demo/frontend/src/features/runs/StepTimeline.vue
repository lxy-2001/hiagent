<script setup lang="ts">
import type { Step } from '../../domain/contracts';
defineProps<{ steps: Step[]; summaries: string[] }>();
</script>
<template><ol class="step-timeline"><li v-for="step in steps" :key="step.stepNo"><span class="step-number">{{ step.stepNo }}</span><div><strong>{{ step.toolName ?? step.stepType }}</strong><span class="small muted">{{ step.status }} · {{ step.latencyMs }} ms</span><p v-if="step.errorCode" class="small error-text">{{ step.errorCode }}</p><details v-if="step.output || step.input"><summary>查看步骤摘要</summary><pre v-if="step.input">{{ step.input }}</pre><pre v-if="step.output">{{ step.output }}</pre></details></div></li><li v-for="(summary, index) in steps.length ? [] : summaries" :key="index"><span class="step-number">{{ index + 1 }}</span><p>{{ summary }}</p></li></ol><p v-if="!steps.length && !summaries.length" class="muted small">暂无已记录步骤。</p></template>
