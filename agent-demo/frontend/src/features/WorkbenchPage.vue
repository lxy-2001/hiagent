<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, Menu, PanelLeftClose, PanelRight, Settings2, CircleHelp, LogOut } from '@lucide/vue';
import BrandMark from '../components/ui/BrandMark.vue';
import PreferencesDialog from './settings/PreferencesDialog.vue';
import HelpDialog from './settings/HelpDialog.vue';
import SessionSidebar from './workspace/SessionSidebar.vue';
import WelcomePanel from './workspace/WelcomePanel.vue';
import Composer from './workspace/Composer.vue';
import MessageList from './workspace/MessageList.vue';
import RunInspector from './runs/RunInspector.vue';
import ApprovalCard from './approvals/ApprovalCard.vue';
import { approvalsApi } from '../api/approvals';
import { runsApi } from '../api/runs';
import { auth } from '../state/auth';
import { workspace as ws } from '../state/workspace';
import { useMedia, useFocusScope } from '../composables/useFocusScope';
import { useActiveRun } from '../composables/useActiveRun';
import { getUiContext } from '../api/ui-context';
import { terminal, type UiContext, type RunSnapshot, type Step, type Approval } from '../domain/contracts';
const route = useRoute(), router = useRouter();
const sidebar = ref(false), inspector = ref(false), dialog = ref<'preferences' | 'help' | null>(null), context = ref<UiContext | null>(null), contextError = ref(false);
const approvals = ref<Approval[]>([]), approvalError = ref(''), inspectorTab = ref<'execution' | 'sources' | 'memory'>('execution'), selectedSource = ref<string>();
const narrow = useMedia('(max-width: 899px)'), overlayInspector = useMedia('(max-width: 1279px)');
const sidebarElement = ref<HTMLElement>(), collapsed = ref(false);
useFocusScope(sidebarElement, computed(() => narrow.value && sidebar.value), () => { sidebar.value = false; });
const controller = new AbortController(); let selection = 0, inspection = 0;
const inspectedSnapshot = ref<RunSnapshot | null>(null), inspectedSteps = ref<Step[]>([]);
const detailsSnapshot = computed(() => inspectedSnapshot.value ?? run.snapshot.value);
const run = useActiveRun(async () => { await ws.refreshHistory(); await ws.loadSessions(); }, syncApprovals);
const active = computed(() => !!ws.session.value?.activeTaskId || !!run.snapshot.value && !terminal(run.snapshot.value.status));
const isNew = computed(() => route.path === '/new');
watch(() => route.params.sessionId, async value => {
  const expected = ++selection; approvals.value = []; approvalError.value = ''; selectedSource.value = undefined; inspection++; inspectedSnapshot.value = null; inspectedSteps.value = []; run.stop(true); run.snapshot.value = null;
  await ws.select(typeof value === 'string' ? value : null);
  if (expected !== selection) return;
  const id = ws.session.value?.activeTaskId ?? (typeof route.query.run === 'string' ? route.query.run : null);
  if (id) void run.open(id);
}, { immediate: true });
onMounted(async () => { void ws.loadSessions(); try { context.value = await getUiContext(controller.signal); } catch { if (!controller.signal.aborted) contextError.value = true; } });
onBeforeUnmount(() => { selection++; controller.abort(); run.stop(); });
async function logout() { const confirmed = await auth.logout(); await router.replace({ path: '/login', query: confirmed ? {} : { logout: 'unconfirmed' } }); }
function example(value: string) { ws.draft.value.text = value; document.querySelector<HTMLTextAreaElement>('#task-input')?.focus(); }
async function send() {
  const result = await ws.submit(); if (!result) return;
  if (route.params.sessionId !== result.sessionId) await router.push({ path: '/sessions/' + encodeURIComponent(result.sessionId), query: { run: result.runId } });
  else { void run.open(result.taskId); await ws.refreshHistory(); }
}
async function syncApprovals(snapshot: RunSnapshot) {
  const expected = selection;
  try { const values = await approvalsApi.list(snapshot.taskId, controller.signal); if (expected === selection) { approvals.value = values; approvalError.value = ''; } }
  catch { if (expected === selection) approvalError.value = '暂时无法读取审批状态，请恢复观察后核对。'; }
}
async function decide(approval: Approval, decision: 'APPROVE' | 'REJECT') {
  const expected = selection;
  try { const value = await approvalsApi.decide(approval.taskId, approval.approvalId, decision); if (expected === selection) approvals.value = approvals.value.map(a => a.approvalId === value.approvalId ? value : a); }
  catch { const value = await approvalsApi.get(approval.taskId, approval.approvalId); if (expected === selection) approvals.value = approvals.value.map(a => a.approvalId === value.approvalId ? value : a); }
}
async function source(runId: string, sourceId: string) { await inspect(runId); inspectorTab.value = 'sources'; selectedSource.value = sourceId; }
function focusMain() { document.getElementById('main-content')?.focus(); }
async function inspect(id: string) { inspector.value = true; selectedSource.value = undefined; const expected = ++inspection; inspectedSnapshot.value = null; inspectedSteps.value = []; if (run.snapshot.value?.runId === id) return; try { const [snapshot, steps] = await Promise.all([runsApi.snapshot(id, controller.signal), runsApi.steps(id, controller.signal)]); if (inspection === expected) { inspectedSnapshot.value = snapshot; inspectedSteps.value = steps; } } catch { if (inspection === expected) ws.error.value = '暂时无法读取这轮任务的详情。'; } }
</script>
<template>
  <div class="workbench"><a class="skip-link" href="#main-content" @click.prevent="focusMain">跳到主要内容</a>
    <button v-if="sidebar" class="sidebar-backdrop" aria-label="关闭会话导航" @click="sidebar = false"/>
    <aside ref="sidebarElement" class="sidebar" :class="{ open: sidebar, collapsed: collapsed && !narrow }" :inert="(narrow && !sidebar) || (overlayInspector && inspector)" aria-label="会话导航"><div class="sidebar-heading"><RouterLink to="/new" class="brand"><BrandMark/><span>HiAgent<span class="brand-dot">.</span></span></RouterLink><button class="icon-button sidebar-close" aria-label="关闭会话导航" @click="sidebar = false"><PanelLeftClose :size="18"/></button></div>
      <RouterLink to="/new" class="new-task" @click="sidebar = false"><Plus :size="18"/> 新任务 <span>↗</span></RouterLink>
      <SessionSidebar :items="ws.sessions.value" :selected="ws.key.value" :loading="ws.listing.value" :more="!!ws.nextSessions.value" :error="ws.listError.value" @select="sidebar = false" @more="ws.loadSessions(true)" @retry="ws.loadSessions()"/>
      <div class="sidebar-footer"><div class="mode-label"><span class="status-dot"/>{{ context?.mode === 'OFFLINE_FIXTURE' ? '离线演示' : context ? '模型已配置' : contextError ? '配置摘要暂不可用' : '正在读取配置' }}</div><p class="model-label">{{ context?.modelLabel ?? 'HiAgent 工作空间' }}</p><div class="account-row"><span class="avatar">{{ auth.user.value?.username.slice(0, 1).toUpperCase() }}</span><span class="account-name">{{ auth.user.value?.username }}</span><button class="icon-button" aria-label="外观偏好" @click="dialog = 'preferences'"><Settings2 :size="17"/></button><button class="icon-button" aria-label="使用帮助" @click="dialog = 'help'"><CircleHelp :size="17"/></button><button class="icon-button" aria-label="退出登录" @click="logout"><LogOut :size="17"/></button></div></div>
    </aside>
    <div class="workspace" :inert="(narrow && sidebar) || (overlayInspector && inspector)"><header class="topbar"><button class="icon-button mobile-menu" aria-label="打开会话导航" :aria-expanded="sidebar" @click="sidebar = true"><Menu :size="20"/></button><button class="icon-button desktop-toggle" :aria-label="collapsed ? '展开会话导航' : '折叠会话导航'" @click="collapsed = !collapsed"><PanelLeftClose :size="18"/></button><span class="topbar-title">工作台 <span class="topbar-slash">/</span> <strong>{{ ws.session.value?.title ?? '新任务' }}</strong></span><button v-if="!isNew" class="icon-button" aria-label="打开任务详情" :aria-expanded="inspector" @click="inspector = !inspector"><PanelRight :size="19"/></button></header>
      <main id="main-content" class="conversation-main" tabindex="-1"><p v-if="ws.error.value || run.error.value" class="error-box" role="alert">{{ ws.error.value || run.error.value }}<button v-if="run.taskId.value" class="text-button" @click="run.open(run.taskId.value)">恢复任务观察</button></p>
        <div v-if="isNew" class="welcome"><div class="welcome-content"><WelcomePanel :rag-enabled="context?.ragEnabled ?? false" @example="example"/><Composer v-model:text="ws.draft.value.text" v-model:evidence="ws.draft.value.evidence" :evidence-enabled="context?.ragEnabled ?? false" :busy="ws.draft.value.status === 'submitting'" :active="active" :uncertain="ws.draft.value.status === 'uncertain'" @submit="send" @cancel="run.cancel" @confirm="ws.confirmResubmit"/></div></div>
        <template v-else><p v-if="ws.loading.value" class="loading-state" role="status">正在读取会话…</p><MessageList v-else :turns="ws.turns.value" :snapshot="run.snapshot.value" :older="ws.hasOlder.value" :loading="ws.loadingOlder.value" :load-older="ws.older" @inspect="inspect" @source="source"><template #approvals><p v-if="approvalError" class="error-box" role="alert">{{ approvalError }}</p><ApprovalCard v-for="approval in approvals" :key="approval.approvalId" :approval="approval" :decide="decision => decide(approval, decision)"/></template></MessageList><div class="bottom-composer"><Composer v-model:text="ws.draft.value.text" v-model:evidence="ws.draft.value.evidence" :evidence-enabled="context?.ragEnabled ?? false" :busy="ws.loading.value || ws.draft.value.status === 'submitting'" :active="active" :uncertain="ws.draft.value.status === 'uncertain'" @submit="send" @cancel="run.cancel" @confirm="ws.confirmResubmit"/></div></template>
      </main>
    </div><button v-if="inspector" class="inspector-backdrop" aria-label="关闭任务详情" @click="inspector = false"/><RunInspector v-if="inspector" :session-id="ws.session.value?.sessionId ?? null" :tab="inspectorTab" :selected-source="selectedSource" @tab="inspectorTab = $event" :snapshot="detailsSnapshot" :steps="inspectedSnapshot ? inspectedSteps : run.steps.value" :summaries="inspectedSnapshot ? [] : run.summaries.value" :state="inspectedSnapshot ? 'closed' : run.state.value" @close="inspector = false" @pause="run.stop" @resume="run.taskId.value && run.open(run.taskId.value)"/>
    <PreferencesDialog v-if="dialog === 'preferences'" @close="dialog = null"/><HelpDialog v-if="dialog === 'help'" @close="dialog = null"/>
  </div>
</template>
