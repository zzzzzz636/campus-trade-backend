import { $, el, emptyPhoto, money, state } from './core.js';
import { api } from './api.js';

let hooks = { openProduct: () => {}, useDraft: () => {} };
let pending = false;

function storageKey() { return `campusTradeAgentSession:${state.user?.id}`; }

function close() { $('ai-agent-dialog').close(); }

function addMessage(text, sender, response) {
  const item = el('div', '', `agent-message ${sender}`);
  item.append(el('p', text));
  if (response?.products?.length) {
    const list = el('div', '', 'agent-products');
    response.products.forEach(product => {
      const card = el('button', '', 'agent-product');
      card.type = 'button';
      const photo = product.imageUrl ? document.createElement('img') : emptyPhoto();
      if (product.imageUrl) { photo.src = product.imageUrl; photo.alt = ''; photo.loading = 'lazy'; }
      card.append(photo, el('span', `${product.title} · ${money(product.price)}`));
      card.addEventListener('click', () => { close(); hooks.openProduct(product.id); });
      list.append(card);
    });
    item.append(list);
  }
  if (response?.orders?.length) {
    const list = el('ul', '', 'agent-orders');
    response.orders.forEach(order => list.append(el('li', `订单 #${order.id} · ${order.productTitle} · ${order.status}`)));
    item.append(list);
  }
  if (response?.draft) {
    const button = el('button', '把草稿填入发布表单', 'outline-btn');
    button.type = 'button';
    button.addEventListener('click', () => { close(); hooks.useDraft(response.draft); });
    item.append(button);
  }
  $('ai-agent-messages').append(item);
  item.scrollIntoView({ block: 'end' });
  return item;
}

export function bindAgent(options) {
  hooks = options;
  $('open-ai-agent').addEventListener('click', () => {
    if (state.isPreview) return;
    $('ai-agent-dialog').showModal();
    $('ai-agent-input').focus();
  });
  $('close-ai-agent').addEventListener('click', close);
  $('ai-agent-dialog').addEventListener('click', event => {
    if (event.target === $('ai-agent-dialog')) close();
  });
  $('ai-agent-input').addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    $('ai-agent-form').requestSubmit();
  });
  $('ai-agent-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (pending) return;
    const input = $('ai-agent-input');
    const message = input.value.trim();
    if (!message) return;
    pending = true;
    $('ai-agent-send').disabled = true;
    $('ai-agent-status').textContent = '正在查询…';
    const pendingBubble = addMessage(message, 'user');
    input.value = '';
    try {
      const sessionId = sessionStorage.getItem(storageKey());
      const result = await api('/api/ai/agent/chat', {
        method: 'POST', body: JSON.stringify({ message, sessionId })
      });
      sessionStorage.setItem(storageKey(), result.sessionId);
      addMessage(result.reply, 'assistant', result);
      $('ai-agent-status').textContent = '';
    } catch (error) {
      pendingBubble.remove();
      if (error.status === 409) sessionStorage.removeItem(storageKey());
      $('ai-agent-status').textContent = `请求失败：${error.message}`;
      input.value = message;
      if (!state.token && $('ai-agent-dialog').open) close();
    } finally {
      pending = false;
      $('ai-agent-send').disabled = false;
    }
  });
}
