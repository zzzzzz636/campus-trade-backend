// Run twice against the same seeded MySQL dataset: SEARCH_BACKEND=mysql and elastic.
// Record DATASET_COUNT, VUS, DURATION, throughput, p95, and error rate from k6 output.
import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const backend = __ENV.SEARCH_BACKEND || 'mysql';
const query = encodeURIComponent(__ENV.QUERY === undefined ? '教材' : __ENV.QUERY);
if (!['mysql', 'elastic'].includes(backend)) throw new Error('SEARCH_BACKEND must be mysql or elastic');
if (!__ENV.DATASET_COUNT) throw new Error('Set DATASET_COUNT to the actual number of products');

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '1m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const path = backend === 'elastic' ? '/api/products/search' : '/api/products';
  const response = http.get(`${base}${path}?page=1&size=20&q=${query}`);
  check(response, { 'search 200': result => result.status === 200 });
}
