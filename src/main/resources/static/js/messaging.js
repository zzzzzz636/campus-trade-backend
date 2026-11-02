import { $, dateTime, el, toast } from './core.js';
import { api } from './api.js';

const POLL_INTERVAL_MS = 2500;

let currentConversation = null;
let nextBeforeId = null;
let latestMessageId = null;
let pollTimer = null;
let pollInFlight = false;
let pollGeneration = 0;
let isComposing = false;
let sending = false;
let lastFocusedElement = null;
let renderedMessageIds = new Set();
let hooks = {
  afterChange: async () => { },
  openProductDetail: async () => { },
  openOrderDetail: async () => { }
};

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
    row.tabIndex = 0;
    row.setAttribute('role', 'button');
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
    row.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        open();
      }
    });
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
  stopPolling();
  currentConversation = conversation;
  nextBeforeId = null;
  latestMessageId = null;
  renderedMessageIds = new Set();
  lastFocusedElement = document.activeElement;
  const kind = conversation.contextType === 'ORDER' ? '订单' : '商品';
  $('chat-title').textContent = conversation.otherNickname || '站内私信';
  $('chat-context-kind').textContent = kind;
  $('chat-context-title').textContent = conversation.contextTitle || `${kind} #${conversation.contextId}`;
  $('chat-context').dataset.contextType = conversation.contextType;
  $('chat-context').dataset.contextId = conversation.contextId;
  $('load-older-messages').hidden = true;
  $('chat-messages').replaceChildren(el('p', '正在加载消息…', 'subtle chat-loading'));
  setPollStatus('正在连接会话…');
  $('chat-overlay').hidden = false;

  try {
    const page = await api(`/api/conversations/${conversation.id}/messages?size=30`);
    if (currentConversation?.id !== conversation.id) return;
    renderMessagePage(page, 'initial');
    $('message-content').focus();
    setPollStatus('新消息将自动显示');
    startPolling();
  } catch (error) {
    if (currentConversation?.id !== conversation.id) return;
    renderLoadError(error);
    setPollStatus('会话加载失败，可点击重试', true);
    return;
  }
  try {
    await refreshReadAndConversationList(conversation.id);
  } catch (error) {
    setPollStatus(`消息已加载，会话列表刷新失败：${error.message}`, true);
  }
}

function renderLoadError(error) {
  const box = el('div', '', 'chat-error');
  box.append(el('strong', '消息暂时无法加载'), el('span', error.message));
  const retry = el('button', '重新加载');
  retry.type = 'button';
  retry.addEventListener('click', () => {
    if (currentConversation) openChat(currentConversation);
  });
  box.append(retry);
  $('chat-messages').replaceChildren(box);
}

function messageBubble(message) {
  const bubble = el('article', '', `message-bubble ${message.mine ? 'mine' : 'theirs'}`);
  bubble.dataset.messageId = message.id;
  bubble.append(el('p', message.content), el('time', dateTime(message.sentAt)));
  return bubble;
}

function renderMessagePage(page, mode) {
  const host = $('chat-messages');
  const prepending = mode === 'prepend';
  const oldHeight = prepending ? host.scrollHeight : 0;
  const oldTop = prepending ? host.scrollTop : 0;
  const hostTop = prepending ? host.getBoundingClientRect().top : 0;
  const readingAnchor = prepending
    ? [...host.querySelectorAll('.message-bubble')]
      .find(node => node.getBoundingClientRect().bottom > hostTop + 1)
    : null;
  const readingAnchorTop = readingAnchor?.getBoundingClientRect().top;
  const wasNearBottom = host.scrollHeight - host.scrollTop - host.clientHeight < 80;
  if (mode === 'initial') host.replaceChildren();
  host.querySelector('.chat-empty')?.remove();

  const fragment = document.createDocumentFragment();
  for (const message of page.items) {
    const numericId = Number(message.id);
    latestMessageId = latestMessageId == null ? numericId : Math.max(latestMessageId, numericId);
    if (renderedMessageIds.has(numericId)) continue;
    renderedMessageIds.add(numericId);
    fragment.append(messageBubble(message));
  }
  if (prepending) host.prepend(fragment); else host.append(fragment);

  if (!host.querySelector('.message-bubble') && mode === 'initial') {
    host.append(el('p', '还没有消息，发送第一条问候吧。', 'subtle chat-empty'));
  }
  if (mode !== 'append') {
    nextBeforeId = page.nextBeforeId;
    $('load-older-messages').hidden = !page.hasMore;
  }
  if (prepending) {
    if (readingAnchor && readingAnchorTop != null) {
      host.scrollTop += readingAnchor.getBoundingClientRect().top - readingAnchorTop;
    } else {
      host.scrollTop = oldTop + (host.scrollHeight - oldHeight);
    }
  } else if (mode === 'initial' || wasNearBottom) {
    host.scrollTop = host.scrollHeight;
  }
}

function setPollStatus(text, error = false) {
  const status = $('chat-poll-status');
  status.textContent = text;
  status.classList.toggle('error', error);
}

function startPolling() {
  stopPolling();
  const generation = pollGeneration;
  pollTimer = window.setInterval(() => pollNewMessages(generation), POLL_INTERVAL_MS);
}

function stopPolling() {
  pollGeneration += 1;
  if (pollTimer != null) window.clearInterval(pollTimer);
  pollTimer = null;
  pollInFlight = false;
}

async function pollNewMessages(generation) {
  if (generation !== pollGeneration || pollInFlight || !currentConversation ||
      $('chat-overlay').hidden || document.hidden) return;
  pollInFlight = true;
  const conversationId = currentConversation.id;
  const after = latestMessageId == null ? '' : `&afterId=${latestMessageId}`;
  try {
    const page = await api(`/api/conversations/${conversationId}/messages?size=100${after}`);
    if (generation !== pollGeneration || currentConversation?.id !== conversationId) return;
    const newMessages = page.items.filter(message => !renderedMessageIds.has(Number(message.id)));
    if (newMessages.length) {
      renderMessagePage({ ...page, items: newMessages }, 'append');
      if (newMessages.some(message => !message.mine)) {
        await refreshReadAndConversationList(conversationId);
      }
    }
    setPollStatus('新消息将自动显示');
    if (page.hasMore) window.setTimeout(() => pollNewMessages(generation), 0);
  } catch (error) {
    if (generation === pollGeneration) setPollStatus(`自动获取失败，将继续重试：${error.message}`, true);
  } finally {
    if (generation === pollGeneration) pollInFlight = false;
  }
}

async function refreshReadAndConversationList(conversationId) {
  const results = await Promise.allSettled([
    api(`/api/conversations/${conversationId}/read`, { method: 'POST' }),
    loadConversations(),
    hooks.afterChange()
  ]);
  const failed = results.find(result => result.status === 'rejected');
  if (failed) throw failed.reason;
}

export function closeChat() {
  if ($('chat-overlay').hidden) return;
  stopPolling();
  $('chat-overlay').hidden = true;
  currentConversation = null;
  nextBeforeId = null;
  latestMessageId = null;
  renderedMessageIds.clear();
  setSendStatus('');
  if (lastFocusedElement?.isConnected) lastFocusedElement.focus();
  lastFocusedElement = null;
}

function setSendStatus(text, error = false) {
  const status = $('message-send-status');
  status.textContent = text;
  status.classList.toggle('error', error);
}

async function submitMessage(form) {
  if (!currentConversation || sending) return;
  const content = form.elements.content.value.trim();
  if (!content) {
    setSendStatus('请输入消息内容', true);
    form.elements.content.focus();
    return;
  }
  const conversationId = currentConversation.id;
  const submit = $('send-message');
  sending = true;
  submit.disabled = true;
  submit.textContent = '发送中…';
  form.setAttribute('aria-busy', 'true');
  setSendStatus('正在发送');

  let message;
  try {
    message = await api(`/api/conversations/${conversationId}/messages`, {
      method: 'POST', body: JSON.stringify({ content })
    });
  } catch (error) {
    setSendStatus(`发送失败：${error.message}`, true);
    toast(`消息发送失败：${error.message}`, true);
    form.elements.content.focus();
    return;
  } finally {
    sending = false;
    submit.disabled = false;
    submit.textContent = '发送';
    form.removeAttribute('aria-busy');
  }

  if (currentConversation?.id !== conversationId) return;
  renderMessagePage({ items: [message], hasMore: false, nextBeforeId: null }, 'append');
  form.reset();
  setSendStatus('已发送');
  form.elements.content.focus();
  try {
    await Promise.all([loadConversations(), hooks.afterChange()]);
  } catch (error) {
    setSendStatus('消息已发送，会话列表稍后自动刷新', true);
    toast(`消息已发送，但会话列表刷新失败：${error.message}`, true);
  }
}

export function bindMessagingEvents() {
  $('close-chat').addEventListener('click', closeChat);
  $('chat-backdrop').addEventListener('click', closeChat);
  $('chat-context').addEventListener('click', async () => {
    if (!currentConversation) return;
    const { contextType, contextId } = currentConversation;
    closeChat();
    try {
      if (contextType === 'ORDER') await hooks.openOrderDetail(Number(contextId));
      else await hooks.openProductDetail(Number(contextId));
    } catch (error) {
      toast(`关联详情打开失败：${error.message}`, true);
    }
  });
  $('load-older-messages').addEventListener('click', async () => {
    if (!currentConversation || !nextBeforeId) return;
    const conversationId = currentConversation.id;
    const button = $('load-older-messages');
    button.disabled = true;
    button.textContent = '加载中…';
    try {
      const page = await api(`/api/conversations/${conversationId}/messages?size=30&beforeId=${nextBeforeId}`);
      if (currentConversation?.id === conversationId) renderMessagePage(page, 'prepend');
    } catch (error) {
      toast(`历史消息加载失败：${error.message}`, true);
    } finally {
      button.disabled = false;
      button.textContent = '加载更早消息';
    }
  });
  const textarea = $('message-content');
  textarea.addEventListener('compositionstart', () => { isComposing = true; });
  textarea.addEventListener('compositionend', () => { isComposing = false; });
  textarea.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !isComposing && event.keyCode !== 229) {
      event.preventDefault();
      $('message-form').requestSubmit();
    }
  });
  textarea.addEventListener('input', () => setSendStatus(''));
  $('message-form').addEventListener('submit', event => {
    event.preventDefault();
    submitMessage(event.currentTarget);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !$('chat-overlay').hidden) {
      event.preventDefault();
      closeChat();
    }
  });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && currentConversation && !$('chat-overlay').hidden) {
      pollNewMessages(pollGeneration);
    }
  });
}
