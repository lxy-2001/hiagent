import { describe, it, expect, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import LoginPage from '../../src/features/auth/LoginPage.vue';
import { auth } from '../../src/state/auth';
import { ApiError } from '../../src/api/response';
const router = () => createRouter({ history: createMemoryHistory(), routes: [{ path: '/login', component: LoginPage }, { path: '/new', component: { template: '<div>新任务</div>' } }] });
afterEach(() => auth.reset());
describe('login form', () => {
  it('keeps labels visible, toggles password and displays only safe errors', async () => {
    const login = vi.spyOn(auth, 'login').mockRejectedValue(new ApiError('UNAUTHORIZED'));
    const wrapper = mount(LoginPage, { global: { plugins: [router()] } });
    await wrapper.get('input[name=username]').setValue('demo');
    await wrapper.get('input[name=password]').setValue('password');
    await wrapper.get('[aria-label="显示密码"]').trigger('click');
    expect(wrapper.get('input[name=password]').attributes('type')).toBe('text');
    await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(login).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[role=alert]').text()).toContain('账号或密码');
  });
  it('prevents duplicate login while waiting', async () => {
    let done!: () => void;
    vi.spyOn(auth, 'login').mockImplementation(() => new Promise(resolve => { done = resolve; }));
    const wrapper = mount(LoginPage, { global: { plugins: [router()] } });
    await wrapper.get('form').trigger('submit');
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined();
    done(); await flushPromises();
  });
});
