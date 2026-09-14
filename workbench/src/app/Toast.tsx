import { signal } from '@preact/signals';

export interface ToastMessage {
  id: number;
  text: string;
  kind: 'info' | 'error';
}

let nextId = 0;
export const toasts = signal<ToastMessage[]>([]);

export function pushToast(text: string, kind: ToastMessage['kind'] = 'info', durationMs = 4000): void {
  const id = nextId++;
  toasts.value = [...toasts.value, { id, text, kind }];
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }, durationMs);
}

export function ToastHost() {
  if (toasts.value.length === 0) return null;
  return (
    <div class="toast-host" role="status" aria-live="polite">
      {toasts.value.map((t) => (
        <div key={t.id} class={`toast toast--${t.kind}`}>
          {t.text}
        </div>
      ))}
    </div>
  );
}
