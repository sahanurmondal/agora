import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Direct gRPC saturation of the rate-limiter: ramp far past RL_RATE for ONE key
// and assert the limiter's math — allowed ≈ burst + rate·t, everything else denied.
// Run from repo root: k6 run load/scenarios/rate-limiter-saturation.js

const client = new grpc.Client();
client.load(['../../contracts/proto'], 'ratelimit.proto');

const allowed = new Counter('rl_allowed');
const denied = new Counter('rl_denied');

export const options = {
  scenarios: {
    saturate: {
      executor: 'constant-arrival-rate',
      rate: 300, timeUnit: '1s',        // 6× the configured RL_RATE=50
      duration: '20s',
      preAllocatedVUs: 50,
    },
  },
  thresholds: {
    grpc_req_duration: ['p(95)<20'],
    rl_denied: ['count>1000'],          // saturation must actually deny
  },
};

export default () => {
  if (__ITER === 0) {
    client.connect(__ENV.RL_ADDR || 'localhost:9095', { plaintext: true });
  }
  const res = client.invoke('agora.ratelimit.v1.RateLimiter/Check', {
    key: 'k6-single-hot-key',
    rule: 'saturation-test',
  });
  check(res, { 'status OK': (r) => r && r.status === grpc.StatusOK });
  if (res.message.allowed) allowed.add(1); else denied.add(1);
};

export function handleSummary(data) {
  const a = data.metrics.rl_allowed ? data.metrics.rl_allowed.values.count : 0;
  const d = data.metrics.rl_denied ? data.metrics.rl_denied.values.count : 0;
  // Expected allowed ≈ burst(100) + rate(50)·20s = ~1100 of 6000
  return { stdout: JSON.stringify({ allowed: a, denied: d, expected_allowed: '~1100' }, null, 2) + '\n' };
}
