<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { memoriesApi } from '../../api/memories';
import { ApiError } from '../../api/response';
import type { MemorySlot } from '../../domain/contracts';
const props = defineProps<{ sessionId: string | null }>();
const slots = ref<MemorySlot[]>([]), drafts = ref<Record<string, string>>({}), busy = ref(''), error = ref(''), notice = ref(''), conflicts = ref<Record<string, boolean>>({}), deleting = ref<string | null>(null), loading = ref(false);
let generation = 0, controller = new AbortController();
const labels = { preferred_language: '偏好语言', project_stack: '项目技术栈' };
watch(() => props.sessionId, async id => {
  const expected = ++generation; controller.abort(); controller = new AbortController(); slots.value = []; drafts.value = {}; conflicts.value = {}; error.value = ''; notice.value = ''; deleting.value = null; loading.value = !!id; busy.value = '';
  if (!id) return;
  try { const values = await memoriesApi.get(id, controller.signal); if (generation === expected) { slots.value = values; drafts.value = Object.fromEntries(values.map(s => [s.key, s.value ?? ''])); } }
  catch { if (generation === expected) error.value = '暂时无法读取会话记忆。'; }
  finally { if (generation === expected) loading.value = false; }
}, { immediate: true });
onBeforeUnmount(() => { generation++; controller.abort(); });
async function save(slot: MemorySlot, remove = false) {
  if (!props.sessionId || busy.value || conflicts.value[slot.key]) return;
  const value = drafts.value[slot.key] ?? ''; if (!remove && (!value.trim() || value.length > 512)) return;
  const expected = generation, id = props.sessionId; busy.value = slot.key; error.value = ''; notice.value = '';
  try { const updated = remove ? await memoriesApi.remove(id, slot.key, slot.version) : await memoriesApi.put(id, slot.key, value, slot.version);
    if (expected !== generation) return; slots.value = slots.value.map(s => s.key === updated.key ? updated : s); drafts.value[slot.key] = updated.value ?? ''; deleting.value = null; notice.value = remove ? '记忆已清除，版本已更新。' : '已保存你确认的偏好。';
  } catch (e) {
    if (expected !== generation) return;
    if (e instanceof ApiError && e.status === 409) {
      conflicts.value[slot.key] = true; error.value = '记忆已在其他页面修改。你的草稿已保留，请核对最新值后再决定。';
      try { const latest = await memoriesApi.get(id, controller.signal); if (expected === generation) slots.value = latest; } catch { if (expected === generation) error.value += ' 最新值读取失败，请关闭并重新打开面板。'; }
    } else error.value = e instanceof ApiError ? e.message : '保存结果未确认，请重新读取后核对。';
  } finally { if (expected === generation) busy.value = ''; }
}
</script>
<template><section class="memory-panel" aria-label="会话记忆"><p class="small muted">仅保存你明确确认的两项偏好，作用于当前会话。</p><p v-if="!sessionId" class="empty-panel">先开始一个会话，再保存偏好。</p><p v-if="loading" role="status">正在读取记忆…</p><p v-if="error" role="alert" class="error-box">{{ error }}</p><article v-for="slot in slots" :key="slot.key"><label :for="'memory-' + slot.key">{{ labels[slot.key] }}</label><p class="small muted">当前值：{{ slot.value ?? '未设置' }}</p><textarea :id="'memory-' + slot.key" v-model="drafts[slot.key]" rows="3" maxlength="512" :disabled="!!busy"/><p class="small muted">{{ (drafts[slot.key] ?? '').length }}/512 · 版本 {{ slot.version }}</p><div v-if="conflicts[slot.key]" class="conflict-panel"><p>请比较上方最新值与保留的草稿。</p><button class="text-button" @click="conflicts[slot.key] = false">我已核对，使用最新版本继续编辑</button></div><div class="memory-actions"><button data-action="save" class="primary" :disabled="!!busy || !!conflicts[slot.key] || !(drafts[slot.key] ?? '').trim() || (drafts[slot.key] ?? '').length > 512" @click="save(slot)">保存偏好</button><button :disabled="!!busy || slot.value === null" @click="deleting = slot.key">清除</button></div><div v-if="deleting === slot.key" class="delete-confirm"><p>清除这项已确认偏好？</p><button class="text-button" @click="save(slot, true)">确认清除</button><button class="text-button" @click="deleting = null">保留</button></div></article><p role="status" class="small muted">{{ notice }}</p></section></template>
