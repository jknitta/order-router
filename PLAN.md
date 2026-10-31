# PLAN

Status: planning. No code written. Waiting on the open items below.

## Setup decisions
- Maven, Java 21, Spring Boot.
- CSVs are copied into `src/main/resources` so the Docker image is self-contained.
- Dev flow: red-green-refactor. Tests are written first and reviewed by the author before implementation.
- Sample orders become integration tests.

## Data findings
- `suppliers.csv` header has a typo: `suplier_name`. Score column is `customer_satisfaction_score`, mail column is `can_mail_order?`.
- `service_zips` is a comma-separated list of tokens. Each token is a single ZIP or a range. Many rows mix both.
- 500 rows have 4-digit ZIPs (leading zero lost). Left-pad to 5 digits.
- `00100-99999` appears on 60 suppliers (serves everywhere).
- Categories vary in case (`CPAP`/`cpap`, `CPM machine`/`cpm machine`) in both files. Match trimmed and case-insensitive.
- `products.csv` has 1,200 rows, 1,195 unique codes (the PDF says 200). The 5 duplicated codes repeat the same category.
- 184 of 1,100 suppliers have "no ratings yet". The 916 numeric scores have mean 7.1, median 7.0, range 2.6-10.
- The PDF success example (SUP-005 for ZIP 10015) does not match the data. Treat it as illustrative.

## Decisions made
| # | Topic | Decision |
|---|-------|----------|
| 1 | Unknown product code | Whole order fails with HTTP 400 (see open item A) |
| 2 | Quantity <= 0 | HTTP 400 (see open item A) |
| 3 | Duplicate lines in an order | Quantities are summed |
| 4 | 4-digit ZIPs in supplier data | Left-pad with 0 |
| 5 | Local supplier | Supplier serves the order ZIP. Applies whether or not `mail_order` is true |
| 6 | `mail_order = true` | Suppliers serving the ZIP count as `local`. Other suppliers with `can_mail_order? = y` count as `mail_order` |
| 7 | Rating "similar" | Exact tie on score only |
| 8 | Final tie-break | Lowest `supplier_id` |
| 9 | Fewest shipments | Minimum number of suppliers (exhaustive search, not greedy) |

## Open items
- A. HTTP status for validation errors: spec says POST /api/route always returns 200, decision says 400.
- B. "No ratings yet" score value (author leans neutral).
- C. Order with an item no eligible supplier can handle: `feasible: false` with errors (recommended).
- D. Ordering among equally small supplier sets.
- E. Order `customer_zip` handling when 4 digits.
- F. "Rules engine": plain comparator vs. framework.

## Implementation steps
1. Scaffold: Maven project, Dockerfile, CSVs into resources, `.gitignore` for `target/`.
2. Tests first (reviewed by author): ZIP parsing, category normalization, candidate selection, set selection, API contract.
3. Implement until green.
4. Refactor.
5. README, final clean-clone verification.

## Deviations
(none yet)

## Future initiatives
- **Clean Data initiative:** normalize `suppliers.csv` and `products.csv` at the source (ZIP leading zeros, category casing, header typo, duplicate product rows) so the router does not need to compensate.
- **Supplier fairness:** the tie-break is lowest `supplier_id` for simplicity. This favors early IDs. A later improvement should track which suppliers were selected most recently and rotate. Suppliers need to be happy in the network as much as customers.
- **"Similar" ratings:** currently an exact match. Revisit if it proves too strict.
