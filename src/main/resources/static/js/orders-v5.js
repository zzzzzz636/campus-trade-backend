import { $, dateTime, el, emptyPhoto, money, statusName, toast } from './core.js';
import { api } from './api.js';

let purchaseProduct = null;
let hooks = { refresh: async () => { }, openConversation: async () => { } };

export function configureOrders(options) { hooks = { ...hooks, ...options }; }

export function emptyProfile(icon, title, detail) {
  const node = el('div', '', 'empty-state');
  node.append(emptyPhoto(), el('strong', title), el('span', detail));
  return node;
}

export function statusStyle(status) {
  if (['PENDING_SELLER_CONFIRMATION', 'WAITING_HANDOFF', 'WAITING_SHIPMENT', 'SHIPPED', 'RESERVED'].includes(status)) return 'status reserved';
  if (['CANCELLED', 'REJECTED', 'EXPIRED', 'OFF_SHELF'].includes(status)) return 'status down';
  return 'status';
}

export function renderPurchases(orders) {
  $('purchase-count').textContent = orders.length;
  renderOrders(orders, $('purchases-pane'), true);
}

export function renderSales(orders) {
  $('sales-count').textContent = orders.length;
  renderOrders(orders, $('sales-pane'), false);
}

function renderOrders(orders, host, buying) {
  host.replaceChildren();
  if (!orders.length) {
    host.append(buying
      ? emptyProfile('🧾', '还没有购买订单', '在商品详情中提交购买申请后，进度会显示在这里。')
      : emptyProfile('📦', '还没有售出订单', '收到购买申请后，可在这里接受或拒绝。'));
    return;
  }
  for (const order of orders) {
    const row = el('article', '', 'list-row order-row');
    row.tabIndex = 0;
    row.setAttribute('role', 'button');
    const mini = el('div', '', 'mini-cover');
    if (order.productCoverImageUrl) {
      const image = document.createElement('img');
      image.src = order.productCoverImageUrl;
      image.alt = order.productTitle;
      image.addEventListener('error', () => mini.replaceChildren(emptyPhoto()), { once: true });
      mini.append(image);
    } else mini.append(emptyPhoto());
    const body = el('div', '', 'list-body');
    body.append(el('h3', order.productTitle || `商品 #${order.productId}`),
      el('p', `订单 #${order.id} · ${order.fulfillmentType === 'SHIPPING' ? '快递邮寄' : '校园面交'}`),
      el('p', buying ? `卖家：${order.sellerNickname || `用户 #${order.sellerId}`}` : `买家：${order.buyerNickname || `用户 #${order.buyerId}`}`));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(order.price)),
      el('span', statusName[order.status] || order.status, statusStyle(order.status)),
      el('button', '查看订单详情 →', 'row-action'));
    row.append(mini, body, side);
    const open = () => openOrderDetail(order.id);
    row.addEventListener('click', open);
    row.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') open(); });
    host.append(row);
  }
}

export function openPurchaseDialog(product) {
  purchaseProduct = product;
  const form = $('purchase-form');
  form.reset();
  $('purchase-product').textContent = `${product.title} · ${money(product.price)} · 发布者 ${product.sellerNickname || '校园同学'}`;
  toggleFulfillmentFields();
  $('purchase-dialog').showModal();
}

function toggleFulfillmentFields() {
  const shipping = $('purchase-fulfillment').value === 'SHIPPING';
  $('handoff-field').hidden = shipping;
  $('shipping-fields').hidden = !shipping;
  for (const name of ['recipientName', 'recipientPhone', 'shippingAddress']) {
    $('purchase-form').elements[name].required = shipping;
  }
}

export function closeOrderDetail() { $('order-overlay').hidden = true; }

export async function openOrderDetail(orderId) {
  try {
    const [order, cancellation] = await Promise.all([
      api(`/api/orders/${orderId}`), api(`/api/orders/${orderId}/cancel-request`)
    ]);
    renderOrderDetail(order, cancellation);
    $('order-overlay').hidden = false;
    $('close-order-detail').focus();
  } catch (error) { toast(`订单详情加载失败：${error.message}`, true); }
}

function renderOrderDetail(order, cancellation) {
  const host = $('order-detail-content');
  host.replaceChildren();
  const header = el('div', '', 'order-detail-header');
  const titleWrap = el('div');
  titleWrap.append(el('span', order.viewerRole === 'BUYER' ? '我的购买订单' : '我的售出订单', 'eyebrow'),
    el('h2', `订单 #${order.id}`), el('p', `创建于 ${dateTime(order.createdAt)}`, 'subtle'));
  header.append(titleWrap, el('span', statusName[order.status] || order.status, statusStyle(order.status)));
  const guidance = {
    PENDING_SELLER_CONFIRMATION: order.viewerRole === 'BUYER' ? '申请已提交，等待卖家确认。' : '买家已提交申请，请选择接受或拒绝。',
    WAITING_HANDOFF: '双方可在私信中约定面交时间与地点。',
    WAITING_SHIPMENT: '卖家接受申请后，请确认物流安排。',
    SHIPPED: '商品已发出，买家收到并检查后确认完成。',
    COMPLETED: '这笔交易已完成。', CANCELLED: '订单已取消，商品恢复可售。',
    REJECTED: '卖家已拒绝申请，商品恢复可售。', EXPIRED: '申请已超时，商品恢复可售。'
  };
  host.append(header, progress(order), el('p', guidance[order.status] || '', 'order-state-hint'));

  const product = el('section', '', 'order-product-card');
  const cover = el('div', '', 'mini-cover order-cover');
  if (order.productCoverImageUrl) {
    const image = document.createElement('img'); image.src = order.productCoverImageUrl; image.alt = order.productTitle;
    image.addEventListener('error', () => cover.replaceChildren(emptyPhoto()), { once: true });
    cover.append(image);
  } else cover.append(emptyPhoto());
  const info = el('div', '', 'list-body');
  info.append(el('h3', order.productTitle), el('p', order.productDescription || '暂无商品描述'), el('strong', money(order.price), 'order-price'));
  product.append(cover, info);
  host.append(product);

  const facts = el('section', '', 'order-facts');
  facts.append(fact('交易方式', order.fulfillmentType === 'SHIPPING' ? '快递邮寄' : '校园面交'),
    fact('买家', order.buyerNickname || `用户 #${order.buyerId}`), fact('卖家', order.sellerNickname || `用户 #${order.sellerId}`));
  if (order.buyerNote) facts.append(fact('买家备注', order.buyerNote));
  if (order.handoffLocation) facts.append(fact('期望地点', order.handoffLocation));
  if (order.recipientName) facts.append(fact('收件信息', `${order.recipientName} · ${order.recipientPhone}\n${order.shippingAddress}`));
  if (order.shippingCarrier) facts.append(fact('物流信息', `${order.shippingCarrier} · ${order.trackingNumber}`));
  if (order.sellerDecisionNote) facts.append(fact('卖家备注', order.sellerDecisionNote));
  if (cancellation) facts.append(fact('协商取消待处理', cancellation.reason));
  host.append(facts);

  const actions = el('section', '', 'order-detail-actions');
  actions.append(el('h3', '交易操作'));
  actions.append(actionButton(order.viewerRole === 'BUYER' ? '联系卖家' : '联系买家', 'outline-btn',
    () => hooks.openConversation('ORDER', order.id)));
  addStateActions(actions, order, cancellation);
  host.append(actions, timeline(order.events || []));
}

function progress(order) {
  const node = el('div', '', 'order-progress');
  const steps = order.fulfillmentType === 'SHIPPING'
    ? [['PENDING_SELLER_CONFIRMATION', '提交申请'], ['WAITING_SHIPMENT', '卖家接受'], ['SHIPPED', '已发货'], ['COMPLETED', '已完成']]
    : [['PENDING_SELLER_CONFIRMATION', '提交申请'], ['WAITING_HANDOFF', '等待面交'], ['COMPLETED', '已完成']];
  const current = steps.findIndex(([status]) => status === order.status);
  steps.forEach(([, label], index) => {
    const step = el('div', '', `progress-step ${(current >= index || order.status === 'COMPLETED') ? 'done' : ''}`);
    step.append(el('span', String(index + 1)), el('small', label)); node.append(step);
  });
  if (['REJECTED', 'CANCELLED', 'EXPIRED'].includes(order.status)) node.classList.add('terminated');
  return node;
}

function fact(label, value) {
  const node = el('div', '', 'order-fact'); node.append(el('span', label), el('strong', value)); return node;
}

function timeline(events) {
  const section = el('section', '', 'order-timeline'); section.append(el('h3', '订单操作记录'));
  for (const event of events) {
    const item = el('div', '', 'timeline-item');
    item.append(el('span', ''), el('strong', eventLabel(event.eventType)), el('time', dateTime(event.createdAt)), event.note ? el('p', event.note) : el('p', ''));
    section.append(item);
  }
  return section;
}

function eventLabel(type) {
  return ({ BUYER_APPLIED: '买家提交购买申请', SELLER_ACCEPTED: '卖家接受申请', SELLER_REJECTED: '卖家拒绝申请',
    BUYER_CANCELLED: '买家取消申请', SELLER_SHIPPED: '卖家确认发货', BUYER_COMPLETED: '买家确认收货',
    APPLICATION_EXPIRED: '购买申请超时', V5_MIGRATED: '历史订单迁移到 V5',
    CANCELLATION_REQUESTED: '发起协商取消', CANCELLATION_ACCEPTED: '同意协商取消',
    CANCELLATION_REJECTED: '拒绝协商取消' })[type] || type;
}

function addStateActions(host, order, cancellation) {
  if (['WAITING_HANDOFF', 'WAITING_SHIPMENT'].includes(order.status)) {
    const myId = order.viewerRole === 'BUYER' ? order.buyerId : order.sellerId;
    if (cancellation) {
      if (cancellation.requesterId === myId) host.append(el('p', '已提出协商取消，等待对方处理。', 'subtle'));
      else host.append(actionButton('同意取消并恢复商品在售', 'outline-btn danger-outline',
        () => transition(order.id, 'cancel-request/accept', '双方已同意取消，商品恢复在售')),
      actionButton('拒绝取消，继续交易', 'outline-btn',
        () => transition(order.id, 'cancel-request/reject', '已拒绝协商取消')));
    } else host.append(actionButton('申请协商取消', 'outline-btn', async () => {
      const reason = window.prompt('请填写取消原因，等待对方同意后才会生效');
      if (reason === null) return;
      if (!reason.trim()) throw new Error('请填写取消原因');
      if (reason.length > 300) throw new Error('取消原因最多 300 字');
      await transition(order.id, 'cancel-request', '协商取消请求已发出', { reason: reason.trim() });
    }));
  }
  if (order.viewerRole === 'SELLER' && order.status === 'PENDING_SELLER_CONFIRMATION') {
    host.append(actionButton('接受购买申请', 'primary', () => decision(order.id, 'accept')),
      actionButton('拒绝并恢复商品在售', 'outline-btn danger-outline', () => decision(order.id, 'reject')));
  }
  if (order.viewerRole === 'BUYER' && order.status === 'PENDING_SELLER_CONFIRMATION') {
    host.append(actionButton('取消购买申请', 'outline-btn danger-outline', () => transition(order.id, 'cancel', '购买申请已取消')));
  }
  if (order.viewerRole === 'SELLER' && order.status === 'WAITING_SHIPMENT' && !cancellation) {
    const form = el('form', '', 'shipping-form');
    const carrier = document.createElement('input'); carrier.placeholder = '快递公司'; carrier.maxLength = 80; carrier.required = true;
    const tracking = document.createElement('input'); tracking.placeholder = '运单号'; tracking.maxLength = 100; tracking.required = true;
    const submit = el('button', '确认已发货', 'primary'); submit.type = 'submit';
    form.append(carrier, tracking, submit);
    form.addEventListener('submit', async event => {
      event.preventDefault();
      try { await transition(order.id, 'ship', '发货信息已保存', { carrier: carrier.value, trackingNumber: tracking.value }); }
      catch (error) { toast(`发货失败：${error.message}`, true); }
    });
    host.append(form);
  }
  if (order.viewerRole === 'BUYER' && ['WAITING_HANDOFF', 'SHIPPED'].includes(order.status) && !cancellation) {
    host.append(actionButton(order.status === 'SHIPPED' ? '确认收货并完成交易' : '确认已面交并完成交易', 'primary', async () => {
      if (!window.confirm('请确认你已经收到并检查商品。完成后不可撤销。')) return;
      await transition(order.id, 'complete', '交易已完成');
    }));
  }
}

function actionButton(label, className, handler) {
  const button = el('button', label, className); button.type = 'button';
  button.addEventListener('click', async event => {
    event.stopPropagation(); button.disabled = true;
    try { await handler(); } catch (error) { toast(`操作失败：${error.message}`, true); } finally { button.disabled = false; }
  });
  return button;
}

async function decision(orderId, action) {
  const wording = action === 'accept' ? '接受这条购买申请？' : '拒绝后商品会恢复在售，确认拒绝？';
  if (!window.confirm(wording)) return;
  const note = window.prompt('可填写给对方的处理备注（可留空）', '');
  if (note === null) return;
  await transition(orderId, action, action === 'accept' ? '已接受购买申请' : '已拒绝申请，商品恢复在售', { note });
}

async function transition(orderId, action, success, body = null) {
  await api(`/api/orders/${orderId}/${action}`, { method: 'POST', body: body ? JSON.stringify(body) : undefined });
  toast(success);
  const results = await Promise.allSettled([openOrderDetail(orderId), hooks.refresh()]);
  if (results.some(result => result.status === 'rejected')) toast('操作已成功，但列表刷新失败；请手动刷新页面。', true);
}

export function bindOrderEvents() {
  $('purchase-fulfillment').addEventListener('change', toggleFulfillmentFields);
  $('close-purchase').addEventListener('click', () => $('purchase-dialog').close());
  $('purchase-form').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget; const submit = form.querySelector('button[type="submit"]'); const data = new FormData(form);
    const shipping = data.get('fulfillmentType') === 'SHIPPING'; submit.disabled = true;
    try {
      const order = await api('/api/orders', { method: 'POST', body: JSON.stringify({
        productId: purchaseProduct.id, requestKey: crypto.randomUUID(), fulfillmentType: data.get('fulfillmentType'),
        buyerNote: data.get('buyerNote') || null, handoffLocation: shipping ? null : data.get('handoffLocation') || null,
        recipientName: shipping ? data.get('recipientName') : null, recipientPhone: shipping ? data.get('recipientPhone') : null,
        shippingAddress: shipping ? data.get('shippingAddress') : null
      }) });
      $('purchase-dialog').close(); await hooks.refresh(); toast(`购买申请 #${order.id} 已提交，等待卖家确认。`); await openOrderDetail(order.id);
    } catch (error) { toast(`购买申请未提交：${error.message}`, true); } finally { submit.disabled = false; }
  });
  $('order-backdrop').addEventListener('click', closeOrderDetail);
  $('close-order-detail').addEventListener('click', closeOrderDetail);
}
