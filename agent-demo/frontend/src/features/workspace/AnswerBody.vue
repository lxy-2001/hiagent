<script setup lang="ts">
import { computed, ref } from 'vue';
import { Copy, Check } from '@lucide/vue';
import type { Citation } from '../../domain/contracts';
import { renderMarkdown, unboundCitations } from '../../security/markdown';
const props = defineProps<{ text: string; citations?: Citation[] }>();
const emit = defineEmits<{ source: [id: string] }>();
const unmatched = computed(() => props.text.length <= 65536 && unboundCitations(props.text, props.citations?.map(c => c.id) ?? []));
function click(event: MouseEvent) { const button = (event.target as Element).closest<HTMLButtonElement>('button[data-source-id]'); const id = button?.dataset.sourceId; if (id && props.citations?.some(c => c.id === id)) emit('source', id); }
const copyState = ref('');
const html = computed(() => { try { return renderMarkdown(props.text, props.citations?.map(c => c.id)); } catch { return '<p>回答超过安全展示上限，无法在此展开。</p>'; } });
async function copy() { try { await navigator.clipboard.writeText(props.text); copyState.value = '已复制'; } catch { copyState.value = '无法访问剪贴板，请手动选择文本复制。'; } }
</script>
<template><div class="answer-body"><div class="markdown-body" @click="click" v-html="html"/><p v-if="unmatched" class="small muted">部分标号未匹配来源，请核对本轮来源面板。</p><div class="answer-actions"><button class="icon-button" aria-label="复制回答" @click="copy"><Check v-if="copyState === '已复制'" :size="16"/><Copy v-else :size="16"/></button><span role="status" class="small muted">{{ copyState }}</span></div></div></template>
