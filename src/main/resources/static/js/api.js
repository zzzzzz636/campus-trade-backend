let getToken = () => '';
let onUnauthorized = () => { };

export function configureApi(options) {
  getToken = options.getToken;
  onUnauthorized = options.onUnauthorized;
}

export async function api(path, options = {}) {
  const isForm = options.body instanceof FormData;
  const headers = new Headers(options.headers || {});
  if (options.body && !isForm && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(path, { ...options, headers });
  const raw = await response.text();
  let data = null;
  if (raw) {
    try { data = JSON.parse(raw); }
    catch { data = raw; }
  }
  if (!response.ok) {
    if (response.status === 401 && token) {
      onUnauthorized();
      throw new Error('登录已过期，请重新登录');
    }
    const detail = data && typeof data === 'object' ? (data.detail || data.message || data.title) : '';
    throw new Error(`${response.status}${detail ? `：${detail}` : ''}`);
  }
  return data;
}
