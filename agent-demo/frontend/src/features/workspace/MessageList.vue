<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted } from 'vue';
import { ChevronRight } from '@lucide/vue';
import type { Turn, RunSnapshot } from '../../domain/contracts';
import AnswerBody from './AnswerBody.vue';
import RunStatus from '../runs/RunStatus.vue';
import { useScrollAnchor } from '../../composables/useScrollAnchor';
const props = defineProps<{ turns: Turn[]; snapshot: RunSnapshot | null; older: boolean; loading: boolean; loadOlder: () => Promise<void> }>();
const emit = defineEmits<{ inspect: [runId: string]; source: [runId: string, sourceId: string] }>();
const scroll = ref<HTMLElement>(); const anchor = useScrollAnchor(scroll);
const rows = computed(() => props.turns.filter(t => t.runId !== props.snapshot?.runId));
const nearBottom = ref(true), unread = ref(false), prepending = ref(false);
function position() { const node = scroll.value; if (node) nearBottom.value = node.scrollHeight - node.clientHeight - node.scrollTop < 80; }
async function bottom() { await nextTick(); if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight; unread.value = false; }
async function loadPrevious() { prepending.value = true; try { await anchor.prepend(props.loadOlder); } finally { prepending.value = false; } }
watch(() => [props.turns.length, props.snapshot?.status, props.snapshot?.finalAnswer], async () => { if (prepending.value) return; if (nearBottom.value) await bottom(); else unread.value = true; });
onMounted(bottom);
</script>
<template><div ref="scroll" class="message-scroll" @scroll="position"><div class="message-list"><button v-if="older" class="load-history" :disabled="loading" @click="loadPrevious">{{ loading ? '正在加载…' : '加载更早的记录' }}</button><article v-for="turn in rows" :key="turn.runId" class="turn" :data-run-id="turn.runId"><div class="user-message"><span class="message-role">你</span><p>{{ turn.contentUnavailable ? '这条历史记录的内容暂不可读。' : turn.input }}</p></div><div class="assistant-message"><div class="message-heading"><strong>HiAgent</strong><RunStatus :status="turn.status"/></div><AnswerBody v-if="turn.finalAnswer && !turn.contentUnavailable" :text="turn.finalAnswer"/><p v-else class="muted">{{ turn.contentUnavailable ? '记录位置已保留，内容无法安全展示。' : '本轮未产生可展示的最终回答。' }}</p><button class="text-button" @click="emit('inspect', turn.runId)">查看执行详情<ChevronRight :size="14"/></button></div></article><article v-if="snapshot" class="turn" :data-run-id="snapshot.runId"><div class="user-message"><span class="message-role">你</span><p>{{ snapshot.input }}</p></div><div class="assistant-message"><div class="message-heading"><strong>HiAgent</strong><RunStatus :status="snapshot.status" :cancel-requested="snapshot.cancelRequested"/></div><AnswerBody v-if="snapshot.status === 'SUCCEEDED' && snapshot.finalAnswer" :text="snapshot.finalAnswer" :citations="snapshot.citations" @source="id => emit('source', snapshot!.runId, id)"/><p v-else-if="snapshot.status === 'WAITING_APPROVAL'" class="muted">任务需要你的确认，批准后才会继续执行。</p><p v-else-if="['QUEUED','RUNNING'].includes(snapshot.status)" class="muted">正在处理任务，你可以查看执行过程。</p><p v-else class="muted">本轮已结束，未生成最终回答。{{ snapshot.terminationReason }}</p><slot name="approvals"/><p v-if="!snapshot.recordingComplete" class="small muted">部分执行记录暂不完整。</p><button class="text-button" @click="emit('inspect', snapshot.runId)">查看执行详情<ChevronRight :size="14"/></button></div></article><p v-if="!rows.length && !snapshot" class="muted empty-history">这个会话还没有可展示的记录。</p></div><button v-if="unread" class="new-content-button" @click="bottom">有新内容 · 回到底部</button></div></template>
