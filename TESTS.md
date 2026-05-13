# Integration test suite

20 tests in `bin/test`. Run with `./bin/test` (full suite, ~4 min cold-start) or `./bin/test --existing` (against a running stack, ~1.5 min). Individual tests with `./bin/test test_name`.

Tests run in the order documented below — read-only first, then progressively more invasive. Every destructive test restores baseline state before returning, so a failed cleanup fails the test (no silent contamination).

`require_baseline` (called by destructive tests) waits up to 10 s for both buckets to be at exactly 2 servers before proceeding. This absorbs the small transient between a successful kill-and-restart cycle and the new HealthWatcher fully stabilising.

---

## A. Baseline / read-only

### 1. `test_containers_running`

**Verifies:** every container we depend on is in the `running` state.

**How:** for each of `broker`, `consul`, `bff-t-1`, `bff-t-2`, `bff-b-1`, `bff-b-2`, `server-t-1`, `server-t-2`, `server-b-1`, `server-b-2`, `ui`, calls `docker inspect -f '{{.State.Running}}'` and checks for `true`.

**Pass criteria:** all 11 are running.

### 2. `test_consul_responds`

**Verifies:** Consul's HTTP API is reachable and the agent has a leader.

**How:** `curl http://127.0.0.1:8500/v1/status/leader`.

**Pass criteria:** non-empty response (the leader's address).

### 3. `test_ui_serves_html`

**Verifies:** the UI container is serving the React bundle.

**How:** `curl -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/`.

**Pass criteria:** HTTP 200.

### 4. `test_trading_bucket_size_2`

**Verifies:** trading BFF has discovered exactly 2 backends.

**How:** `GET /api/trading/servers`, count entries.

**Pass criteria:** count == 2.

### 5. `test_billing_bucket_size_2`

**Verifies:** same for the billing bucket.

**How:** `GET /api/billing/servers`, count entries.

**Pass criteria:** count == 2.

### 6. `test_consul_has_all_8_services`

**Verifies:** every BFF and backend has independently registered with Consul.

**How:** `GET /v1/agent/services`, count entries.

**Pass criteria:** count == 8 (4 `echo-server` + 4 `bff`).

### 7. `test_consul_health_all_passing`

**Verifies:** every health check Consul knows about is passing.

**How:** `GET /v1/health/state/critical`, count entries.

**Pass criteria:** count == 0.

### 8. `test_trading_echo_works`

**Verifies:** the full path — UI host → nginx → trading BFF → trading backend — works.

**How:** `POST /api/trading/echo` with `{"message":"hi"}`, parse `serverId` from the JSON reply.

**Pass criteria:** `serverId` matches `server-t-*`.

### 9. `test_billing_echo_works`

**Verifies:** same for the billing path.

**How:** `POST /api/billing/echo`, parse `serverId`.

**Pass criteria:** `serverId` matches `server-b-*`.

### 10. `test_trading_round_robin_hits_both`

**Verifies:** the trading BFF's round-robin spreads load across backends.

**How:** fire 8 trading echoes, collect unique `serverId` values.

**Pass criteria:** unique-count ≥ 2 (at least two different backends saw traffic).

### 11. `test_role_isolation`

**Verifies:** billing traffic never crosses into the trading pool.

**How:** fire 6 billing echoes, count how many `serverId`s start with `server-t-`.

**Pass criteria:** count == 0.

### 12. `test_known_nodes_includes_other_roles`

**Verifies:** the cross-role observability view — a trading BFF knows about billing nodes too (just doesn't route to them).

**How:** `GET /api/trading/known-nodes`, count entries where `role == "billing"`.

**Pass criteria:** count ≥ 4 (2 billing BFFs + 2 billing backends).

---

## B. Server lifecycle

### 13. `test_kill_server_drops_from_bff`

**Verifies:** when a backend is `kill -9`'d, the BFF detects via `Health.Watch` keepalive and removes it from its routing pool within ~10 s.

**How:**
1. `require_baseline`.
2. `docker kill server-t-1`.
3. `wait_bucket_size trading 1` (timeout 10 s).
4. **Cleanup:** `docker start server-t-1`; `wait_bucket_size trading 2` (timeout 30 s).

**Pass criteria:** step 3 succeeds (BFF view shrunk to 1) **and** cleanup succeeds.

### 14. `test_kill_server_marked_critical_in_consul`

**Verifies:** Consul's independent health probe also detects the dead backend.

**How:**
1. `require_baseline`.
2. `docker kill server-t-1`.
3. Poll `GET /v1/health/service/echo-server?passing=false` for up to 8 s until the gRPC check on `server-t-1` reports `critical`.
4. **Cleanup:** `docker start server-t-1`; `wait_bucket_size trading 2` (30 s).

**Pass criteria:** Consul flips the check within 8 s **and** cleanup succeeds.

### 15. `test_kill_billing_server_isolates_trading`

**Verifies:** the cross-bucket isolation contract — killing a billing backend has zero effect on the trading bucket.

**How:**
1. `require_baseline`.
2. `docker kill server-b-1`.
3. Sample trading bucket size 6 times at 1 s intervals.
4. **Cleanup:** `docker start server-b-1`; `wait_bucket_size billing 2` (30 s).

**Pass criteria:** all 6 samples == 2 (trading bucket never changed).

### 16. `test_drain_removes_server`

**Verifies:** the graceful drain path — UI button → BFF → `DrainService.RequestDrain` → backend exits cleanly within the grace window.

**How:**
1. `require_baseline`.
2. `POST /api/trading/servers/server-t-2/drain`.
3. `wait_bucket_size trading 1` (timeout 10 s — covers the 3 s drain grace + slack).
4. **Cleanup:** `docker start server-t-2`; `wait_bucket_size trading 2` (30 s).

**Pass criteria:** drained server is gone within 10 s **and** cleanup succeeds.

---

## C. BFF lifecycle

### 17. `test_kill_bff_transparent_failover`

**Verifies:** nginx's `proxy_next_upstream` automatically routes around a dead BFF; client never sees an error (modulo the documented passive-check retry window).

**How:**
1. `require_baseline`.
2. `docker kill bff-t-1`.
3. Sleep 2 s (let nginx mark it down on the first failed connect).
4. Fire 10 trading echoes, count HTTP 200 responses.
5. **Cleanup:** `docker start bff-t-1`; wait up to 25 s for a `200` reply (BFF process up + nginx `fail_timeout` window expired).

**Pass criteria:** ≥ 8 of 10 echoes return 200. (1–2 failures are expected: nginx's `fail_timeout=5s` means it periodically retries the dead upstream, paying the 2 s connect-timeout for one request per cycle.)

### 18. `test_kill_bff_marked_critical_in_consul`

**Verifies:** Consul's HTTP health check catches the dead BFF.

**How:**
1. `require_baseline`.
2. `docker kill bff-t-1`.
3. Poll Consul for up to 8 s until the HTTP check on `bff-t-1` reports `critical`.
4. **Cleanup:** `docker start bff-t-1`; sleep 6 s for re-registration + nginx fail-window expiry.

**Pass criteria:** Consul flips the check within 8 s **and** cleanup runs.

---

## D. Broker resilience

### 19. `test_broker_restart_bootstraps_from_consul`

**Verifies:** when the broker process is restarted, it queries Consul on boot, pre-seeds its in-memory map, and BFF subscribers re-converge — no node has to re-announce.

**How:**
1. `require_baseline`.
2. Snapshot the count of `Consul bootstrap: [1-9]` lines in the broker log (call it `before`).
3. `docker restart broker`.
4. Sleep 6 s.
5. Count the same log pattern again (call it `after`).
6. Wait up to 20 s for the BFF's known-nodes view to reach ≥ 8 entries.

**Pass criteria:** `after > before` (a new non-zero bootstrap log fired post-restart) **and** the BFF view repopulates fully within 20 s.

---

## E. Scale-out

### 20. `test_scale_out_server_t_3`

**Verifies:** a brand-new backend brought up live (no compose edits) is picked up by both the broker and the routing BFF.

**How:**
1. `require_baseline`.
2. `./bin/compose --profile extra up -d server-t-3`.
3. Poll `GET /api/trading/servers` for up to 15 s for `server-t-3` to appear.
4. **Cleanup:** `compose --profile extra stop server-t-3` + `docker rm -f server-t-3`; wait_bucket_size trading 2.

**Pass criteria:** server-t-3 appears in the trading pool within 15 s **and** cleanup succeeds.

---

## Test framework primitives

Used internally by the test functions:

| Helper | Purpose |
|---|---|
| `wait_for TIMEOUT_S "desc" cmd...` | Run `cmd` repeatedly (every 0.5 s) until it returns 0 or `TIMEOUT_S` elapses. |
| `count_servers ROLE` | Returns the number of entries in `GET /api/{role}/servers`. |
| `wait_bucket_size ROLE N [TIMEOUT]` | Wait for `count_servers ROLE` to equal `N`. Default timeout 20 s. |
| `ensure_running NAME` | Recover a container — start it if stopped; recreate via compose if removed. |
| `restore_baseline` | Bring all 8 application containers back to running + warm up nginx upstream pools. Used after the most invasive failure paths. |
| `require_baseline` | Precondition guard called at the start of every destructive test. Waits up to 10 s for both buckets at size 2 before proceeding. |
| `fire_echoes_count_200 ROLE N` | Fire N echoes against `/api/{role}/echo`, return the count that got HTTP 200. |
| `fire_echoes_unique_servers ROLE N` | Fire N echoes, print the set of unique `serverId`s that handled them. |

## Conventions

- **Cleanup failure is test failure.** A destructive test that detected the property under test but couldn't restore baseline still returns non-zero — otherwise the next test starts with a polluted state and produces confusing errors.
- **Deterministic order.** Tests are listed in `TESTS_ALL` in `bin/test`; the suite executes that array sequentially. Don't rely on alphabetical / dictionary order.
- **No new dependencies.** The suite uses `curl` + `python3` (for JSON parsing) + the existing `bin/c` and `bin/compose` wrappers. No JUnit, no Testcontainers, no pytest.
- **Tolerant baseline.** A 10 s wait in `require_baseline` covers HealthWatcher reconnects after a kill cycle.
- **Time budgets.** Each individual test's wait windows are sized for podman on a laptop with no other heavy load. CI may need bumped timeouts.

## Adding a new test

1. Add a `test_xxx` bash function in `bin/test`.
2. Use `require_baseline` at the top if it's destructive.
3. Use the helpers above instead of raw `curl + sleep` loops.
4. If destructive, end with cleanup that's also a hard requirement (`|| { echo "cleanup failed"; return 1; }`).
5. Add the test name to the `TESTS_ALL=( ... )` array in the right group.
6. Add a section to this file in the same group with the same documentation shape.
