import { nextTick, type Ref } from 'vue';
export function useScrollAnchor(element: Ref<HTMLElement | undefined>) {
  async function prepend(load: () => Promise<void>) {
    const target = element.value; if (!target) return load();
    const height = target.scrollHeight, top = target.scrollTop;
    await load(); await nextTick(); target.scrollTop = top + target.scrollHeight - height;
  }
  async function preserveBottom(update: () => Promise<void>) {
    const target = element.value, nearBottom = !!target && target.scrollHeight - target.clientHeight - target.scrollTop < 80;
    await update(); await nextTick(); if (target && nearBottom) target.scrollTop = target.scrollHeight;
  }
  return { prepend, preserveBottom };
}
