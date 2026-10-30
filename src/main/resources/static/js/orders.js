import { $, el, money, statusName, toast } from './core.js';
import { api } from './api.js';

export function emptyProfile(icon, title, detail) {
  const node = el('div', '', 'empty-state');
  node.append(el('div', icon, 'empty-icon'), el('strong', title), el('span', detail));
  return node;
}

export function statusStyle(status) {
  if (status === 'PENDING_MEETUP' || status === 'RESERVED') return 'status reserved';
  if (status === 'CANCELLED' || status === 'OFF_SHELF') return 'status down';
  return 'status';
}

export function renderPurchases(orders, refresh) {
  $('purchase-count').textContent = orders.length;
  renderOrders(orders, $('purchases-pane'), true, refresh);
}

export function renderSales(orders, refresh) {
  $('sales-count').textContent = orders.length;
  renderOrders(orders, $('sales-pane'), false, refresh);
}

function renderOrders(orders, host, buying, refresh) {
  host.replaceChildren();
  if (!orders.length) {
    host.append(buying
      ? emptyProfile('🧾', '还没有购买订单', '逛逛商品广场，发现心仪的物品。')
      : emptyProfile('📦', '还没有售出订单', '有同学选购你的商品后，订单会显示在这里。'));
    return;
  }
  for (const order of orders) {
    const row = el('article', '', 'list-row');
    row.append(el('div', buying ? '🧾' : '📦', 'mini-cover'));
    const body = el('div', '', 'list-body');
    body.append(el('h3', order.productTitle || `商品 #${order.productId}`),
      el('p', `订单 #${order.id} · 商品 #${order.productId}`));
    if (!buying && order.status === 'PENDING_MEETUP') {
      body.append(el('p', '等待买家线下面交后确认成交。'));
    }
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(order.price)),
      el('span', statusName[order.status] || order.status, statusStyle(order.status)));
    if (buying && order.status === 'PENDING_MEETUP') {
      const buttons = el('div', '', 'order-actions');
      for (const [action, label] of [['cancel', '取消订单'], ['complete', '确认成交']]) {
        const button = el('button', label, action === 'cancel' ? 'order-cancel' : 'order-complete');
        button.type = 'button';
        button.addEventListener('click', async () => {
          if (action === 'complete' && !window.confirm('请确认已经线下面交并收到商品。确认后不可撤销。')) return;
          for (const child of buttons.querySelectorAll('button')) child.disabled = true;
          try {
            await api(`/api/orders/${order.id}/${action}`, { method: 'POST' });
            await refresh();
            toast(action === 'cancel' ? '订单已取消，商品重新上架' : '交易已完成，商品标记为已售出');
          } catch (error) {
            toast(`操作失败：${error.message}`, true);
            for (const child of buttons.querySelectorAll('button')) child.disabled = false;
          }
        });
        buttons.append(button);
      }
      side.append(buttons);
    }
    row.append(body, side);
    host.append(row);
  }
}
