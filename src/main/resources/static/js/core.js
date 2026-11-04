export const state = {
  token: '', user: null, view: 'home', page: 1, query: '', categoryId: '',
  minPrice: '', maxPrice: '', sort: 'NEWEST', total: 0, categories: new Map(),
  selected: null, editingProduct: null, editReturnView: 'home', loadId: 0, pendingImages: [], favoriteIds: new Set(), isPreview: false
};

export const $ = id => document.getElementById(id);
export const money = value => `¥${Number(value).toFixed(2)}`;
export const conditionName = { NEW: '全新', LIKE_NEW: '近新', GOOD: '良好', FAIR: '可用' };
export const statusName = {
  ON_SALE: '在售', RESERVED: '已预订', SOLD: '已售出', OFF_SHELF: '已下架',
  PENDING_SELLER_CONFIRMATION: '等待卖家确认', WAITING_HANDOFF: '等待面交',
  WAITING_SHIPMENT: '等待卖家发货', SHIPPED: '已发货', COMPLETED: '已完成',
  REJECTED: '卖家已拒绝', CANCELLED: '已取消', EXPIRED: '申请已超时'
};
export const dateTime = value => value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '';

let toastTimer;

export function toast(message, error = false) {
  const node = $('toast');
  node.textContent = message;
  node.className = error ? 'toast error' : 'toast';
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 5000);
}

export function el(tag, text, className = '') {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

export function emptyPhoto() {
  const host = el('div', '', 'photo-empty');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 64 64');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = '<rect x="9" y="12" width="46" height="40" rx="5"/><circle cx="24" cy="25" r="4"/><path d="m13 46 14-13 8 7 8-9 8 9"/>';
  host.append(svg, el('span', '暂无照片'));
  return host;
}
