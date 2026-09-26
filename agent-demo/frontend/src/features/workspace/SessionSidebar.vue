<script setup lang="ts">
import { computed, ref } from 'vue';
import { Search, MessageSquare } from '@lucide/vue';
import type { Conversation } from '../../domain/contracts';
const props = defineProps<{ items: Conversation[]; selected: string; loading: boolean; more: boolean; error: string }>();
const emit = defineEmits<{ more: []; select: []; retry: [] }>();
const filter = ref('');
const filtered = computed(() => props.items.filter(item => item.title.toLocaleLowerCase().includes(filter.value.toLocaleLowerCase())));
function day(value: string) { const date = new Date(value), today = new Date(); const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1); return date.toDateString() === today.toDateString() ? '今天' : date.toDateString() === yesterday.toDateString() ? '昨天' : '更早'; }
</script>
<template><div class="sidebar-history"><label class="session-filter"><Search :size="15"/><input v-model="filter" aria-label="筛选已加载会话" placeholder="筛选已加载会话"/></label><p v-if="error" role="alert" class="small">{{ error }}<button class="text-button" @click="emit('retry')">重试</button></p><p v-else-if="!items.length" class="muted small">{{ loading ? '正在读取会话…' : '你的探索，将从这里开始。' }}</p><template v-for="(item, index) in filtered" :key="item.sessionId"><p v-if="index === 0 || day(filtered[index - 1]!.createdAt) !== day(item.createdAt)" class="session-group">{{ day(item.createdAt) }}</p><RouterLink :to="'/sessions/' + encodeURIComponent(item.sessionId)" class="session-link" :class="{ selected: selected === item.sessionId }" :aria-current="selected === item.sessionId ? 'page' : undefined" :title="item.title" @click="emit('select')"><MessageSquare :size="15"/><span>{{ item.title }}</span><span v-if="item.activeTaskId" class="status-dot" aria-label="有运行中的任务"/></RouterLink></template><p v-if="items.length && !filtered.length" class="muted small">已加载的会话中没有匹配项。</p><button v-if="more" class="text-button" :disabled="loading" @click="emit('more')">{{ loading ? '正在加载…' : '加载更多会话' }}</button></div></template>
