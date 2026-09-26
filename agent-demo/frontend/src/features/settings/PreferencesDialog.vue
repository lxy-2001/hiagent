<script setup lang="ts">
import ModalDialog from '../../components/ui/ModalDialog.vue';
import { preferences, type Theme } from '../../state/preferences';
const emit = defineEmits<{ close: [] }>();
const options: { value: Theme; label: string; text: string }[] = [{ value: 'light', label: '浅色', text: '干净明亮，专注阅读' }, { value: 'dark', label: '深色', text: '柔和对比，适合夜间' }, { value: 'system', label: '跟随系统', text: '自动匹配设备外观' }];
</script>
<template><ModalDialog title="外观偏好" @close="emit('close')"><p class="muted">让工作台适合你的习惯。</p><fieldset class="theme-options"><legend class="sr-only">选择主题</legend><label v-for="option in options" :key="option.value"><input type="radio" name="theme" :value="option.value" :checked="preferences.theme.value === option.value" @change="preferences.setTheme(option.value)"/><span><strong>{{ option.label }}</strong><small>{{ option.text }}</small></span></label></fieldset><p class="muted small">仅保存外观偏好，不保存账号凭据或对话内容。</p></ModalDialog></template>
