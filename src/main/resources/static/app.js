import { $, conditionName, dateTime, el, emptyPhoto, money, state, statusName, toast } from './js/core.js';
import { api, configureApi } from './js/api.js';
import { bindAuthEvents, configureAuth, logout, restoreSession } from './js/auth.js';
import { bindProfileEvents, configureProfile, loadProfile, profileTab } from './js/profile.js';
import { bindOrderEvents, closeOrderDetail, configureOrders, openOrderDetail, openPurchaseDialog } from './js/orders-v5.js';
import { bindMessagingEvents, closeChat, configureMessaging, openConversation } from './js/messaging.js';
import { configureFavorites, favoriteButton, loadFavoriteIds } from './js/favorites.js';
import { bindNotificationEvents, configureNotifications } from './js/notifications.js';
import { createMarketCard } from './js/market-card.js';
import { bindAgent } from './js/agent.js';

let previewItems = [];
let previewCategories = [];

function setFilterDrawer(open, restoreFocus = false) {
  const active = open && window.matchMedia('(max-width: 900px)').matches;
  $('market-filters').classList.toggle('open', active);
  $('filter-backdrop').hidden = !active;
  $('open-filters').setAttribute('aria-expanded', String(active));
  document.body.classList.toggle('market-filters-open', active);
  if (active) {
    $('market-filters').setAttribute('role', 'dialog');
    $('market-filters').setAttribute('aria-modal', 'true');
    $('close-filters').focus();
  } else {
    $('market-filters').removeAttribute('role');
    $('market-filters').removeAttribute('aria-modal');
    if (restoreFocus) $('open-filters').focus();
  }
}

function navigate(view) {
  setFilterDrawer(false);
  state.view = view;
  for (const name of ['home', 'publish', 'profile']) $(`${name}-view`).hidden = name !== view;
  $('app-view').classList.toggle('market-home-active', view === 'home');
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
  const categories = state.isPreview ? previewCategories : await api('/api/categories');
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
  renderCategoryShortcuts(categories);
}

function renderCategoryShortcuts(categories) {
  const host = $('category-shortcuts');
  host.replaceChildren();
  for (const category of [{ id: '', name: '全部' }, ...categories]) {
    const chip = el('button', category.name, 'category-chip');
    chip.type = 'button';
    chip.dataset.categoryId = String(category.id);
    chip.classList.toggle('active', String(category.id) === String(state.categoryId));
    chip.setAttribute('aria-pressed', String(category.id) === String(state.categoryId));
    chip.addEventListener('click', () => {
      $('category-filter').value = String(category.id);
      applyProductFilters();
    });
    host.append(chip);
  }
}

function syncFilterShortcuts() {
  for (const chip of $('category-shortcuts').querySelectorAll('.category-chip')) {
    chip.classList.toggle('active', chip.dataset.categoryId === $('category-filter').value);
    chip.setAttribute('aria-pressed', chip.dataset.categoryId === $('category-filter').value);
  }
  for (const chip of document.querySelectorAll('.quick-prices button')) {
    chip.classList.toggle('active', chip.dataset.min === $('min-price-filter').value.trim()
      && chip.dataset.max === $('max-price-filter').value.trim());
  }
}

function categoryName(id) { return state.categories.get(Number(id)) || '校园好物'; }
function displayCategory(item) { return item.categoryName || categoryName(item.categoryId); }
function hasProductFilters() {
  return Boolean(state.query || state.categoryId || state.minPrice || state.maxPrice || state.sort !== 'NEWEST');
}
function renderProducts(items) {
  const host = $('products');
  host.replaceChildren();
  if (!items.length) {
    const filtered = hasProductFilters();
    const empty = el('div', '', 'empty-state marketplace-empty');
    empty.append(emptyPhoto(), el('strong', filtered ? '没有找到符合条件的商品' : '这里还没有在售商品'),
      el('span', filtered ? '调整关键词或价格区间，再试一次。' : '成为第一个分享闲置的同学。'));
    const action = el('button', filtered ? '清除筛选' : '发布闲置', 'outline-btn');
    action.type = 'button';
    action.addEventListener('click', () => filtered ? $('clear-search').click() : startCreate());
    empty.append(action);
    host.append(empty);
  } else {
    for (const item of items) {
      host.append(createMarketCard(item, {
        preview: Boolean(state.isPreview), favoriteButton,
        onOpen: () => state.isPreview
          ? toast('这是本地设计预览；请返回真实平台浏览和交易。')
          : openDetail(item.id)
      }));
    }
  }
  $('result-label').textContent = state.isPreview
    ? `本地示例 · ${state.total} 件`
    : hasProductFilters() ? `${state.total} 件符合条件` : `${state.total} 件在售`;
  $('clear-search').disabled = !hasProductFilters();
  $('page-label').textContent = `第 ${state.page} 页`;
  $('prev-page').disabled = state.page <= 1;
  $('next-page').disabled = state.page * 12 >= state.total;
  document.querySelector('.market-pager').hidden = state.total <= 12;
  syncFilterShortcuts();
}

async function loadProducts() {
  if (state.isPreview) {
    const query = state.query.toLocaleLowerCase('zh-CN');
    const matches = previewItems.filter(item =>
      (!query || `${item.title} ${item.categoryName}`.toLocaleLowerCase('zh-CN').includes(query))
      && (!state.categoryId || String(item.categoryId) === String(state.categoryId))
      && (!state.minPrice || item.price >= Number(state.minPrice))
      && (!state.maxPrice || item.price <= Number(state.maxPrice)));
    matches.sort((a, b) => state.sort === 'PRICE_ASC' ? a.price - b.price
      : state.sort === 'PRICE_DESC' ? b.price - a.price : a.previewTile - b.previewTile);
    state.total = matches.length;
    renderProducts(matches.slice((state.page - 1) * 12, state.page * 12));
    return;
  }
  const id = ++state.loadId;
  const params = new URLSearchParams({ page: state.page, size: 12, q: state.query, sort: state.sort });
  if (state.categoryId) params.set('categoryId', state.categoryId);
  if (state.minPrice) params.set('minPrice', state.minPrice);
  if (state.maxPrice) params.set('maxPrice', state.maxPrice);
  const loadingTimer = window.setTimeout(() => {
    if (id !== state.loadId) return;
    const host = $('products');
    host.setAttribute('aria-busy', 'true');
    host.replaceChildren(...Array.from({ length: 8 }, () => {
      const skeleton = el('div', '', 'product-skeleton');
      skeleton.setAttribute('aria-hidden', 'true');
      return skeleton;
    }));
  }, 180);
  try {
    const [result] = await Promise.all([api(`/api/products?${params}`), loadFavoriteIds()]);
    if (id !== state.loadId) return;
    if (result.total > 0 && result.items.length === 0 && state.page > 1) {
      state.page = Math.ceil(result.total / 12);
      return loadProducts();
    }
    state.total = result.total;
    renderProducts(result.items);
  } catch (error) {
    if (id === state.loadId && state.token) {
      $('result-label').textContent = '商品加载遇到问题';
      const retry = el('button', '重试加载', 'outline-btn');
      retry.type = 'button';
      retry.addEventListener('click', () => loadProducts().catch(next => toast(next.message, true)));
      const empty = el('div', '', 'empty-state marketplace-empty');
      empty.append(el('strong', '商品暂时无法显示'), el('span', '请检查网络连接后重试。'), retry);
      $('products').replaceChildren(empty);
    }
    throw error;
  } finally {
    window.clearTimeout(loadingTimer);
    if (id === state.loadId) $('products').setAttribute('aria-busy', 'false');
  }
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
    main.addEventListener('error', () => coverHost.replaceChildren(emptyPhoto()), { once: true });
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
  } else coverHost.append(emptyPhoto());
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
    $('detail-status').textContent = statusName[product.status] || product.status;
    $('detail-status').className = `status ${product.status === 'ON_SALE' ? '' : 'down'}`;
    $('detail-seller').textContent = product.sellerNickname || '校园同学';
    $('detail-seller-avatar').textContent = (product.sellerNickname || '同').slice(0, 1);
    $('detail-published').textContent = product.createdAt ? `发布于 ${dateTime(product.createdAt)}` : '';
    $('detail-price').textContent = money(product.price);
    $('detail-description').textContent = product.description;
    $('detail-condition').textContent = `成色：${conditionName[product.condition] || product.condition}`;
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
  clearProductRiskResult();
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
$('open-filters').addEventListener('click', () => setFilterDrawer(true));
$('close-filters').addEventListener('click', () => setFilterDrawer(false, true));
$('filter-backdrop').addEventListener('click', () => setFilterDrawer(false, true));
$('apply-mobile-filters').addEventListener('click', () => {
  if (applyProductFilters()) setFilterDrawer(false, true);
});
window.matchMedia('(max-width: 900px)').addEventListener('change', () => setFilterDrawer(false));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && $('market-filters').classList.contains('open')) setFilterDrawer(false, true);
});
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
  applyProductFilters();
});
$('category-filter').addEventListener('change', applyProductFilters);
$('min-price-filter').addEventListener('change', applyProductFilters);
$('max-price-filter').addEventListener('change', applyProductFilters);
$('sort-filter').addEventListener('change', applyProductFilters);
for (const chip of document.querySelectorAll('.quick-prices button')) {
  chip.addEventListener('click', () => {
    $('min-price-filter').value = chip.dataset.min;
    $('max-price-filter').value = chip.dataset.max;
    applyProductFilters();
  });
}
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
  $('price-filter-error').hidden = true;
  syncFilterShortcuts();
  loadProducts().catch(error => toast(`加载失败：${error.message}`, true));
});

function applyProductFilters() {
  const min = $('min-price-filter').value.trim();
  const max = $('max-price-filter').value.trim();
  const validMoney = value => !value || /^(?:0|[1-9]\d{0,7})(?:\.\d{1,2})?$/.test(value);
  let problem = '';
  if (!validMoney(min) || !validMoney(max)) problem = '请输入不超过两位小数的价格。';
  else if (min && max && Number(min) > Number(max)) problem = '最低价不能高于最高价。';
  $('price-filter-error').textContent = problem;
  $('price-filter-error').hidden = !problem;
  if (problem) return false;
  state.query = $('search-input').value.trim();
  state.categoryId = $('category-filter').value;
  state.minPrice = min;
  state.maxPrice = max;
  state.sort = $('sort-filter').value;
  state.page = 1;
  syncFilterShortcuts();
  loadProducts().catch(error => toast(`筛选失败：${error.message}`, true));
  return true;
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

let previewObjectUrl = null;
function closePublishPreview() {
  $('publish-preview-dialog').close();
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl);
  previewObjectUrl = null;
}
$('publish-preview').addEventListener('click', () => {
  const form = $('publish-form');
  const host = $('publish-preview-content');
  host.replaceChildren();
  const photo = el('div', '', 'publish-preview-photo');
  const file = state.pendingImages[0];
  const existing = state.editingProduct?.images?.[0]?.url;
  if (file || existing) {
    const image = document.createElement('img');
    previewObjectUrl = file ? URL.createObjectURL(file) : null;
    image.src = previewObjectUrl || existing;
    image.alt = '商品封面预览';
    photo.append(image);
  } else photo.append(emptyPhoto());
  const text = el('div', '', 'publish-preview-info');
  const title = form.elements.title.value.trim();
  const price = form.elements.price.value;
  text.append(el('span', categoryName(form.elements.categoryId.value), 'tag'),
    el('h3', title || '尚未填写标题'),
    el('strong', price && Number(price) > 0 ? money(price) : '尚未填写价格', 'detail-price'),
    el('p', `成色：${conditionName[form.elements.conditionLevel.value] || '尚未选择'}`, 'subtle'),
    el('p', form.elements.description.value.trim() || '尚未填写商品描述', 'detail-description'));
  host.append(photo, text);
  $('publish-preview-dialog').showModal();
});
$('close-publish-preview').addEventListener('click', closePublishPreview);
$('publish-preview-dialog').addEventListener('click', event => {
  if (event.target === $('publish-preview-dialog')) closePublishPreview();
});
$('publish-preview-dialog').addEventListener('close', () => {
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl);
  previewObjectUrl = null;
});

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
    clearProductRiskResult();
    toast(`AI 已建议“${suggestion.categoryName}”分类。请检查内容和价格后再发布。`);
  } catch (error) {
    toast(`AI 整理未完成：${error.message}。原内容已保留，可以继续手动发布。`, true);
  } finally {
    button.disabled = false;
    button.textContent = 'AI 帮我整理';
  }
});

function clearProductRiskResult() {
  const result = $('product-risk-result');
  result.hidden = true;
  result.replaceChildren();
}

function renderProductRiskResult(risk) {
  const result = $('product-risk-result');
  const names = { low: '低风险提示', medium: '需要进一步核实', high: '建议谨慎核实' };
  result.dataset.level = risk.riskLevel;
  result.replaceChildren(el('h3', names[risk.riskLevel] || '交易风险提示'));
  const reasons = el('ul', '');
  for (const reason of risk.reasons || []) reasons.append(el('li', reason));
  result.append(reasons);
  if (risk.suggestions?.length) {
    result.append(el('strong', '建议核实'));
    const suggestions = el('ul', '');
    for (const suggestion of risk.suggestions) suggestions.append(el('li', suggestion));
    result.append(suggestions);
  }
  if (risk.needsManualReview) result.append(el('p', '建议人工复核；平台不会根据本次检查自动下架商品。'));
  result.append(el('p', risk.notice || (risk.analysisMode === 'RULES_ONLY' ? '仅完成规则检查。' : '结果仅供参考。')));
  result.hidden = false;
}

$('publish-form').addEventListener('input', clearProductRiskResult);
$('publish-form').addEventListener('change', clearProductRiskResult);
$('product-risk-button').addEventListener('click', async () => {
  const form = $('publish-form');
  const title = form.elements.title.value.trim();
  const description = form.elements.description.value.trim();
  const priceText = form.elements.price.value.trim();
  const categoryId = Number(form.elements.categoryId.value);
  if (!title || !description || !/^(?:0|[1-9]\d{0,7})(?:\.\d{1,2})?$/.test(priceText)
      || Number(priceText) <= 0 || !categoryId) {
    toast('请先填写标题、描述、分类及有效价格，再检查交易风险', true);
    return;
  }
  const button = $('product-risk-button');
  button.disabled = true;
  button.textContent = '检查中…';
  clearProductRiskResult();
  try {
    const risk = await api('/api/ai/product-risk', {
      method: 'POST', body: JSON.stringify({ title, description, price: Number(priceText), categoryId })
    });
    renderProductRiskResult(risk);
  } catch (error) {
    toast(`风险检查未完成：${error.message}。可继续手动发布。`, true);
  } finally {
    button.disabled = false;
    button.textContent = '检查当前商品';
  }
});

$('publish-form').addEventListener('submit', async event => {
  event.preventDefault();
  // currentTarget 仅在同步事件分发期间可靠；先保存表单引用，避免 await 后变为 null。
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  const button = formElement.querySelector('button[type="submit"]');
  const editing = state.editingProduct;
  const priceText = String(form.get('price')).trim();
  if (!/^(?:0|[1-9]\d{0,7})(?:\.\d{1,2})?$/.test(priceText) || Number(priceText) <= 0) {
    toast('价格应大于 0，最多两位小数，且不超过 99999999.99 元', true);
    formElement.elements.price.focus();
    return;
  }
  const payload = {
    categoryId: Number(form.get('categoryId')), title: String(form.get('title')),
    description: String(form.get('description')), price: Number(priceText),
    conditionLevel: String(form.get('conditionLevel'))
  };
  if (editing) payload.version = editing.version;
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
configureProfile({ navigate, openDetail, startEdit, takeDown, loadProducts, displayCategory });
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
bindAgent({
  openProduct: openDetail,
  useDraft: draft => {
    setPublishMode();
    navigate('publish');
    const form = $('publish-form');
    form.elements.title.value = draft.title;
    form.elements.description.value = draft.description;
    form.elements.categoryId.value = String(draft.categoryId);
    toast('草稿已回填，请核对价格、成色和实物信息后再发布。');
  }
});
bindAuthEvents();
bindProfileEvents();
bindOrderEvents();
bindMessagingEvents();
bindNotificationEvents();
async function initPreview() {
  ({ previewItems, previewCategories } = await import('./js/market-preview-data.js'));
  state.isPreview = true;
  $('auth-view').hidden = true;
  $('app-view').hidden = false;
  $('app-view').classList.add('preview-mode');
  $('market-preview-note').hidden = false;
  navigate('home');
  await loadCategories();
  await loadProducts();
}

const localPreview = new URLSearchParams(location.search).get('uiPreview') === '1'
  && ['localhost', '127.0.0.1'].includes(location.hostname);
if (localPreview) initPreview().catch(error => toast(`本地预览加载失败：${error.message}`, true));
else restoreSession();
