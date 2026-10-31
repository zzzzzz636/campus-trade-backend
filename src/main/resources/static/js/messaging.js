import { $, dateTime, el, toast } from './core.js';
import { api } from './api.js';

let currentConversation = null;
let nextBeforeId = null;
let hooks = { afterChange: async () => { } };

export function configureMessaging(options) { hooks = { ...hooks, ...options }; }

export async function loadConversations() {
  const conversations = await api('/api/conversations?page=1&size=50');
  renderConversations(conversations);
  return conversations;
}

function renderConversations(conversations) {
  const host = $('messages-pane');
  host.replaceChildren();
  $('message-count').textContent = conversations.reduce((sum, item) => sum + Number(item.unreadCount || 0), 0);
  if (!conversations.length) {
    const empty = el('div', '', 'empty-state');
    empty.append(el('div', '💬', 'empty-icon'), el('strong', '还没有私信'), el('span', '可从商品详情或订单详情联系对方。'));
    host.append(empty);
    return;
  }
  for (const conversation of conversations) {
    const row = el('article', '', 'list-row conversation-row');
    row.tabIndex = 0; row.setAttribute('role', 'button');
    const avatar = el('div', (conversation.otherNickname || '同').slice(0, 1), 'avatar message-avatar');
    const body = el('div', '', 'list-body');
    body.append(el('h3', conversation.otherNickname || `用户 #${conversation.otherUserId}`),
      el('p', `${conversation.contextType === 'ORDER' ? '订单' : '商品'} · ${conversation.contextTitle || `#${conversation.contextId}`}`),
      el('p', conversation.lastMessage || '尚未发送消息', 'message-preview'));
    const side = el('div', '', 'list-side');
    if (conversation.lastMessageAt) side.append(el('time', dateTime(conversation.lastMessageAt), 'conversation-time'));
    if (conversation.unreadCount) side.append(el('span', String(conversation.unreadCount), 'unread-badge'));
    row.append(avatar, body, side);
    const open = () => openChat(conversation);
    row.addEventListener('click', open);
    row.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') open(); });
    host.append(row);
  }
}

export async function openConversation(contextType, contextId) {
  const conversation = await api('/api/conversations', {
    method: 'POST', body: JSON.stringify({ contextType, contextId })
  });
  await openChat(conversation);
}

async function openChat(conversation) {
  currentConversation = conversation;
  $('chat-title').textContent = conversation.otherNickname || '站内私信';
  $('chat-context').textContent = `${conversation.contextType === 'ORDER' ? '订单' : '商品'}：${conversation.contextTitle || `#${conversation.contextId}`}`;
  $('chat-messages').replaceChildren(el('p', '正在加载消息…', 'subtle chat-loading'));
  $('chat-overlay').hidden = false;
  const page = await api(`/api/conversations/${conversation.id}/messages?size=30`);
  renderMessagePage(page, false);
  await api(`/api/conversations/${conversation.id}/read`, { method: 'POST' });
  await loadConversations();
}

function renderMessagePage(page, prepend) {
  const host = $('chat-messages');
  if (!prepend) host.replaceChildren();
  if (!page.items.length && !prepend) host.append(el('p', '还没有消息，发送第一条问候吧。', 'subtle chat-empty'));
  const fragment = document.createDocumentFragment();
  for (const message of page.items) {
    const bubble = el('article', '', `message-bubble ${message.mine ? 'mine' : 'theirs'}`);
    bubble.append(el('p', message.content), el('time', dateTime(message.sentAt)));
    fragment.append(bubble);
  }
  if (prepend) host.prepend(fragment); else host.append(fragment);
  nextBeforeId = page.nextBeforeId;
  $('load-older-messages').hidden = !page.hasMore;
  if (!prepend) host.scrollTop = host.scrollHeight;
}

export function closeChat() {
  $('chat-overlay').hidden = true;
  currentConversation = null;
  nextBeforeId = null;
}

export function bindMessagingEvents() {
  $('close-chat').addEventListener('click', closeChat);
  $('chat-backdrop').addEventListener('click', closeChat);
  $('load-older-messages').addEventListener('click', async () => {
    if (!currentConversation || !nextBeforeId) return;
    const button = $('load-older-messages'); button.disabled = true;
    try {
      const page = await api(`/api/conversations/${currentConversation.id}/messages?size=30&beforeId=${nextBeforeId}`);
      renderMessagePage(page, true);
    } catch (error) { toast(`历史消息加载失败：${error.message}`, true); }
    finally { button.disabled = false; }
  });
  $('message-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!currentConversation) return;
    const form = event.currentTarget;
    const content = form.elements.content.value.trim();
    if (!content) return;
    const submit = form.querySelector('button[type="submit"]'); submit.disabled = true;
    try {
      const message = await api(`/api/conversations/${currentConversation.id}/messages`, {
        method: 'POST', body: JSON.stringify({ content })
      });
      const host = $('chat-messages');
      host.querySelector('.chat-empty')?.remove();
      const bubble = el('article', '', 'message-bubble mine');
      bubble.append(el('p', message.content), el('time', dateTime(message.sentAt)));
      host.append(bubble); host.scrollTop = host.scrollHeight; form.reset();
      await Promise.all([loadConversations(), hooks.afterChange()]);
    } catch (error) { toast(`消息发送失败：${error.message}`, true); }
    finally { submit.disabled = false; }
  });
}
