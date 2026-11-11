# nginx/

`nginx.conf` is the single entry point in front of the three gateways (plan section 13). Docker Compose publishes it on host port `${NGINX_PORT:-8088}`. Inside its container Nginx listens on **80**, which k6 (`http://nginx`) relies on. Container ports can't clash with anything on the host, so keep it at 80.

| Setting | Why |
|---|---|
| `upstream gateways` with gateway1–3, round robin, `keepalive 32` | Spreads requests across the instances and reuses connections to them |
| `proxy_http_version 1.1` + `Connection ""` | Required for keepalive to the gateways |
| `X-Real-IP` and `X-Forwarded-For` **set** to `$remote_addr` | Overwrites anything the client sent, so a caller can't fake its IP to dodge per-IP limits |
| `location /actuator` → 404 | Health and metrics stay internal; Prometheus scrapes gateways directly |
| `worker_processes 1` | Each worker keeps its own round-robin position. With one worker per core (12 here), a few fresh connections all start at gateway1. One worker gives predictable rotation and is plenty at this scale |
| `proxy_connect_timeout 1s`, `proxy_read_timeout 10s` | Fail fast on a dead gateway, and stay above the gateway's own 2 s backend timeout |

Check the syntax without starting the gateways:

```bash
docker run --rm --add-host gateway1:127.0.0.1 --add-host gateway2:127.0.0.1 --add-host gateway3:127.0.0.1 \
  -v "$PWD/nginx:/conf:ro" nginx:alpine nginx -t -c /conf/nginx.conf
```
