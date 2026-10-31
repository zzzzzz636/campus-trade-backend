import { $, conditionName, dateTime, el, money, statusName, toast } from './core.js';
import { api } from './api.js';
import { emptyProfile, renderPurchases, renderSales, statusStyle } from './orders-v5.js';
import { loadConversations } from './messaging.js';
import { loadFavorites } from './favorites.js';

let hooks = {
  navigate: () => { }, openDetail: async () => { }, startEdit: () => { },
  takeDown: async () => { }, loadProducts: async () => { }, iconFor: () => '🛍️',
  displayCategory: () => '校园好物'
};

export function configureProfile(options) {
  hooks = { ...hooks, ...options };
}

export async function loadProfile() {
  const [purchases, sales, products] = await Promise.all([
    api('/api/orders/purchases'), api('/api/orders/sales'), api('/api/products/me'), loadConversations(), loadFavorites()
  ]);
  renderPurchases(purchases);
  renderSales(sales);
  renderListings(products);
}

export function profileTab(name) {
  for (const tab of ['purchases', 'sales', 'listings', 'messages', 'favorites']) {
    $(`${tab}-pane`).hidden = tab !== name;
    $(`tab-${tab}`).classList.toggle('active', tab === name);
  }
}

export function bindProfileEvents() {
  $('nav-profile').addEventListener('click', () => hooks.navigate('profile'));
  $('tab-purchases').addEventListener('click', () => profileTab('purchases'));
  $('tab-sales').addEventListener('click', () => profileTab('sales'));
  $('tab-listings').addEventListener('click', () => profileTab('listings'));
  $('tab-messages').addEventListener('click', () => profileTab('messages'));
  $('tab-favorites').addEventListener('click', () => profileTab('favorites'));
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
    const row = el('article', '', 'list-row');
    const mini = el('div', '', 'mini-cover');
    if (product.coverImageUrl) {
      const image = document.createElement('img');
      image.src = product.coverImageUrl;
      image.alt = product.title;
      mini.append(image);
    } else mini.textContent = hooks.iconFor(product);
    row.append(mini);
    const body = el('div', '', 'list-body');
    body.append(el('h3', product.title),
      el('p', `商品 #${product.id} · ${hooks.displayCategory(product)} · ${conditionName[product.condition] || product.condition}`));
    if (product.createdAt) body.append(el('p', `发布于 ${dateTime(product.createdAt)}`));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(product.price)),
      el('span', statusName[product.status] || product.status, statusStyle(product.status)));
    const manage = el('button', product.status === 'ON_SALE' ? '查看 / 管理图片' : '查看商品详情', 'row-action');
    manage.type = 'button';
    manage.addEventListener('click', () => hooks.openDetail(product.id));
    side.append(manage);
    if (product.status === 'ON_SALE') addSellerActions(side, product);
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
