<script setup lang="ts">
import { ref } from 'vue';
import type { Citation } from '../../domain/contracts';
defineProps<{ citations: Citation[]; runId: string; selected?: string }>();
const message = ref('');
async function copy(value: string) { try { await navigator.clipboard.writeText(value); message.value = '片段已复制'; } catch { message.value = '复制失败，请手动选择片段。'; } }
</script>
<template><section class="source-panel" aria-label="引用来源"><p class="small muted">仅展示本轮成功回答返回的来源，来源路径不会自动打开。</p><p v-if="!citations.length" class="empty-panel">本轮没有可核对的引用来源。</p><article v-for="source in citations" :key="runId + ':' + source.id" :class="{ highlighted: selected === source.id }"><span class="source-id">[{{ source.id }}]</span><h3>{{ source.title }}</h3><p class="source-path">{{ source.sourcePath }}</p><p class="small muted">版本 {{ source.documentVersion }} · 片段 {{ source.start }}–{{ source.end }}</p><blockquote>{{ source.excerpt }}</blockquote><button class="text-button" @click="copy(source.excerpt)">复制片段</button><details><summary>核对来源标识</summary><p class="small source-path">文档 {{ source.docId }}<br>快照 {{ source.snapshotId }}<br>内容校验 {{ source.contentHash }}</p></details></article><p role="status" class="small muted">{{ message }}</p></section></template>
