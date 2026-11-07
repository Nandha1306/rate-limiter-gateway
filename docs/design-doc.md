# Design Doc: Distributed Rate Limiter + API Gateway

_Draft. Filled in during Phase 9; outline from plan section 18._

1. Context and problem
2. Requirements (functional and non-functional)
3. Architecture and request flow
4. Algorithm choice and trade-offs
5. Key design (hash tags, tier, algorithm code)
6. Failure modes: Redis down, backend down, missed config messages
7. Consistency vs performance
8. Scaling: horizontal gateways, Redis Cluster, hot keys
9. Alternatives considered (Spring Cloud Gateway, Hazelcast, local-only)
10. Benchmark results
11. Known gaps and future work
