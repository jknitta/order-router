# Order Router

A Spring Boot service that routes a multi-item order to one or more suppliers. It reads `suppliers.csv` and `products.csv` at startup and exposes `POST /api/route`.

## Run

```
docker build -t order-router . && docker run --rm -p 8080:8080 order-router
```

`podman` works as a drop-in replacement for `docker`. The service listens on port 8080.

```
curl -s -X POST localhost:8080/api/route -H 'Content-Type: application/json' -d '{
  "order_id": "ORD-003", "customer_zip": "02130", "mail_order": true,
  "items": [{"product_code": "CP-STD-031", "quantity": 1}, {"product_code": "NB-COMP-039", "quantity": 1}]
}'
```

## Test

Needs Java 21 and Maven:

```
mvn test
```

`mvn test` also runs Checkstyle first (`checkstyle.xml`: imports, indentation, line length, naming) and fails on any violation. The tests cover ZIP parsing, CSV loading, validation, the routing rules and the HTTP contract. The three orders in `sample_orders.json` are run against the real CSVs.

## API

`POST /api/route` always returns HTTP 200. The `feasible` field says whether routing succeeded.

- Success: `{ "feasible": true, "routing": [ { supplier_id, supplier_name, items: [ { product_code, quantity, category, fulfillment_mode } ] } ] }`
- Failure: `{ "feasible": false, "errors": [ ... ] }`

Extra request fields (such as `priority` and `notes`) are ignored.

## Logging

SLF4J API with Logback (configured in `logback-spring.xml`). Each order logs one line:

```
Routed order_id=ORD-001 feasible=true suppliers=[SUP-0636] items=2 mail_order=false duration_ms=3
Rejected order_id=ORD-X feasible=false reason=validation error_count=1 duration_ms=0
```

`reason` is `validation` or `unroutable`. A search that runs out of time logs a warning with `reason=timeout`, and unreadable JSON logs a warning. The customer ZIP, product codes, error messages and request bodies are never logged, because together they can identify a patient's care.

## Metrics

`GET /actuator/metrics` lists them. The useful ones:

- `order.route` (timer, tag `outcome` = `routed`, `validation`, `unroutable` or `timeout`): count and latency of every order.
- `router.search.candidates`, `router.search.combinations`, `router.search.suppliers`: how much work the supplier search did per order. Watch `combinations`, since it is what grows with large orders.

Example: `curl localhost:8080/actuator/metrics/router.search.combinations`

## Performance

The supplier search picks the first category still uncovered and only tries suppliers that cover it. It skips groups it has already explored and stops when the remaining suppliers cannot cover what is left. It returns the same answers as trying every combination: a randomized test compares it with a brute-force reference on 400 random catalogs.

An opt-in suite runs synthetic worst cases (`mvn test -Pperf`; the normal build skips it). Its work budgets count search steps, which are deterministic, so they do not flake. Measured on the same made-up catalogs:

| Scenario | Exhaustive search | Branching search |
|---|---|---|
| 500 suppliers, 12 categories, 3 suppliers needed | 374,791 combinations | 6,266 steps |
| 500 suppliers, 16 categories, 4 suppliers needed | 84,980,042 combinations, 9.7 s | 486,775 steps, 0.25 s |
| 20,000 suppliers, 4 items | 28 ms | 34 ms |

The real CSVs never reach these cases: a supplier covering every category exists, so every order there resolves with one supplier in milliseconds. Timings are from a laptop and vary from run to run. The step counts do not.

## Search timeout

A search can still be made arbitrarily hard with different data, so it is capped as well.

A supplier search that runs past `router.search.timeout.duration` (default `PT2S`) stops itself, and the order returns `feasible: false` with "Order could not be routed within the time limit." Nothing partial is returned. It is counted as `order.route` outcome `timeout` and logged as a warning. Set `router.search.timeout.enabled=false` to let every search run to completion. Either setting can be overridden through the environment, for example `ROUTER_SEARCH_TIMEOUT_DURATION=PT500MS`.

## Security and PHI handling

This is a best-effort alignment with HIPAA's technical safeguards, not a compliance claim. A formal risk analysis is still required before this handles real patient data.

**What the service handles.** An order's `customer_zip`, product codes and `order_id` together can reveal what care a patient receives, so treat all three as PHI. The service is stateless: no database, no writes, nothing persisted. It reads two CSV files at startup that hold supplier and product data, not patient data.

**Logging (PHI-controlled).**
- Each order logs its `order_id`, outcome, supplier IDs, item count and duration. `order_id` is logged on purpose, as the key for tracing a decision. An order ID can be linked to a patient, so the logs must be treated as PHI: restrict access, set a retention period, and send them only to a log store covered by a BAA.
- Never logged: the ZIP, product codes, request bodies, error messages (rejected orders log only `error_count`) and exception messages. Unexpected errors log only the exception type and where it was thrown.
- Control characters in caller-supplied text are replaced before logging, so a caller cannot forge log lines.

**Input handling.** Every response to `/api/route`, including unexpected failures, has the documented shape, and the cause is never exposed to the caller. Fractional quantities (`1.9`, reported as "must be a whole number"), quantities beyond the integer range (including the total of duplicate lines for one product), null items, bad JSON and the wrong content type are rejected, not coerced. A whole number written with a decimal point (`2.0`) is accepted.

**Container.** Runs as a non-root user (uid 10001) and works with a read-only filesystem:

```
docker run --rm --read-only --tmpfs /tmp -p 8080:8080 order-router
```

Only `/actuator/health` and `/actuator/metrics` are exposed. Metrics hold counts and timings, never order data, but the platform should still keep `/actuator` internal. Dependabot (`.github/dependabot.yml`) opens weekly updates for Maven and base-image dependencies. To scan the built image: `trivy image order-router`.

**The platform must provide** (outside this service):
- TLS for traffic in transit.
- Authentication and authorization on `/api/route`. There is none in the service, so logs carry no caller identity.
- Network isolation, such as a private subnet, and rate limiting.
- Log retention, access control and audit.
- A BAA with the hosting and logging vendors.

**Known gaps.** There is no cap on order size. A pathological order is stopped by the search timeout (default 2 s) but can still use that much CPU per request, so rate limiting at the platform is still needed. The base image is pinned by tag, not digest. The image scan is documented but has not been run.

## Code layout

Dependencies run one way: `api` -> `routing` -> `catalog`.

- `api`: the HTTP edge. `RouteController`, the JSON types `RouteRequest` and `RouteResponse` (which convert to and from the router's types), and `RouteRequestValidator`.
- `routing`: the business rules. `RouterService` turns an `Order` into a `RoutingResult` (a `Plan`, a list of errors, or a timeout) and knows nothing about JSON. `SupplierSetRanker` is the swappable ranking.
- `catalog`: the CSV data. `CatalogLoader`, `Supplier`, and `ServiceArea` (ZIP parsing).

## How routing works

1. A supplier is eligible if it serves the customer's ZIP. When `mail_order` is true, a supplier with `can_mail_order? = y` is also eligible regardless of ZIP.
2. A supplier can only take items whose category it handles.
3. The router tries one supplier, then two, and so on, and stops at the first size that covers every item. That is the fewest shipments. Suppliers that can take exactly the same items are collapsed to the best-ranked one first, so the search depends on the number of distinct item groups, not on how many suppliers there are.
4. Among plans of that size, the best is chosen by: higher average supplier score, then more items fulfilled locally, then the lowest supplier IDs.
5. If a chosen supplier is not the only one that could take an item, the item goes to the higher-rated one, then a local one, then the lower ID.

The ranking in step 4 sits behind the `SupplierSetRanker` interface. The search also applies the same score, local, id order when it picks between suppliers that cover the same items (steps 3 and 5, `BEST_FIRST` in `RouterService`). A replacement ranker that changes those priorities has to change that order too.

## Decisions

- **"No ratings yet":** scored 5.5, the midpoint of the 1-10 scale, so new suppliers are neither favored nor buried. A score that is not a finite number (unparseable, `NaN` or `Infinity`) is treated the same way.
- **Partial feasibility:** all or nothing. If any item has no eligible supplier, the whole order is `feasible: false` and the errors name each unroutable item. The spec has no partial result.
- **Unknown product codes, quantity of zero or less, missing quantity:** the whole order fails with `feasible: false` and an error that names the product. The spec says the endpoint always returns 200, so these are not HTTP errors. Unreadable JSON is handled the same way.
- **Duplicate lines for one product:** quantities are summed into one item.
- **ZIP parsing:** `service_zips` is a comma-separated list where each token is a single ZIP or a range, and rows often mix both. A token that is not a 4- or 5-digit ZIP or range (for example `N/A` or a negative number) is ignored and the rest of the row still counts. `00100-99999` serves every ZIP.
- **Leading zeros:** the data lost them (`2164` is really `02164`). 4-digit ZIPs are left-padded with `0`, in supplier data and in the order's `customer_zip`. An order ZIP that is not 4 or 5 digits is invalid.
- **Local vs mail order:** a supplier is *local* if it serves the order's ZIP. With `mail_order = false`, every item is `local`. With `mail_order = true`, an item is `local` if its supplier serves the ZIP and `mail_order` otherwise. A local supplier is eligible even if its `can_mail_order?` is `n`. The 60 suppliers with `00100-99999` serve every ZIP, so they count as local everywhere.
- **Similar ratings:** only an exact tie on score counts, and then the local supplier wins. 7.0 vs 6.9 is not a tie.
- **Tie-breaking:** after score and local preference, the lowest supplier ID wins, compared as text. This is deterministic but favors early IDs.
- **Categories:** matched ignoring case and surrounding spaces, because the data mixes `CPAP` and `cpap`.
- **Duplicate product codes in `products.csv`:** the first row wins. The 5 duplicated codes repeat the same category.
- **Header typo:** `suppliers.csv` spells the name column `suplier_name`, and the loader reads it that way.

## Improvement checklist

Working list of what to tackle next, ordered by best-guess impact against effort (highest impact, lowest effort first). Impact and LOE are rough estimates and will change as we learn more.

| # | Done | Item | Impact | LOE | Why / scope |
|---|------|------|--------|-----|-------------|
| 1 | [x] | Request logging | Med-High | ~20 min | One structured line per routed order (order id, feasible, supplier ids, duration) plus a warning for each rejected order. The service has no logging today, so a bad answer cannot be traced. |
| 2 | [x] | Code linting | Med | ~30 min | Checkstyle (`checkstyle.xml`) runs on every build and fails it on violations. Chosen over google-java-format because that would reformat the whole codebase to a different style. |
| 3 | [~] | CLAUDE.md and skills to enforce standards | High | ~45 min | Update CLAUDE.md for the package layout and the dependency direction (`api` -> `routing` -> `catalog`). CLAUDE.md now records the layout and standards (done). Still open: a hook that blocks commits unless `mvn test` passes, and a skill for the red-green and prompt-logging steps. |
| 4 | [x] | Performance tests | High | ~1 hr | A test with a worst-case order (many distinct items, mail order on, about 500 eligible suppliers) and a time budget. It quantifies the search-cost risk below. |
| 5 | [x] | Search-cost guard | High | ~30 min | Done as a configurable timeout (see Search timeout, switchable by configuration) plus a faster branching search. No order-size cap and no greedy fallback, by decision. |
| 6 | [ ] | Clean Data initiative | High | ~45 min | Fix the source files instead of compensating in code: leading zeros, category casing, the `suplier_name` header, duplicate product rows, and inconsistent supplier IDs (14 look like `SUP-T001`). Then simplify the loader. |
| 7 | [x] | Metrics instrumentation | Med | ~40 min | Spring Boot Actuator and Micrometer: a timer on `/api/route` and counters for feasible and infeasible orders. |
| 8 | [ ] | Documentation | Med | ~45 min | OpenAPI description of `/api/route`, a short architecture note (packages and the search), and Javadoc on the public types. |
| 9 | [ ] | Supplier fairness | High (business) | ~1 hr | The lowest-ID tie-break always favors the same suppliers. Track which supplier won a tie most recently and rotate. Needs state, so persistence has to be decided. |
| 10 | [ ] | Similar ratings | Low | ~15 min | Exact-match rule today. Revisit if it proves too strict. |
| 11 | [ ] | Partial fulfillment | Med | ~1 hr | Return the routable items alongside the unroutable ones. The spec is all-or-nothing, so this goes beyond the MVP. |
| 12 | [ ] | GitHub Pages MkDocs site | Low-Med | ~1.25 hr | `mkdocs.yml`, content drawn from the README and #8, and a GitHub Actions publish workflow. Mostly presentation, so it comes last. |
| 13 | [x] | Hardening pass | High | ~45 min | Catch-all error handling, null and fractional input rejected, health-only Actuator, non-root container, Dependabot and the Security and PHI section above. |
| 14 | [ ] | External load tests (Gatling) | High | ~2 hr | Drive the running container over HTTP with Gatling, which the in-process suite cannot do. It would catch what step counts miss: thread-pool exhaustion, connection handling, GC pauses and memory growth, container CPU and memory limits, and the cost of JSON, logging and metrics under load. Scenarios: steady load, a spike, a soak run, and mixed outcomes (routed, validation failures, timeouts). Assertions on p95/p99 latency and error rate. The SLA numbers need to come from you, since none are defined yet. Run it from a separate Maven module or `perf/` folder so it never touches the normal build, and in CI against a freshly built image. |
