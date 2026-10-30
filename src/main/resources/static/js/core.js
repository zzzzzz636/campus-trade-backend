export const state = {
  token: '', user: null, view: 'home', page: 1, query: '', categoryId: '',
  minPrice: '', maxPrice: '', sort: 'NEWEST', total: 0, categories: new Map(),
  selected: null, editingProduct: null, editReturnView: 'home', loadId: 0, pendingImages: []
};

export const $ = id => document.getElementById(id);
export const money = value => `¥${Number(value).toFixed(2)}`;
export const conditionName = { NEW: '全新', LIKE_NEW: '近新', GOOD: '良好', FAIR: '可用' };
export const statusName = {
  ON_SALE: '在售', RESERVED: '已预订', SOLD: '已售出', OFF_SHELF: '已下架',
  PENDING_MEETUP: '待线下面交', COMPLETED: '已完成', CANCELLED: '已取消'
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
