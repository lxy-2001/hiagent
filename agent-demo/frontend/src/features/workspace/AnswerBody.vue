<script setup lang="ts">
import { computed, ref } from 'vue';
import { Copy, Check } from '@lucide/vue';
import { renderMarkdown } from '../../security/markdown';
const props = defineProps<{ text: string }>();
const copyState = ref('');
const html = computed(() => { try { return renderMarkdown(props.text); } catch { return '<p>回答超过安全展示上限，无法在此展开。</p>'; } });
async function copy() { try { await navigator.clipboard.writeText(props.text); copyState.value = '已复制'; } catch { copyState.value = '无法访问剪贴板，请手动选择文本复制。'; } }
</script>
<template><div class="answer-body"><div class="markdown-body" v-html="html"/><div class="answer-actions"><button class="icon-button" aria-label="复制回答" @click="copy"><Check v-if="copyState === '已复制'" :size="16"/><Copy v-else :size="16"/></button><span role="status" class="small muted">{{ copyState }}</span></div></div></template>
