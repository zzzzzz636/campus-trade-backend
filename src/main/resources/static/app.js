import { $, conditionName, dateTime, el, money, state, toast } from './js/core.js';
import { api, configureApi } from './js/api.js';
import { bindAuthEvents, configureAuth, logout, restoreSession } from './js/auth.js';
import { bindProfileEvents, configureProfile, loadProfile, profileTab } from './js/profile.js';
import { bindOrderEvents, closeOrderDetail, configureOrders, openOrderDetail, openPurchaseDialog } from './js/orders-v5.js';
import { bindMessagingEvents, closeChat, configureMessaging, openConversation } from './js/messaging.js';
import { configureFavorites, favoriteButton, loadFavoriteIds } from './js/favorites.js';
import { bindNotificationEvents, configureNotifications } from './js/notifications.js';

function navigate(view) {
  state.view = view;
  for (const name of ['home', 'publish', 'profile']) $(`${name}-view`).hidden = name !== view;
  $('nav-home').classList.toggle('active', view === 'home');
  $('nav-publish').classList.toggle('active', view === 'publish');
  $('nav-profile').classList.toggle('active', view === 'profile');
  closeDetail();
  closeOrderDetail();
  closeChat();
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (view === 'profile') loadProfile().catch(error => toast(`加载个人中心失败：${error.message}`, true));
}

async function loadCategories() {
  const categories = await api('/api/categories');
  state.categories = new Map(categories.map(category => [Number(category.id), category.name]));
  const select = $('category-select');
  select.replaceChildren();
  const filter = $('category-filter');
  filter.replaceChildren(el('option', '全部分类'));
  filter.firstElementChild.value = '';
  for (const category of categories) {
    const option = el('option', category.name);
    option.value = category.id;
    select.append(option);
    const filterOption = el('option', category.name);
    filterOption.value = category.id;
    filter.append(filterOption);
  }
  filter.value = state.categoryId;
}

function categoryName(id) { return state.categories.get(Number(id)) || '校园好物'; }
function displayCategory(item) { return item.categoryName || categoryName(item.categoryId); }
function hasProductFilters() {
  return Boolean(state.query || state.categoryId || state.minPrice || state.maxPrice || state.sort !== 'NEWEST');
}
function iconFor(item) {
  const text = `${displayCategory(item)} ${item.title}`;
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
    body.append(el('span', displayCategory(item), 'tag'), el('h3', item.title), el('div', '等待真实商品发布', 'subtle'));
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
  if (!items.length && state.total === 0 && !hasProductFilters()) {
    sampleCards();
  } else if (!items.length) {
    const empty = el('div', '', 'empty-state');
    empty.append(el('div', '🔎', 'empty-icon'), el('strong', '没有找到相关商品'), el('span', '试试其他关键词，或者清除搜索条件。'));
    host.append(empty);
  } else {
    for (const [index, item] of items.entries()) {
      const card = el('article', '', 'product-card');
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      const body = el('div', '', 'product-body');
      body.append(el('span', displayCategory(item), 'tag'), el('h3', item.title), el('div', money(item.price), 'price'));
      const meta = el('div', '', 'product-meta');
      meta.append(el('span', `发布者 · ${item.sellerNickname || '校园同学'}`), el('span', conditionName[item.condition] || item.condition));
      body.append(meta, el('time', item.createdAt ? `发布于 ${dateTime(item.createdAt)}` : '', 'product-time'));
      const cardCover = cover(item, index);
      cardCover.append(favoriteButton(item.id, true));
      card.append(cardCover, body);
      card.addEventListener('click', () => openDetail(item.id));
      card.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') openDetail(item.id); });
      host.append(card);
    }
  }
  $('result-label').textContent = hasProductFilters() ? `符合当前条件的商品 · ${state.total} 件` : `全部在售商品 · ${state.total} 件`;
  $('clear-search').hidden = !hasProductFilters();
  $('page-label').textContent = `第 ${state.page} 页`;
  $('prev-page').disabled = state.page <= 1;
  $('next-page').disabled = state.page * 12 >= state.total;
  document.querySelector('.pager').hidden = state.total <= 12;
}

async function loadProducts() {
  const id = ++state.loadId;
  const params = new URLSearchParams({ page: state.page, size: 12, q: state.query, sort: state.sort });
  if (state.categoryId) params.set('categoryId', state.categoryId);
  if (state.minPrice) params.set('minPrice', state.minPrice);
  if (state.maxPrice) params.set('maxPrice', state.maxPrice);
  const [result] = await Promise.all([api(`/api/products?${params}`), loadFavoriteIds()]);
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
    const managingFromProfile = own && state.view === 'profile';
    renderDetailImages(product, managingFromProfile && product.status === 'ON_SALE');
    $('detail-category').textContent = displayCategory(product);
    $('detail-title').textContent = product.title;
    $('detail-seller').textContent = `发布者 · ${product.sellerNickname || '校园同学'}`;
    $('detail-published').textContent = product.createdAt ? `发布于 ${dateTime(product.createdAt)}` : '';
    $('detail-price').textContent = money(product.price);
    $('detail-description').textContent = product.description;
    $('detail-condition').textContent = `商品成色：${conditionName[product.condition] || product.condition}`;
    const actions = $('detail-actions');
    actions.replaceChildren();
    if (!own) actions.append(favoriteButton(product.id));
    if (product.status === 'ON_SALE') {
      if (own) {
        if (managingFromProfile) {
          const editButton = el('button', '编辑商品信息', 'primary secondary-action');
          editButton.type = 'button';
          editButton.addEventListener('click', () => startEdit(product));
          const downButton = el('button', '下架这件商品', 'primary danger');
          downButton.type = 'button';
          downButton.addEventListener('click', async () => {
            downButton.disabled = true;
            try { await takeDown(product.id); }
            catch (error) { toast(`操作失败：${error.message}`, true); downButton.disabled = false; }
          });
          actions.append(editButton, downButton);
        } else {
          const tip = el('p', '这是你发布的商品，请前往个人中心管理。', 'detail-owner-tip');
          const manageButton = el('button', '前往“我发布的”', 'primary secondary-action');
          manageButton.type = 'button';
          manageButton.addEventListener('click', () => {
            closeDetail();
            profileTab('listings');
            navigate('profile');
          });
          actions.append(tip, manageButton);
        }
      } else {
        const contact = el('button', '联系卖家', 'outline-btn');
        contact.type = 'button';
        contact.addEventListener('click', async () => {
          contact.disabled = true;
          try { await openConversation('PRODUCT', product.id); }
          catch (error) { toast(`无法打开私信：${error.message}`, true); }
          finally { contact.disabled = false; }
        });
        const button = el('button', '提交购买申请', 'primary');
        button.type = 'button';
        button.addEventListener('click', () => {
          closeDetail();
          openPurchaseDialog(product);
        });
        actions.append(contact, button);
      }
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

function setPublishMode(product = null) {
  state.editingProduct = product;
  const editing = Boolean(product);
  $('publish-eyebrow').textContent = editing ? 'EDIT LISTING' : 'SELL AN ITEM';
  $('publish-title').textContent = editing ? '编辑商品信息' : '发布你的闲置';
  $('publish-subtitle').textContent = editing ? '只有在售商品可以修改；图片可在商品详情中继续管理。' : '写清楚物品情况，方便同学了解。发布后会出现在商品广场。';
  $('publish-form-tip').textContent = editing ? '选择的新照片会追加到现有图片中，最多仍为 6 张。' : '发布前可以预览并移除已选择的照片。';
  $('publish-submit').textContent = editing ? '保存修改 →' : '确认发布 →';
  $('publish-back').textContent = editing ? '← 返回我的发布' : '← 返回商品广场';
  $('ai-draft-panel').hidden = editing;
}

function clearPendingImages() {
  state.pendingImages = [];
  renderImagePreview();
}

function startCreate() {
  state.editReturnView = 'home';
  setPublishMode();
  $('publish-form').reset();
  clearPendingImages();
  navigate('publish');
}

function startEdit(product) {
  if (!product || product.status !== 'ON_SALE') {
    toast('只有在售商品可以编辑', true);
    return;
  }
  if (Number(product.sellerId) !== Number(state.user.id)) {
    toast('只能编辑自己发布的商品', true);
    return;
  }
  state.editReturnView = 'profile';
  setPublishMode(product);
  const form = $('publish-form');
  form.elements.categoryId.value = String(product.categoryId);
  form.elements.conditionLevel.value = product.condition;
  form.elements.title.value = product.title;
  form.elements.description.value = product.description;
  form.elements.price.value = product.price;
  clearPendingImages();
  navigate('publish');
}

$('brand-home').addEventListener('click', () => navigate('home'));
$('nav-home').addEventListener('click', () => navigate('home'));
$('nav-publish').addEventListener('click', startCreate);
$('home-publish').addEventListener('click', startCreate);
$('publish-back').addEventListener('click', () => {
  const returnView = state.editingProduct ? state.editReturnView : 'home';
  setPublishMode();
  clearPendingImages();
  if (returnView === 'profile') profileTab('listings');
  navigate(returnView);
});
$('detail-backdrop').addEventListener('click', closeDetail);
$('close-detail').addEventListener('click', closeDetail);
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && $('chat-overlay').hidden && $('order-overlay').hidden) closeDetail();
});
$('search-form').addEventListener('submit', event => {
  event.preventDefault();
  state.query = $('search-input').value.trim();
  state.categoryId = $('category-filter').value;
  state.minPrice = $('min-price-filter').value;
  state.maxPrice = $('max-price-filter').value;
  state.sort = $('sort-filter').value;
  state.page = 1;
  loadProducts().catch(error => toast(`搜索失败：${error.message}`, true));
});
$('category-filter').addEventListener('change', applyProductFilters);
$('min-price-filter').addEventListener('change', applyProductFilters);
$('max-price-filter').addEventListener('change', applyProductFilters);
$('sort-filter').addEventListener('change', applyProductFilters);
$('clear-search').addEventListener('click', () => {
  $('search-input').value = '';
  $('category-filter').value = '';
  $('min-price-filter').value = '';
  $('max-price-filter').value = '';
  $('sort-filter').value = 'NEWEST';
  state.query = '';
  state.categoryId = '';
  state.minPrice = '';
  state.maxPrice = '';
  state.sort = 'NEWEST';
  state.page = 1;
  loadProducts().catch(error => toast(`加载失败：${error.message}`, true));
});

function applyProductFilters() {
  state.query = $('search-input').value.trim();
  state.categoryId = $('category-filter').value;
  state.minPrice = $('min-price-filter').value;
  state.maxPrice = $('max-price-filter').value;
  state.sort = $('sort-filter').value;
  state.page = 1;
  loadProducts().catch(error => toast(`筛选失败：${error.message}`, true));
}
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

$('ai-draft-button').addEventListener('click', async () => {
  const form = $('publish-form');
  const rawTitle = form.elements.title.value.trim();
  const rawDescription = form.elements.description.value.trim();
  if (!rawDescription) {
    toast('请先在商品描述中写下物品的真实情况，再让 AI 帮你整理', true);
    form.elements.description.focus();
    return;
  }

  const button = $('ai-draft-button');
  button.disabled = true;
  button.textContent = 'AI 正在整理…';
  try {
    const suggestion = await api('/api/ai/product-draft', {
      method: 'POST',
      body: JSON.stringify({
        rawTitle,
        rawDescription,
        conditionLevel: form.elements.conditionLevel.value
      })
    });
    form.elements.title.value = suggestion.title;
    form.elements.description.value = suggestion.description;
    form.elements.categoryId.value = String(suggestion.categoryId);
    toast(`AI 已建议“${suggestion.categoryName}”分类。请检查内容和价格后再发布。`);
  } catch (error) {
    toast(`AI 整理未完成：${error.message}。原内容已保留，可以继续手动发布。`, true);
  } finally {
    button.disabled = false;
    button.textContent = '✨ AI 帮我整理';
  }
});

$('publish-form').addEventListener('submit', async event => {
  event.preventDefault();
  // currentTarget 仅在同步事件分发期间可靠；先保存表单引用，避免 await 后变为 null。
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  const button = formElement.querySelector('button[type="submit"]');
  const editing = state.editingProduct;
  const payload = {
    categoryId: Number(form.get('categoryId')), title: String(form.get('title')),
    description: String(form.get('description')), price: Number(form.get('price')),
    conditionLevel: String(form.get('conditionLevel'))
  };
  button.disabled = true;
  try {
    const saved = await api(editing ? `/api/products/${editing.id}` : '/api/products', {
      method: editing ? 'PUT' : 'POST', body: JSON.stringify(payload)
    });
    const productId = editing ? editing.id : saved.id;
    let uploadError = null;
    if (state.pendingImages.length) {
      const images = new FormData();
      for (const file of state.pendingImages) images.append('files', file, file.name);
      try { await api(`/api/products/${productId}/images`, { method: 'POST', body: images }); }
      catch (error) { uploadError = error; }
    }
    formElement.reset();
    setPublishMode();
    clearPendingImages();
    state.query = '';
    state.categoryId = '';
    state.minPrice = '';
    state.maxPrice = '';
    state.sort = 'NEWEST';
    state.page = 1;
    $('search-input').value = '';
    $('category-filter').value = '';
    $('min-price-filter').value = '';
    $('max-price-filter').value = '';
    $('sort-filter').value = 'NEWEST';
    const returnView = editing ? state.editReturnView : 'home';
    if (returnView === 'profile') profileTab('listings');
    navigate(returnView);
    await loadProducts();
    toast(uploadError
      ? `商品 #${productId} 信息已保存，但图片上传失败：${uploadError.message}。可在个人中心重试。`
      : `商品 #${productId} ${editing ? '修改' : '发布'}成功`, Boolean(uploadError));
  } catch (error) { toast(`${editing ? '修改' : '发布'}失败：${error.message}`, true); }
  finally { button.disabled = false; }
});

configureApi({ getToken: () => state.token, onUnauthorized: () => logout(false) });
configureAuth({
  navigate, closeDetail, loadCategories, loadProducts,
  resetProductDraft: () => {
    setPublishMode();
    clearPendingImages();
    $('publish-form').reset();
  }
});
configureProfile({ navigate, openDetail, startEdit, takeDown, loadProducts, iconFor, displayCategory });
configureOrders({
  refresh: async () => {
    await loadProducts();
    if (state.view === 'profile') await loadProfile();
  },
  openConversation
});
configureMessaging({
  afterChange: async () => { if (state.view === 'profile') await loadProfile(); },
  openProductDetail: openDetail,
  openOrderDetail
});
configureFavorites({ openDetail, refreshProducts: loadProducts });
configureNotifications({ openOrder: openOrderDetail });
bindAuthEvents();
bindProfileEvents();
bindOrderEvents();
bindMessagingEvents();
bindNotificationEvents();
restoreSession();
