// Run only against an isolated MySQL test deployment with representative seeded data.
// BASE_URL and BUYER_TOKEN are supplied at runtime; do not commit credentials.
import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const buyerToken = __ENV.BUYER_TOKEN || '';
export const options = {
  scenarios: {
    product_search: { executor: 'constant-vus', vus: Number(__ENV.SEARCH_VUS || 10),
      duration: __ENV.DURATION || '1m', exec: 'search' },
    purchase_list: { executor: 'constant-vus', vus: Number(__ENV.ORDER_VUS || 5),
      duration: __ENV.DURATION || '1m', exec: 'orders' },
  },
};

export function search() {
  const response = http.get(`${base}/api/products?page=1&size=20&sort=NEWEST&q=教材`);
  check(response, { 'search 200': result => result.status === 200 });
}

export function orders() {
  if (!buyerToken) throw new Error('BUYER_TOKEN is required for the order scenario');
  const response = http.get(`${base}/api/orders/purchases/page?page=1&size=20`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  check(response, { 'orders 200': result => result.status === 200 });
}
