# loadtest/

`k6-script.js` drives load through Nginx (plan section 15). It runs as the `grafana/k6` container on the Compose network, so nothing needs installing.

| Variable | Default | Meaning |
|---|---|---|
| `SCENARIO` | `accuracy` | `accuracy`: 1000 requests from 50 virtual users. `baseline` and `burst` come in Phase 9 |
| `BASE_URL` | `http://nginx` | Where to send requests (the Nginx service inside Compose) |
| `API_KEY` | `demo-free-key-1` | Sent as `X-API-Key` |
| `TARGET_PATH` | `/api/demo/lua` | Path to hit; deliberately not named `PATH`, which clashes with the shell |

The summary prints allowed (200) per gateway, blocked (429) and unexpected responses; 429 counts as an expected status, not a failure.

```bash
docker run --rm --network rate-limiter-gateway_default -v "$PWD/loadtest:/scripts:ro" \
  -e TARGET_PATH=/api/demo/naive grafana/k6 run --quiet /scripts/k6-script.js
```

Warm up with one throwaway run first, and clear the demo buckets in Redis between measured runs (see the README's race condition demo).
