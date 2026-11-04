import { $, dateTime, el, emptyPhoto, money, state, statusName, toast } from './core.js';
import { api } from './api.js';
import { emptyProfile, statusStyle } from './orders-v5.js';

let hooks = { openDetail: async () => { }, refreshProducts: async () => { } };

export function configureFavorites(options) { hooks = { ...hooks, ...options }; }

export async function loadFavoriteIds() {
  state.favoriteIds = new Set((await api('/api/favorites/product-ids')).map(Number));
  return state.favoriteIds;
}

export function favoriteButton(productId, compact = false) {
  const active = state.favoriteIds.has(Number(productId));
  const button = el('button', active ? '♥' : '♡', compact ? 'favorite-button compact' : 'outline-btn favorite-detail-button');
  button.type = 'button';
  button.title = active ? '取消收藏' : '收藏商品';
  if (!compact) button.textContent = active ? '♥ 已收藏' : '♡ 收藏商品';
  button.classList.toggle('active', active);
  button.addEventListener('click', async event => {
    event.stopPropagation(); button.disabled = true;
    try {
      await toggleFavorite(productId);
      const now = state.favoriteIds.has(Number(productId));
      button.classList.toggle('active', now);
      button.textContent = compact ? (now ? '♥' : '♡') : (now ? '♥ 已收藏' : '♡ 收藏商品');
      button.title = now ? '取消收藏' : '收藏商品';
    } catch (error) { toast(`收藏操作失败：${error.message}`, true); }
    finally { button.disabled = false; }
  });
  return button;
}

export async function toggleFavorite(productId) {
  const id = Number(productId);
  const active = state.favoriteIds.has(id);
  await api(`/api/products/${id}/favorite`, { method: active ? 'DELETE' : 'POST' });
  if (active) state.favoriteIds.delete(id); else state.favoriteIds.add(id);
  toast(active ? '已取消收藏' : '已加入收藏');
}

export async function loadFavorites() {
  const products = await api('/api/favorites?page=1&size=100');
  $('favorite-count').textContent = products.length;
  const host = $('favorites-pane'); host.replaceChildren();
  if (!products.length) {
    host.append(emptyProfile('♡', '还没有收藏商品', '点击商品卡片上的爱心，稍后再来看。'));
    return products;
  }
  for (const product of products) {
    const row = el('article', '', 'list-row favorite-row');
    const mini = el('div', '', 'mini-cover');
    if (product.coverImageUrl) {
      const image = document.createElement('img'); image.src = product.coverImageUrl; image.alt = product.title;
      image.addEventListener('error', () => mini.replaceChildren(emptyPhoto()), { once: true });
      mini.append(image);
    } else mini.append(emptyPhoto());
    const body = el('div', '', 'list-body');
    body.append(el('h3', product.title), el('p', `${product.categoryName} · 发布者 ${product.sellerNickname}`),
      el('p', `收藏于 ${dateTime(product.favoritedAt)}`));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(product.price)), el('span', statusName[product.status] || product.status, statusStyle(product.status)));
    const view = el('button', '查看商品', 'row-action'); view.type = 'button'; view.addEventListener('click', () => hooks.openDetail(product.id));
    const remove = el('button', '取消收藏', 'row-action favorite-remove'); remove.type = 'button';
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try { await toggleFavorite(product.id); await Promise.all([loadFavorites(), hooks.refreshProducts()]); }
      catch (error) { toast(`取消收藏失败：${error.message}`, true); remove.disabled = false; }
    });
    side.append(view, remove); row.append(mini, body, side); host.append(row);
  }
  return products;
}
