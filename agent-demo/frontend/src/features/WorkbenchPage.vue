<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Menu, PanelLeftClose, Settings2, CircleHelp, LogOut, ArrowUpRight, ArrowUp, Layers } from '@lucide/vue';
import BrandMark from '../components/ui/BrandMark.vue';
import PreferencesDialog from './settings/PreferencesDialog.vue';
import HelpDialog from './settings/HelpDialog.vue';
import { auth } from '../state/auth';
import { getUiContext } from '../api/ui-context';
import type { UiContext } from '../domain/contracts';
const router = useRouter();
const sidebar = ref(false), dialog = ref<'preferences' | 'help' | null>(null), context = ref<UiContext | null>(null), contextError = ref(false);
const draft = ref(''); const input = ref<HTMLTextAreaElement>(); const controller = new AbortController();
onMounted(async () => { try { context.value = await getUiContext(controller.signal); } catch { if (!controller.signal.aborted) contextError.value = true; } });
onBeforeUnmount(() => controller.abort());
async function logout() { const confirmed = await auth.logout(); await router.replace({ path: '/login', query: confirmed ? {} : { logout: 'unconfirmed' } }); }
function example(value: string) { draft.value = value; input.value?.focus(); }
</script>
<template>
  <div class="workbench"><a class="skip-link" href="#main-content">跳到主要内容</a>
    <button v-if="sidebar" class="sidebar-backdrop" aria-label="关闭会话导航" @click="sidebar = false"/>
    <aside class="sidebar" :class="{ open: sidebar }" aria-label="会话导航"><div class="sidebar-heading"><RouterLink to="/new" class="brand"><BrandMark/><span>HiAgent<span class="brand-dot">.</span></span></RouterLink><button class="icon-button sidebar-close" aria-label="关闭会话导航" @click="sidebar = false"><PanelLeftClose :size="18"/></button></div>
      <RouterLink to="/new" class="new-task" @click="sidebar = false"><Plus :size="18"/> 新任务 <span>↗</span></RouterLink>
      <div class="sidebar-history"><p class="eyebrow">你的会话</p><p class="muted small">从右侧开始一次新的探索。</p></div>
      <div class="sidebar-footer"><div class="mode-label"><span class="status-dot"/>{{ context?.mode === 'OFFLINE_FIXTURE' ? '离线演示' : context ? '模型已配置' : contextError ? '配置摘要暂不可用' : '正在读取配置' }}</div><p class="model-label">{{ context?.modelLabel ?? 'HiAgent 工作空间' }}</p><div class="account-row"><span class="avatar">{{ auth.user.value?.username.slice(0, 1).toUpperCase() }}</span><span class="account-name">{{ auth.user.value?.username }}</span><button class="icon-button" aria-label="外观偏好" @click="dialog = 'preferences'"><Settings2 :size="17"/></button><button class="icon-button" aria-label="使用帮助" @click="dialog = 'help'"><CircleHelp :size="17"/></button><button class="icon-button" aria-label="退出登录" @click="logout"><LogOut :size="17"/></button></div></div>
    </aside>
    <div class="workspace"><header class="topbar"><button class="icon-button mobile-menu" aria-label="打开会话导航" :aria-expanded="sidebar" @click="sidebar = true"><Menu :size="20"/></button><span>工作台 <span class="topbar-slash">/</span> <strong>新任务</strong></span><span class="topbar-note"><Layers :size="15"/> 专注于你的下一步</span></header>
      <main id="main-content" class="welcome" tabindex="-1"><div class="welcome-content"><BrandMark class="welcome-mark"/><p class="eyebrow">为思考留一点空间</p><h1>今天想完成什么？</h1><p class="welcome-description">提出问题，探索思路，让每一步都有迹可循。</p>
        <div class="suggestions"><button @click="example('帮我梳理一个 Java 接口的设计思路')"><span>梳理设计思路</span><small>把复杂问题，拆成清晰的步骤</small><ArrowUpRight :size="18"/></button><button @click="example('解释构造注入和字段注入的区别')"><span>理解一个概念</span><small>从原理出发，联系具体场景</small><ArrowUpRight :size="18"/></button><button v-if="context?.ragEnabled" @click="example('根据知识库解释项目的模块边界')"><span>从知识库找答案</span><small>结合文档，核对答案的依据</small><ArrowUpRight :size="18"/></button></div>
        <div class="composer"><label class="sr-only" for="task-input">任务内容</label><textarea ref="input" id="task-input" v-model="draft" rows="3" maxlength="8000" placeholder="描述你想完成的事…"/><div class="composer-bottom"><span class="muted small">对话功能正在接入</span><button class="send-button" aria-label="发送任务" disabled><ArrowUp :size="20"/></button></div></div><p class="welcome-note">AI 的回答可能有误，请核对关键信息与引用来源。</p>
      </div></main>
    </div><PreferencesDialog v-if="dialog === 'preferences'" @close="dialog = null"/><HelpDialog v-if="dialog === 'help'" @close="dialog = null"/>
  </div>
</template>
