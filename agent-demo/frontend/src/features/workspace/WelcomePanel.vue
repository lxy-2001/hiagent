<script setup lang="ts">
import { ArrowUpRight } from '@lucide/vue';
import BrandMark from '../../components/ui/BrandMark.vue';
defineProps<{ ragEnabled: boolean }>();
const emit = defineEmits<{ example: [value: string] }>();
</script>
<template><div><BrandMark class="welcome-mark"/><p class="eyebrow">为思考留一点空间</p><h1>今天想完成什么？</h1><p class="welcome-description">提出问题，探索思路，让每一步都有迹可循。</p><div class="suggestions"><button @click="emit('example', '帮我梳理一个 Java 接口的设计思路')"><span>梳理设计思路</span><small>把复杂问题，拆成清晰的步骤</small><ArrowUpRight :size="18"/></button><button @click="emit('example', '解释构造注入和字段注入的区别')"><span>理解一个概念</span><small>从原理出发，联系具体场景</small><ArrowUpRight :size="18"/></button><button v-if="ragEnabled" @click="emit('example', '根据知识库解释项目的模块边界')"><span>从知识库找答案</span><small>结合文档，核对答案的依据</small><ArrowUpRight :size="18"/></button></div></div></template>
