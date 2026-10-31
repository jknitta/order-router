# CLAUDE.md

## Project
Take-home assessment: a Multi-Supplier Order Router service. Hard cap of 4 hours of total work. Prefer a smaller, well-understood solution over a larger one. The author must be able to explain every line and decision in a live review.

Stack: Java Spring Boot

Inputs (provided): suppliers.csv, products.csv, sample_orders.json, and the original instructions document.

## Required deliverables
1. Working code, containerized (Dockerfile; runs with one documented command).
2. README.md: how to build, run, and test, plus a short decisions section.
3. AI_PROMPTS.md: the author's significant prompts, in order, verbatim.

## Prompt logging (important)
- Before acting on any substantive prompt from the user, append it VERBATIM to AI_PROMPTS.md as its own `### Prompt N` section (not a numbered list, which renders badly when prompts contain numbers). Skip trivial replies like "yes" or "continue".
- Never edit, reword, summarize, merge, or reorder earlier entries.
- Do not log your own responses, only the user's prompts.

## Working method
- Keep a working plan in PLAN.md (design decisions, implementation steps, status).
- Update PLAN.md as work proceeds. When the implementation deviates from the plan, add a one-line entry under "Deviations" (planned X, changed to Y, because Z).
- Start in plan mode. Propose a design and list open ambiguities BEFORE writing code. Wait for the user's decisions.
- Work in small, single-purpose commits with messages that explain why.
- Write tests for agreed behavior before or alongside the implementation. Cover risky paths and edge cases, not only the happy path.
- At each stopping point, update PLAN.md with where we are and what is next.

## Spec essentials
- Endpoint: POST /api/route. ALWAYS returns HTTP 200. The `feasible` field signals success.
- Success: { "feasible": true, "routing": [ { supplier_id, supplier_name, items: [ { product_code, quantity, category, fulfillment_mode } ] } ] }
- Failure: { "feasible": false, "errors": [ ...messages ] } (e.g. no line items, invalid customer_zip).
- Feasibility rules:
  - mail_order = false: only suppliers serving the customer's ZIP.
  - mail_order = true: any supplier with can_mail_order? = "y", regardless of ZIP.
  - A supplier must handle the product's category.
- Optimization priorities, in order:
  1. Feasibility
  2. Fewest shipments (consolidate with one supplier when possible)
  3. Higher customer_satisfaction_score
  4. Prefer local over mail-order when ratings are similar
- Data is messy: ZIPs may be explicit lists or ranges, scores may be "no ratings yet". Handle malformed values defensively.

## Decisions that must be made and documented in the README
- Treatment of "no ratings yet" suppliers
- Partial feasibility (some items unroutable): feasible false or partial result
- Unknown product codes
- ZIP range and list parsing, and malformed values
- Local vs mail-order choice and how fulfillment_mode is set when mail_order = true
- Tie-breaking rules
Ask the user to decide each one. Do not silently pick.

## Code quality bar
- Builds and runs from a clean clone with the documented command.
- No dead code, leftover scaffolding, or abstractions that cannot be justified.
- Keep it simple enough to explain out loud. Prefer clear over clever.
- Do not add features beyond the spec without asking.

## Before finishing
- Verify from a clean clone: build, run, tests pass.
- Distill PLAN.md into the README decisions section. Delete PLAN.md or move a cleaned version to docs/. Do not ship the raw working file.
- Remind the user to read the whole repo top to bottom and flag anything they could not explain.

## Architecture (as built)
- Packages depend in one direction only: `api` -> `routing` -> `catalog`.
  - `api`: `RouteController`, `RouteRequest` and `RouteResponse` (they own conversion to and from the router's types), `RouteRequestValidator`.
  - `routing`: `Order`, `RoutingResult` (`Routed` or `Unroutable`), `RouterService`, `Plan`, `SupplierSetRanker`. Knows nothing about JSON or HTTP.
  - `catalog`: CSV loading, `Supplier`, `ServiceArea` (ZIP parsing).
- Ranking sits behind `SupplierSetRanker` so it can be swapped for a rules engine. Do not inline it into the search.
- Package structure is a decision the author made. Do not flatten it or add cross-package dependencies without asking.

## Standards (enforced)
- `mvn test` runs Checkstyle first (`checkstyle.xml`: 4-space indent, 120 columns, import order, no unused imports). Fix violations in the code. Do not loosen the rules.
- Red-green: write failing tests, commit them, let the author review, then implement and commit green separately.
- PHI in logs: never log the customer ZIP, product codes of routed items, request bodies or exception messages. Caller-supplied text that is logged goes through `loggable()` in `RouteController`. `order_id` is logged on purpose and the README documents that logs are PHI-controlled.
- `POST /api/route` always returns HTTP 200 in the documented shape. Unexpected failures go through the catch-all handler, which never echoes the cause.
- The container runs as non-root. Only `/actuator/health` (and `metrics`, when added) is exposed. Do not expose other endpoints.
- Behavior changes update the README (decisions, security section, checklist) in the same change.
