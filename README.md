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

The tests cover ZIP parsing, CSV loading, validation, the routing rules and the HTTP contract. The three orders in `sample_orders.json` are run against the real CSVs.

## API

`POST /api/route` always returns HTTP 200. The `feasible` field says whether routing succeeded.

- Success: `{ "feasible": true, "routing": [ { supplier_id, supplier_name, items: [ { product_code, quantity, category, fulfillment_mode } ] } ] }`
- Failure: `{ "feasible": false, "errors": [ ... ] }`

Extra request fields (such as `priority` and `notes`) are ignored.

## How routing works

1. A supplier is eligible if it serves the customer's ZIP. When `mail_order` is true, a supplier with `can_mail_order? = y` is also eligible regardless of ZIP.
2. A supplier can only take items whose category it handles.
3. The router tries one supplier, then two, and so on, and stops at the first size that covers every item. That is the fewest shipments.
4. Among plans of that size, the best is chosen by: higher average supplier score, then more items fulfilled locally, then the lowest supplier IDs.
5. If a chosen supplier is not the only one that could take an item, the item goes to the higher-rated one, then a local one, then the lower ID.

The ranking in step 4 sits behind the `SupplierSetRanker` interface, so it can be replaced (for example by a rules engine) without touching the search.

## Decisions

- **"No ratings yet":** scored 5.5, the midpoint of the 1-10 scale, so new suppliers are neither favored nor buried. A score that cannot be parsed is treated the same way.
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
| 1 | [ ] | Request logging | Med-High | ~20 min | One structured line per routed order (order id, feasible, supplier ids, duration) plus a warning for each rejected order. The service has no logging today, so a bad answer cannot be traced. |
| 2 | [ ] | Code linting | Med | ~30 min | Spotless (google-java-format) and unused-import checks wired into `mvn verify`, so style cannot drift. |
| 3 | [ ] | CLAUDE.md and skills to enforce standards | High | ~45 min | Update CLAUDE.md for the package layout and the dependency direction (`api` -> `routing` -> `catalog`). Add a hook that blocks commits unless `mvn verify` passes, and a skill for the red-green-refactor and prompt-logging steps. |
| 4 | [ ] | Performance tests | High | ~1 hr | A test with a worst-case order (many distinct items, mail order on, about 500 eligible suppliers) and a time budget. It quantifies the search-cost risk below. |
| 5 | [ ] | Search-cost guard | High | ~30 min | Exhaustive set search grows quickly with distinct items. Cap distinct items per order or fall back to greedy past a limit. Depends on #4 for the numbers, and the cap is new behavior, so it needs a decision first. |
| 6 | [ ] | Clean Data initiative | High | ~45 min | Fix the source files instead of compensating in code: leading zeros, category casing, the `suplier_name` header, duplicate product rows, and inconsistent supplier IDs (14 look like `SUP-T001`). Then simplify the loader. |
| 7 | [ ] | Metrics instrumentation | Med | ~40 min | Spring Boot Actuator and Micrometer: a timer on `/api/route` and counters for feasible and infeasible orders. |
| 8 | [ ] | Documentation | Med | ~45 min | OpenAPI description of `/api/route`, a short architecture note (packages and the search), and Javadoc on the public types. |
| 9 | [ ] | Supplier fairness | High (business) | ~1 hr | The lowest-ID tie-break always favors the same suppliers. Track which supplier won a tie most recently and rotate. Needs state, so persistence has to be decided. |
| 10 | [ ] | Similar ratings | Low | ~15 min | Exact-match rule today. Revisit if it proves too strict. |
| 11 | [ ] | Partial fulfillment | Med | ~1 hr | Return the routable items alongside the unroutable ones. The spec is all-or-nothing, so this goes beyond the MVP. |
| 12 | [ ] | GitHub Pages MkDocs site | Low-Med | ~1.25 hr | `mkdocs.yml`, content drawn from the README and #8, and a GitHub Actions publish workflow. Mostly presentation, so it comes last. |
