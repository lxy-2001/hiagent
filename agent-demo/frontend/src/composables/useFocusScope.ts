import { ref, watch, nextTick, onBeforeUnmount, type Ref } from 'vue';
export function useMedia(query: string) {
  const media = typeof matchMedia === 'function' ? matchMedia(query) : null;
  const matches = ref(media?.matches ?? false);
  const change = () => { matches.value = media?.matches ?? false; };
  media?.addEventListener('change', change); onBeforeUnmount(() => media?.removeEventListener('change', change)); return matches;
}
export function useFocusScope(root: Ref<HTMLElement | undefined>, active: Ref<boolean>, close: () => void) {
  let previous: HTMLElement | null = null;
  function controls() { return [...(root.value?.querySelectorAll<HTMLElement>('a[href],button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]') ?? [])].filter(e => e.getClientRects().length > 0 && !e.closest('[inert]')); }
  function keydown(event: KeyboardEvent) {
    if (!active.value || document.querySelector('dialog[open]')) return;
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (event.key === 'Tab') { const list = controls(), first = list[0], last = list.at(-1); if (!first) return;
      if (event.shiftKey && (document.activeElement === first || !root.value?.contains(document.activeElement))) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || !root.value?.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
    }
  }
  watch(active, async enabled => { if (enabled) { previous = document.activeElement as HTMLElement; await nextTick(); if (active.value) controls()[0]?.focus(); document.addEventListener('keydown', keydown); }
    else { document.removeEventListener('keydown', keydown); await nextTick(); previous?.focus(); previous = null; } }, { immediate: true, flush: 'post' });
  onBeforeUnmount(() => { document.removeEventListener('keydown', keydown); previous?.focus(); });
}
