import { $, conditionName, dateTime, el, emptyPhoto, money, statusName, state, toast } from './core.js';
import { logout } from './auth.js';
import { api } from './api.js';
import { emptyProfile, renderPurchases, renderSales, statusStyle } from './orders-v5.js';
import { loadConversations } from './messaging.js';
import { loadFavorites } from './favorites.js';
import { loadNotifications, refreshNotificationCount } from './notifications.js';

let hooks = {
  navigate: () => { }, openDetail: async () => { }, startEdit: () => { },
  takeDown: async () => { }, loadProducts: async () => { },
  displayCategory: () => '校园好物'
};
const orderPages = { purchases: 1, sales: 1 };
const orderPageSize = 20;
const orderStatuses = [
  'PENDING_SELLER_CONFIRMATION', 'WAITING_HANDOFF', 'WAITING_SHIPMENT', 'SHIPPED',
  'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'
];

export function configureProfile(options) {
  hooks = { ...hooks, ...options };
}

export async function loadProfile() {
  const [purchases, sales, products] = await Promise.all([
    loadOrderPage('purchases'), loadOrderPage('sales'), api('/api/products/me'), loadConversations(), loadFavorites(), refreshNotificationCount()
  ]);
  showOrderPage('purchases', purchases);
  showOrderPage('sales', sales);
  renderListings(products);
}

async function loadOrderPage(kind) {
  const params = new URLSearchParams({ page: orderPages[kind], size: orderPageSize });
  const status = $(`${kind}-status`).value;
  if (status) params.set('status', status);
  return api(`/api/orders/${kind}/page?${params}`);
}

function showOrderPage(kind, result) {
  if (kind === 'purchases') renderPurchases(result.items);
  else renderSales(result.items);
  $(`${kind === 'purchases' ? 'purchase' : 'sales'}-count`).textContent = result.total;
  $(`${kind}-page-label`).textContent = `第 ${result.page} 页 · 共 ${result.total} 条`;
  $(`${kind}-prev`).disabled = result.page <= 1;
  $(`${kind}-next`).disabled = !result.hasMore;
}

async function changeOrderPage(kind, delta) {
  orderPages[kind] = Math.max(1, orderPages[kind] + delta);
  try { showOrderPage(kind, await loadOrderPage(kind)); }
  catch (error) { orderPages[kind] = Math.max(1, orderPages[kind] - delta); toast(`订单加载失败：${error.message}`, true); }
}

export function profileTab(name) {
  const headings = {
    purchases: ['我买到的', '查看购买申请及交易进度。'],
    sales: ['我卖出的', '处理同学的购买申请，跟进交易。'],
    listings: ['我发布的', '查看商品状态，编辑在售商品或管理照片。'],
    notifications: ['通知', '查看订单与交易的最新动态。'],
    messages: ['私信', '与买家或卖家沟通商品和订单。'],
    favorites: ['我的收藏', '保存感兴趣的好物，方便以后查看。'],
    account: ['账号设置', '更新个人资料与登录密码。']
  };
  $('profile-pane-title').textContent = headings[name][0];
  $('profile-pane-hint').textContent = headings[name][1];
  for (const tab of ['purchases', 'sales', 'listings', 'notifications', 'messages', 'favorites', 'account']) {
    $(`${tab}-pane`).hidden = tab !== name;
    if (tab === 'purchases' || tab === 'sales') $(`${tab}-pager`).hidden = tab !== name;
    if (tab === 'notifications') {
      $('notifications-pager').hidden = tab !== name;
      if (tab === name) loadNotifications().catch(error => toast(`通知加载失败：${error.message}`, true));
    }
    $(`tab-${tab}`).classList.toggle('active', tab === name);
  }
}

export function bindProfileEvents() {
  for (const kind of ['purchases', 'sales']) {
    for (const status of orderStatuses) {
      const option = el('option', statusName[status] || status);
      option.value = status;
      $(`${kind}-status`).append(option);
    }
    $(`${kind}-status`).addEventListener('change', async () => {
      orderPages[kind] = 1;
      try { showOrderPage(kind, await loadOrderPage(kind)); }
      catch (error) { toast(`订单筛选失败：${error.message}`, true); }
    });
    $(`${kind}-prev`).addEventListener('click', () => changeOrderPage(kind, -1));
    $(`${kind}-next`).addEventListener('click', () => changeOrderPage(kind, 1));
  }
  $('nav-profile').addEventListener('click', () => hooks.navigate('profile'));
  $('tab-purchases').addEventListener('click', () => profileTab('purchases'));
  $('tab-sales').addEventListener('click', () => profileTab('sales'));
  $('tab-listings').addEventListener('click', () => profileTab('listings'));
  $('tab-notifications').addEventListener('click', () => profileTab('notifications'));
  $('tab-messages').addEventListener('click', () => profileTab('messages'));
  $('tab-favorites').addEventListener('click', () => profileTab('favorites'));
  $('tab-account').addEventListener('click', () => {
    $('nickname-form').elements.nickname.value = state.user?.nickname || '';
    profileTab('account');
  });
  $('nickname-form').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget; const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    try {
      state.user = await api('/api/auth/me', { method: 'PUT', body: JSON.stringify({ nickname: form.elements.nickname.value }) });
      $('profile-name').textContent = state.user.nickname;
      $('profile-avatar').textContent = $('header-avatar').textContent = state.user.nickname.trim().slice(0, 1);
      toast('昵称已更新');
    } catch (error) { toast(`修改昵称失败：${error.message}`, true); }
    finally { button.disabled = false; }
  });
  $('password-form').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget; const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    try {
      await api('/api/auth/me/password', { method: 'POST', body: JSON.stringify({
        currentPassword: form.elements.currentPassword.value, newPassword: form.elements.newPassword.value
      }) });
      form.reset(); logout(false); toast('密码已修改，请重新登录');
    } catch (error) { toast(`修改密码失败：${error.message}`, true); }
    finally { button.disabled = false; }
  });
}

function renderListings(products) {
  $('listing-count').textContent = products.length;
  const host = $('listings-pane');
  host.replaceChildren();
  if (!products.length) {
    host.append(emptyProfile('📦', '还没有发布商品', '点击顶部“发布闲置”，分享你的第一件好物。'));
    return;
  }
  for (const product of products) {
    const row = el('article', '', 'list-row listing-row');
    const mini = el('div', '', 'mini-cover');
    if (product.coverImageUrl) {
      const image = document.createElement('img');
      image.src = product.coverImageUrl;
      image.alt = product.title;
      mini.append(image);
    } else mini.append(emptyPhoto());
    row.append(mini);
    const body = el('div', '', 'list-body');
    body.append(el('h3', product.title),
      el('p', `商品 #${product.id} · ${hooks.displayCategory(product)} · ${conditionName[product.condition] || product.condition}`));
    if (product.createdAt) body.append(el('p', `发布于 ${dateTime(product.createdAt)}`));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(product.price)),
      el('span', statusName[product.status] || product.status, statusStyle(product.status)));
    const actions = el('div', '', 'listing-actions');
    const manage = el('button', product.status === 'ON_SALE' ? '查看 / 管理图片' : '查看商品详情', 'row-action');
    manage.type = 'button';
    manage.addEventListener('click', () => hooks.openDetail(product.id));
    actions.append(manage);
    if (product.status === 'ON_SALE') addSellerActions(actions, product);
    side.append(actions);
    row.append(body, side);
    host.append(row);
  }
}

function addSellerActions(side, product) {
  const edit = el('button', '编辑商品', 'row-action');
  edit.type = 'button';
  edit.addEventListener('click', async () => {
    edit.disabled = true;
    try {
      const detail = await api(`/api/products/${product.id}`);
      hooks.startEdit(detail);
    } catch (error) {
      toast(`加载商品失败：${error.message}`, true);
      edit.disabled = false;
    }
  });
  side.append(edit);
  const down = el('button', '下架商品', 'row-action');
  down.type = 'button';
  down.addEventListener('click', async () => {
    down.disabled = true;
    try { await hooks.takeDown(product.id); }
    catch (error) {
      toast(`下架失败：${error.message}`, true);
      down.disabled = false;
    }
  });
  side.append(down);
}
