const state = { token: '', user: null, view: 'home', page: 1, query: '', total: 0, categories: new Map(), selected: null, loadId: 0, pendingImages: [] };
const $ = id => document.getElementById(id);
const money = value => `¥${Number(value).toFixed(2)}`;
const conditionName = { NEW: '全新', LIKE_NEW: '近新', GOOD: '良好', FAIR: '可用' };
const statusName = { ON_SALE: '在售', RESERVED: '已预订', SOLD: '已售出', OFF_SHELF: '已下架', PENDING_MEETUP: '待线下面交', COMPLETED: '已完成', CANCELLED: '已取消' };
let toastTimer;
let codeCountdownTimer;

function toast(message, error = false) {
  const node = $('toast');
  node.textContent = message;
  node.className = error ? 'toast error' : 'toast';
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 5000);
}

function el(tag, text, className = '') {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

async function api(path, options = {}) {
  const isForm = options.body instanceof FormData;
  const headers = { ...(options.body && !isForm ? { 'Content-Type': 'application/json' } : {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  const response = await fetch(path, { ...options, headers });
  const raw = await response.text();
  let data = null;
  if (raw) { try { data = JSON.parse(raw); } catch { data = raw; } }
  if (!response.ok) {
    if (response.status === 401 && state.token) {
      logout(false);
      throw new Error('登录已过期，请重新登录');
    }
    const detail = data && typeof data === 'object' ? (data.detail || data.message || data.title) : '';
    throw new Error(`${response.status}${detail ? `：${detail}` : ''}`);
  }
  return data;
}

function setAuthMode(mode) {
  const register = mode === 'register';
  $('login-form').hidden = register;
  $('register-form').hidden = !register;
  $('show-login').classList.toggle('active', !register);
  $('show-register').classList.toggle('active', register);
  $('auth-title').textContent = register ? '加入校园好物圈' : '欢迎回来';
  $('auth-subtitle').textContent = register ? '创建账号，开始分享和发现闲置' : '登录后浏览校园里的闲置好物';
}

function logout(showMessage = true) {
  state.token = '';
  state.user = null;
  state.selected = null;
  sessionStorage.removeItem('campusTradeToken');
  closeDetail();
  $('app-view').hidden = true;
  $('auth-view').hidden = false;
  setAuthMode('login');
  if (showMessage) toast('已退出登录');
}

async function finishLogin(token) {
  state.token = token;
  state.user = await api('/api/auth/me');
  sessionStorage.setItem('campusTradeToken', token);
  $('auth-view').hidden = true;
  $('app-view').hidden = false;
  const initial = (state.user.nickname || '同').trim().slice(0, 1);
  $('header-avatar').textContent = initial;
  $('profile-avatar').textContent = initial;
  $('profile-name').textContent = state.user.nickname;
  $('profile-email').textContent = state.user.email;
  navigate('home');
  const results = await Promise.allSettled([loadCategories(), loadProducts()]);
  for (const result of results) {
    if (result.status === 'rejected') toast(`页面数据加载失败：${result.reason.message}`, true);
  }
}

function navigate(view) {
  state.view = view;
  for (const name of ['home', 'publish', 'profile']) $(`${name}-view`).hidden = name !== view;
  $('nav-home').classList.toggle('active', view === 'home');
  $('nav-publish').classList.toggle('active', view === 'publish');
  $('nav-profile').classList.toggle('active', view === 'profile');
  closeDetail();
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (view === 'profile') loadProfile().catch(error => toast(`加载个人中心失败：${error.message}`, true));
}

async function loadCategories() {
  const categories = await api('/api/categories');
  state.categories = new Map(categories.map(category => [Number(category.id), category.name]));
  const select = $('category-select');
  select.replaceChildren();
  for (const category of categories) {
    const option = el('option', category.name);
    option.value = category.id;
    select.append(option);
  }
}

function categoryName(id) { return state.categories.get(Number(id)) || '校园好物'; }
function iconFor(item) {
  const text = `${categoryName(item.categoryId)} ${item.title}`;
  if (/书|教材|笔记|资料/.test(text)) return '📚';
  if (/灯/.test(text)) return '💡';
  if (/耳机|音箱|数码|手机|电脑|电器/.test(text)) return '🎧';
  if (/包|衣|鞋/.test(text)) return '🎒';
  return '🛍️';
}
function cover(item, index) {
  const node = el('div', '', `product-cover cover-${index % 4}`);
  if (item.coverImageUrl) {
    const image = document.createElement('img');
    image.src = item.coverImageUrl;
    image.alt = item.title || '商品照片';
    image.loading = 'lazy';
    node.append(image);
  } else {
    node.textContent = iconFor(item);
  }
  return node;
}

function sampleCards() {
  const samples = [
    { title: '闲置教材示例', categoryId: 1, sellerNickname: '示例发布者 A' },
    { title: '桌面台灯示例', categoryId: 2, sellerNickname: '示例发布者 B' },
    { title: '校园生活好物示例', categoryId: 0, sellerNickname: '示例发布者 C' }
  ];
  for (const [index, item] of samples.entries()) {
    const card = el('article', '', 'product-card sample');
    const image = cover(item, index);
    image.append(el('span', '示意卡片', 'sample-badge'));
    const body = el('div', '', 'product-body');
    body.append(el('span', categoryName(item.categoryId), 'tag'), el('h3', item.title), el('div', '等待真实商品发布', 'subtle'));
    const meta = el('div', '', 'product-meta');
    meta.append(el('span', `发布者 · ${item.sellerNickname}`));
    body.append(meta);
    card.append(image, body);
    $('products').append(card);
  }
}

function renderProducts(items) {
  const host = $('products');
  host.replaceChildren();
  if (!items.length && state.total === 0 && !state.query) {
    sampleCards();
  } else if (!items.length) {
    const empty = el('div', '', 'empty-state');
    empty.append(el('div', '🔎', 'empty-icon'), el('strong', '没有找到相关商品'), el('span', '试试其他关键词，或者清除搜索条件。'));
    host.append(empty);
  } else {
    for (const [index, item] of items.entries()) {
      const card = el('button', '', 'product-card');
      card.type = 'button';
      const body = el('div', '', 'product-body');
      body.append(el('span', categoryName(item.categoryId), 'tag'), el('h3', item.title), el('div', money(item.price), 'price'));
      const meta = el('div', '', 'product-meta');
      meta.append(el('span', `发布者 · ${item.sellerNickname || '校园同学'}`), el('span', conditionName[item.condition] || item.condition));
      body.append(meta);
      card.append(cover(item, index), body);
      card.addEventListener('click', () => openDetail(item.id));
      host.append(card);
    }
  }
  $('result-label').textContent = state.query ? `“${state.query}”的搜索结果：${state.total} 件` : `全部在售商品 · ${state.total} 件`;
  $('clear-search').hidden = !state.query;
  $('page-label').textContent = `第 ${state.page} 页`;
  $('prev-page').disabled = state.page <= 1;
  $('next-page').disabled = state.page * 12 >= state.total;
  document.querySelector('.pager').hidden = state.total <= 12;
}

async function loadProducts() {
  const id = ++state.loadId;
  const result = await api(`/api/products?page=${state.page}&size=12&q=${encodeURIComponent(state.query)}`);
  if (id !== state.loadId) return;
  if (result.total > 0 && result.items.length === 0 && state.page > 1) {
    state.page = Math.ceil(result.total / 12);
    return loadProducts();
  }
  state.total = result.total;
  renderProducts(result.items);
}

function closeDetail() { $('detail-overlay').hidden = true; state.selected = null; }

function renderDetailImages(product, own) {
  const coverHost = $('detail-cover');
  const strip = $('detail-images');
  coverHost.replaceChildren();
  strip.replaceChildren();
  const images = product.images || [];
  const showImage = image => {
    coverHost.replaceChildren();
    const main = document.createElement('img');
    main.src = image.url;
    main.alt = product.title;
    coverHost.append(main);
  };
  if (images.length) {
    showImage(images[0]);
    for (const image of images) {
      const item = el('div', '', 'detail-thumb-wrap');
      const thumb = document.createElement('button');
      thumb.type = 'button';
      thumb.className = 'detail-thumb';
      const preview = document.createElement('img');
      preview.src = image.url;
      preview.alt = image.originalName;
      thumb.append(preview);
      thumb.addEventListener('click', () => showImage(image));
      item.append(thumb);
      if (own && !['RESERVED', 'SOLD'].includes(product.status)) {
        const remove = el('button', '×', 'image-remove');
        remove.type = 'button';
        remove.title = '删除这张图片';
        remove.addEventListener('click', async event => {
          event.stopPropagation();
          remove.disabled = true;
          try {
            await api(`/api/products/${product.id}/images/${image.id}`, { method: 'DELETE' });
            toast('商品图片已删除');
            await openDetail(product.id);
            await Promise.all([loadProducts(), loadProfile()]);
          } catch (error) { toast(`图片删除失败：${error.message}`, true); remove.disabled = false; }
        });
        item.append(remove);
      }
      strip.append(item);
    }
  } else {
    coverHost.textContent = iconFor(product);
  }
  if (own && !['RESERVED', 'SOLD'].includes(product.status) && images.length < 6) {
    const add = el('label', '＋ 添加照片', 'detail-add-image');
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.accept = 'image/jpeg,image/png,image/webp';
    input.addEventListener('change', async () => {
      if (!input.files.length) return;
      const data = new FormData();
      for (const file of input.files) data.append('files', file, file.name);
      try {
        await api(`/api/products/${product.id}/images`, { method: 'POST', body: data });
        toast('商品图片已上传');
        await openDetail(product.id);
        await Promise.all([loadProducts(), loadProfile()]);
      } catch (error) { toast(`图片上传失败：${error.message}`, true); }
    });
    add.append(input);
    strip.append(add);
  }
}

async function openDetail(id) {
  try {
    const product = await api(`/api/products/${id}`);
    state.selected = product;
    const own = Number(state.user.id) === Number(product.sellerId);
    renderDetailImages(product, own);
    $('detail-category').textContent = categoryName(product.categoryId);
    $('detail-title').textContent = product.title;
    $('detail-seller').textContent = `发布者 · ${product.sellerNickname || '校园同学'}`;
    $('detail-price').textContent = money(product.price);
    $('detail-description').textContent = product.description;
    $('detail-condition').textContent = `商品成色：${conditionName[product.condition] || product.condition}`;
    const actions = $('detail-actions');
    actions.replaceChildren();
    if (product.status === 'ON_SALE') {
      const button = el('button', own ? '下架这件商品' : '选择商品，创建线下面交订单', own ? 'primary danger' : 'primary');
      button.type = 'button';
      const requestKey = crypto.randomUUID();
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          if (own) {
            await takeDown(product.id);
          } else {
            const order = await api('/api/orders', { method: 'POST', body: JSON.stringify({ productId: product.id, requestKey }) });
            closeDetail();
            await loadProducts();
            toast(`订单 #${order.id} 已创建，可在个人中心查看。`);
          }
        } catch (error) { toast(`操作失败：${error.message}`, true); }
        finally { button.disabled = false; }
      });
      actions.append(button);
    }
    $('detail-overlay').hidden = false;
    $('close-detail').focus();
  } catch (error) { toast(`查看商品失败：${error.message}`, true); await loadProducts(); }
}

async function takeDown(id) {
  await api(`/api/products/${id}`, { method: 'DELETE' });
  closeDetail();
  await loadProducts();
  if (state.view === 'profile') await loadProfile();
  toast('商品已下架');
}

function emptyProfile(icon, title, detail) {
  const node = el('div', '', 'empty-state');
  node.append(el('div', icon, 'empty-icon'), el('strong', title), el('span', detail));
  return node;
}

function statusStyle(status) {
  if (status === 'PENDING_MEETUP' || status === 'RESERVED') return 'status reserved';
  if (status === 'CANCELLED' || status === 'OFF_SHELF') return 'status down';
  return 'status';
}

function renderOrders(orders, host, buying) {
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
    body.append(el('h3', order.productTitle || `商品 #${order.productId}`), el('p', `订单 #${order.id} · 商品 #${order.productId}`));
    if (!buying && order.status === 'PENDING_MEETUP') body.append(el('p', '等待买家线下面交后确认成交。'));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(order.price)), el('span', statusName[order.status] || order.status, statusStyle(order.status)));
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
            await Promise.all([loadProfile(), loadProducts()]);
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

function renderPurchases(orders) {
  $('purchase-count').textContent = orders.length;
  renderOrders(orders, $('purchases-pane'), true);
}

function renderSales(orders) {
  $('sales-count').textContent = orders.length;
  renderOrders(orders, $('sales-pane'), false);
}

function renderListings(products) {
  $('listing-count').textContent = products.length;
  const host = $('listings-pane');
  host.replaceChildren();
  if (!products.length) { host.append(emptyProfile('📦', '还没有发布商品', '点击顶部“发布闲置”，分享你的第一件好物。')); return; }
  for (const product of products) {
    const row = el('article', '', 'list-row');
    const mini = el('div', '', 'mini-cover');
    if (product.coverImageUrl) {
      const image = document.createElement('img');
      image.src = product.coverImageUrl;
      image.alt = product.title;
      mini.append(image);
    } else mini.textContent = iconFor(product);
    row.append(mini);
    const body = el('div', '', 'list-body');
    body.append(el('h3', product.title), el('p', `商品 #${product.id} · ${categoryName(product.categoryId)} · ${conditionName[product.condition] || product.condition}`));
    const side = el('div', '', 'list-side');
    side.append(el('strong', money(product.price)), el('span', statusName[product.status] || product.status, statusStyle(product.status)));
    const manage = el('button', '查看 / 管理图片', 'row-action');
    manage.type = 'button';
    manage.addEventListener('click', () => openDetail(product.id));
    side.append(manage);
    if (product.status === 'ON_SALE') {
      const down = el('button', '下架商品', 'row-action');
      down.type = 'button';
      down.addEventListener('click', async () => {
        down.disabled = true;
        try { await takeDown(product.id); } catch (error) { toast(`下架失败：${error.message}`, true); down.disabled = false; }
      });
      side.append(down);
    }
    row.append(body, side);
    host.append(row);
  }
}

async function loadProfile() {
  const [purchases, sales, products] = await Promise.all([api('/api/orders/purchases'), api('/api/orders/sales'), api('/api/products/me')]);
  renderPurchases(purchases);
  renderSales(sales);
  renderListings(products);
}

function profileTab(name) {
  for (const tab of ['purchases', 'sales', 'listings']) {
    $(`${tab}-pane`).hidden = tab !== name;
    $(`tab-${tab}`).classList.toggle('active', tab === name);
  }
}

$('show-login').addEventListener('click', () => setAuthMode('login'));
$('show-register').addEventListener('click', () => setAuthMode('register'));
$('send-code').addEventListener('click', async () => {
  const form = $('register-form');
  const emailInput = form.elements.email;
  if (!emailInput.reportValidity()) return;
  const button = $('send-code');
  button.disabled = true;
  try {
    const result = await api('/api/auth/verification-codes', {
      method: 'POST', body: JSON.stringify({ email: emailInput.value.trim() })
    });
    let remaining = 60;
    clearInterval(codeCountdownTimer);
    button.textContent = `${remaining} 秒后重试`;
    codeCountdownTimer = setInterval(() => {
      remaining--;
      if (remaining <= 0) {
        clearInterval(codeCountdownTimer);
        button.textContent = '重新获取';
        button.disabled = false;
      } else {
        button.textContent = `${remaining} 秒后重试`;
      }
    }, 1000);
    toast(result.mode === 'SMTP' ? '验证码邮件已发送，请检查邮箱' : '本地验证码已生成，请查看后端控制台');
  } catch (error) {
    button.disabled = false;
    toast(`验证码发送失败：${error.message}`, true);
  }
});
$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const result = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email: String(form.get('email')), password: String(form.get('password')) }) });
    await finishLogin(result.accessToken);
    event.currentTarget.reset();
    toast('登录成功，欢迎来到商品广场');
  } catch (error) { toast(`登录失败：${error.message}`, true); }
  finally { button.disabled = false; }
});
$('register-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const email = String(form.get('email'));
  const password = String(form.get('password'));
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await api('/api/auth/register', { method: 'POST', body: JSON.stringify({
      nickname: String(form.get('nickname')), email, password, code: String(form.get('code'))
    }) });
    const result = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
    await finishLogin(result.accessToken);
    event.currentTarget.reset();
    toast('注册成功，欢迎来到商品广场');
  } catch (error) { toast(`注册失败：${error.message}`, true); }
  finally { button.disabled = false; }
});
$('brand-home').addEventListener('click', () => navigate('home'));
$('nav-home').addEventListener('click', () => navigate('home'));
$('nav-publish').addEventListener('click', () => navigate('publish'));
$('home-publish').addEventListener('click', () => navigate('publish'));
$('publish-back').addEventListener('click', () => navigate('home'));
$('nav-profile').addEventListener('click', () => navigate('profile'));
$('logout').addEventListener('click', () => logout());
$('tab-purchases').addEventListener('click', () => profileTab('purchases'));
$('tab-sales').addEventListener('click', () => profileTab('sales'));
$('tab-listings').addEventListener('click', () => profileTab('listings'));
$('detail-backdrop').addEventListener('click', closeDetail);
$('close-detail').addEventListener('click', closeDetail);
document.addEventListener('keydown', event => { if (event.key === 'Escape') closeDetail(); });
$('search-form').addEventListener('submit', event => {
  event.preventDefault();
  state.query = $('search-input').value.trim();
  state.page = 1;
  loadProducts().catch(error => toast(`搜索失败：${error.message}`, true));
});
$('clear-search').addEventListener('click', () => {
  $('search-input').value = '';
  state.query = '';
  state.page = 1;
  loadProducts().catch(error => toast(`加载失败：${error.message}`, true));
});
$('prev-page').addEventListener('click', () => { if (state.page > 1) { state.page--; loadProducts().catch(error => toast(error.message, true)); } });
$('next-page').addEventListener('click', () => { if (state.page * 12 < state.total) { state.page++; loadProducts().catch(error => toast(error.message, true)); } });
$('product-images').addEventListener('change', event => {
  const allowed = new Set(['image/jpeg', 'image/png', 'image/webp']);
  const selected = [...event.target.files];
  for (const file of selected) {
    if (!allowed.has(file.type)) { toast(`${file.name} 不是支持的图片格式`, true); continue; }
    if (file.size > 5 * 1024 * 1024) { toast(`${file.name} 超过 5 MB`, true); continue; }
    if (state.pendingImages.length >= 6) { toast('每件商品最多选择 6 张图片', true); break; }
    state.pendingImages.push(file);
  }
  event.target.value = '';
  renderImagePreview();
});

function renderImagePreview() {
  const host = $('image-preview');
  host.replaceChildren();
  state.pendingImages.forEach((file, index) => {
    const item = el('div', '', 'preview-item');
    const image = document.createElement('img');
    const objectUrl = URL.createObjectURL(file);
    image.src = objectUrl;
    image.alt = file.name;
    image.addEventListener('load', () => URL.revokeObjectURL(objectUrl), { once: true });
    const remove = el('button', '×', 'image-remove');
    remove.type = 'button';
    remove.title = '移除图片';
    remove.addEventListener('click', () => { state.pendingImages.splice(index, 1); renderImagePreview(); });
    item.append(image, remove);
    host.append(item);
  });
}

$('publish-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const created = await api('/api/products', { method: 'POST', body: JSON.stringify({
      categoryId: Number(form.get('categoryId')), title: String(form.get('title')),
      description: String(form.get('description')), price: Number(form.get('price')),
      conditionLevel: String(form.get('conditionLevel'))
    }) });
    let uploadError = null;
    if (state.pendingImages.length) {
      const images = new FormData();
      for (const file of state.pendingImages) images.append('files', file, file.name);
      try { await api(`/api/products/${created.id}/images`, { method: 'POST', body: images }); }
      catch (error) { uploadError = error; }
    }
    event.currentTarget.reset();
    state.pendingImages = [];
    renderImagePreview();
    state.query = '';
    state.page = 1;
    $('search-input').value = '';
    navigate('home');
    await loadProducts();
    toast(uploadError
      ? `商品 #${created.id} 已发布，但图片上传失败：${uploadError.message}。可在个人中心重试。`
      : `商品 #${created.id} 发布成功`, Boolean(uploadError));
  } catch (error) { toast(`发布失败：${error.message}`, true); }
  finally { button.disabled = false; }
});

(async function boot() {
  const token = sessionStorage.getItem('campusTradeToken');
  if (!token) return;
  try { await finishLogin(token); }
  catch (error) { logout(false); toast(`请重新登录：${error.message}`, true); }
})();
