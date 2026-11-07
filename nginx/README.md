# nginx/

Phase 3: `nginx.conf`, the entry point on host port 8080. It balances across the 3 gateways with keepalive, sets the client real-IP and forwarded-for headers, and returns 404 for `/actuator`. See plan section 13.
