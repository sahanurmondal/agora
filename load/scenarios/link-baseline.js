import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

// Baseline for link-service: 90% redirect reads / 10% creates.
// Read path exercises cache-aside (first hit per code = miss->Postgres,
// after that Redis). NUMBERS.md row: max sustained QPS @ p95<50ms.

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const notFound = new Rate('not_found');

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 300,
      stages: [
        { duration: '30s', target: 200 },
        { duration: '1m', target: 500 },
        { duration: '30s', target: 500 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<50', 'p(99)<200'],
    http_req_failed: ['rate<0.01'],
    not_found: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // Seed a pool of links; the test reads from these.
  const codes = [];
  for (let i = 0; i < 50; i++) {
    const res = http.post(`${BASE}/api/v1/links`,
      JSON.stringify({ url: `https://example.com/product/${i}` }),
      { headers: { 'Content-Type': 'application/json' } });
    check(res, { 'seed created': (r) => r.status === 201 });
    codes.push(res.json('code'));
  }
  return { codes };
}

export default function (data) {
  if (Math.random() < 0.9) {
    const code = data.codes[Math.floor(Math.random() * data.codes.length)];
    const res = http.get(`${BASE}/${code}`, { redirects: 0 });
    notFound.add(res.status === 404);
    check(res, { 'redirects 302': (r) => r.status === 302 });
  } else {
    const res = http.post(`${BASE}/api/v1/links`,
      JSON.stringify({ url: `https://example.com/p/${Date.now()}-${Math.random()}` }),
      { headers: { 'Content-Type': 'application/json' } });
    check(res, { 'created 201': (r) => r.status === 201 });
  }
}

export function handleSummary(data) {
  const date = new Date().toISOString().slice(0, 10);
  return {
    [`load/results/link-baseline-${date}.json`]: JSON.stringify(data, null, 2),
    stdout: JSON.stringify({
      p95_ms: data.metrics.http_req_duration.values['p(95)'],
      p99_ms: data.metrics.http_req_duration.values['p(99)'],
      rps: data.metrics.http_reqs.values.rate,
      failed_rate: data.metrics.http_req_failed.values.rate,
    }, null, 2) + '\n',
  };
}
