<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { X } from '@lucide/vue';
defineProps<{ title: string }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>(); let previous: HTMLElement | null = null;
onMounted(() => { previous = document.activeElement as HTMLElement; dialog.value?.showModal(); });
onBeforeUnmount(() => { dialog.value?.close(); previous?.focus(); });
</script>
<template><dialog ref="dialog" class="dialog" aria-labelledby="dialog-title" @cancel.prevent="emit('close')" @click="event => { if (event.target === dialog) emit('close'); }"><header><h2 id="dialog-title">{{ title }}</h2><button class="icon-button" aria-label="关闭弹窗" @click="emit('close')"><X :size="20"/></button></header><slot/></dialog></template>
