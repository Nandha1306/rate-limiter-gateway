import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const API_KEY = __ENV.API_KEY || 'demo-free-key-1';
const TARGET_PATH = __ENV.TARGET_PATH || '/api/demo/lua';
const SCENARIO = __ENV.SCENARIO || 'accuracy';

const SCENARIOS = {
  accuracy: { executor: 'shared-iterations', vus: 50, iterations: 1000, maxDuration: '1m' },
};

if (!SCENARIOS[SCENARIO]) {
  throw new Error(`Unknown SCENARIO "${SCENARIO}"; known: ${Object.keys(SCENARIOS).join(', ')}`);
}

const allowed = new Counter('allowed');
const blocked = new Counter('blocked');
const unexpected = new Counter('unexpected');
const allowedByGateway = {
  'gw-1': new Counter('allowed_gw_1'),
  'gw-2': new Counter('allowed_gw_2'),
  'gw-3': new Counter('allowed_gw_3'),
};

export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
};

http.setResponseCallback(http.expectedStatuses(200, 429));

/**
 * Sends one request to the target path and counts it as allowed (per gateway), blocked or unexpected.
 */
export default function () {
  const res = http.get(`${BASE_URL}${TARGET_PATH}`, { headers: { 'X-API-Key': API_KEY } });
  if (res.status === 200) {
    allowed.add(1);
    const gatewayCounter = allowedByGateway[res.headers['X-Gateway-Id']];
    if (gatewayCounter) {
      gatewayCounter.add(1);
    }
  } else if (res.status === 429) {
    blocked.add(1);
  } else {
    unexpected.add(1);
  }
}

/**
 * Prints the run's totals: requests, duration, allowed per gateway, blocked and unexpected responses.
 */
export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const seconds = (data.state.testRunDurationMs / 1000).toFixed(2);
  const text = [
    '',
    `scenario:      ${SCENARIO}`,
    `target:        ${TARGET_PATH} (API key ${API_KEY})`,
    `requests:      ${count('http_reqs')} from ${SCENARIOS[SCENARIO].vus} virtual users in ${seconds} s`,
    `allowed (200): ${count('allowed')}  [gw-1: ${count('allowed_gw_1')}, gw-2: ${count('allowed_gw_2')}, gw-3: ${count('allowed_gw_3')}]`,
    `blocked (429): ${count('blocked')}`,
    `unexpected:    ${count('unexpected')}`,
    '',
  ].join('\n');
  return { stdout: text };
}
