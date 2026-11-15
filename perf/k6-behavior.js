// Synthetic HTTP traffic against an isolated test product. Every successful detail view
// emits one best-effort Kafka behavior event. Never run this against business data.
import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL;
const productId = __ENV.PRODUCT_ID;
if (!base || !productId) throw new Error('BASE_URL and PRODUCT_ID are required');

export const options = {
  scenarios: {
    views: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.TARGET_RPS || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 100),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const response = http.get(`${base}/api/products/${productId}`);
  check(response, { 'product detail 200': result => result.status === 200 });
}
