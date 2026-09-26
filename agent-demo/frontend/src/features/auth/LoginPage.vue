<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Eye, EyeOff, ArrowRight, Layers, ShieldCheck, Compass } from '@lucide/vue';
import { auth } from '../../state/auth';
import { safeReturn } from '../../router';
import { ApiError } from '../../api/response';
import BrandMark from '../../components/ui/BrandMark.vue';
const route = useRoute(), router = useRouter();
const username = ref(''), password = ref(''), visible = ref(false), busy = ref(false), error = ref('');
const usernameInput = ref<HTMLInputElement>();
onMounted(() => usernameInput.value?.focus());
async function submit() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try { await auth.login(username.value.trim(), password.value); password.value = ''; await router.replace(safeReturn(route.query.return)); }
  catch (e) { error.value = e instanceof ApiError && e.code === 'UNAUTHORIZED' ? '账号或密码不正确，请核对后重试。' : '暂时无法登录，请检查服务是否启动后重试。'; password.value = ''; }
  finally { busy.value = false; }
}
</script>
<template>
  <main class="login-layout">
    <section class="login-story" aria-label="关于 HiAgent">
      <a class="brand" href="#/login"><BrandMark /> <span>HiAgent<span class="brand-dot">.</span></span></a>
      <div class="story-copy"><p class="eyebrow">思考 · 行动 · 有据可查</p><h1>让每一个想法，<br>走向清晰的结果。</h1><p>与你一起拆解问题、使用工具、核对来源。<br>把关注留给问题，把过程交给 HiAgent。</p>
        <div class="story-features"><span><Layers :size="19"/> 看见执行过程</span><span><ShieldCheck :size="19"/> 由你确认关键操作</span><span><Compass :size="19"/> 回到答案的来源</span></div>
      </div>
      <div class="story-footer">一个专注于任务的 Agent 工作台 <span>HiAgent / 01</span></div>
    </section>
    <section class="login-form-area"><div class="login-form-card">
      <BrandMark class="mobile-brand"/><p class="eyebrow">你的专属工作空间</p><h2>欢迎回来</h2><p class="muted login-intro">登录后，继续你的思考与探索。</p>
      <p v-if="route.query.logout === 'unconfirmed'" role="status" class="error-box">本页登录已清除，但未能确认服务端退出。请稍后重新登录核对。</p>
      <form @submit.prevent="submit" :aria-busy="busy">
        <label for="username">账号</label><input ref="usernameInput" id="username" name="username" v-model="username" autocomplete="username" required maxlength="128" placeholder="输入你的账号" :disabled="busy"/>
        <label for="password">密码</label><div class="password-field"><input id="password" name="password" v-model="password" :type="visible ? 'text' : 'password'" autocomplete="current-password" required :disabled="busy"/><button type="button" class="icon-button" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" @click="visible = !visible"><EyeOff v-if="visible" :size="18"/><Eye v-else :size="18"/></button></div>
        <p v-if="error" role="alert" class="error-box">{{ error }}</p><button type="submit" class="primary login-submit" :disabled="busy">{{ busy ? '正在登录…' : '进入工作台' }}<ArrowRight :size="18"/></button>
      </form>
      <p class="login-note"><ShieldCheck :size="16"/> 登录凭据仅保存在当前页面，刷新后需要重新登录。</p><p class="account-help">账号由本机服务管理员配置。没有账号时，请检查初始化登录配置。</p>
    </div><footer class="login-bottom">HiAgent · 让过程透明，让结果可信</footer></section>
  </main>
</template>
