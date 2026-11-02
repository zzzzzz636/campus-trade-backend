import { $, dateTime, el, state, toast } from './core.js';
import { api } from './api.js';

let page = 1;
let openOrder = async () => { };

export function configureNotifications(options) { openOrder = options.openOrder; }

export async function refreshNotificationCount() {
  if (!state.token) return;
  const result = await api('/api/notifications/unread-count');
  $('notification-count').textContent = result.count;
}

export async function loadNotifications() {
  const result = await api(`/api/notifications?page=${page}&size=20`);
  const host = $('notifications-pane');
  host.replaceChildren();
  if (!result.items.length) host.append(el('div', '暂无通知', 'empty-state'));
  for (const item of result.items) {
    const row = el('button', '', `notification-row ${item.readAt ? '' : 'unread'}`);
    row.type = 'button';
    row.append(el('strong', item.title), el('small', dateTime(item.createdAt)),
      el('span', '查看订单详情 →'));
    row.addEventListener('click', async () => {
      try {
        await api(`/api/notifications/${item.id}/read`, { method: 'POST' });
        row.classList.remove('unread');
        await refreshNotificationCount();
        await openOrder(item.referenceId);
      } catch (error) { toast(`通知操作失败：${error.message}`, true); }
    });
    host.append(row);
  }
  $('notifications-page-label').textContent = `第 ${result.page} 页 · 共 ${result.total} 条`;
  $('notifications-prev').disabled = page <= 1;
  $('notifications-next').disabled = !result.hasMore;
}

export function bindNotificationEvents() {
  for (const [id, delta] of [['notifications-prev', -1], ['notifications-next', 1]]) {
    $(id).addEventListener('click', async () => {
      page = Math.max(1, page + delta);
      try { await loadNotifications(); }
      catch (error) { page = Math.max(1, page - delta); toast(`通知加载失败：${error.message}`, true); }
    });
  }
  window.setInterval(() => {
    if (!state.token || document.hidden) return;
    refreshNotificationCount().catch(() => { });
    if (!$('notifications-pane').hidden) loadNotifications().catch(() => { });
  }, 10000);
}
