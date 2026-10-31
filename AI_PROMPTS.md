# AI Prompts

1. Review CLAUDE.md file and compare it to the files in this project. Tell me where there are discrepancies and ask questions before we begin.

2. 1. Maven
2. 21
3. Yes both
4. This feels like a pure business decision and my tendency to want to give newcomers a shot leads me to want to treat them as neutral. Happy to discuss the pros and cons of this approach
5. We'll have to test this, but the wording of the prompt makes it sound like there may be some combination of orders that are not able to be fulfilled by a single supplier so we may have to use multiple suppliers to fulfill some orders. That makes me think that we'll need to apply a rules engine to provide the best possilbe experience see "Routing Logic Properties" from the instructions
6. Meaning caller provides an unknown product code? Yes this is a hTTP 400, fail the whole order
7. Not sure what you mean "skip the bad token", if the only malformation of ZIPs is leading zeroes, then those are valid and they shoudln't be skipped. BTW, let's make a note in a future initiatives section of the PLAN.md that we want to have a "Clean Data" Initiative.
8. yes
9. are they legit zip codes if we left pad with 0s?
10. what's your understanding of a "local" supplier? my take is that mail_order=false BUT they service the area code of the order = LOCAL, so in other words I agree with your recommendation.
11. Ratings should be a deterministic point system. It should only be an exact match for simplicity now. We can refactor later if we find there's an issue
12. First iteration should be lowest supplier id, but I think a later improvement should track which suppliers were selected most recently. Just make that last bit a note that we're making a tradeoff for simplicity now with thought given to supplier fairness. Suppliers need to be happy with being part of our network just as much as customers do.
13. i lean minimum, but define "greedy"
14. yes 400 for non-positive, and duplicate lines become additive
15. absolutely. our dev flow is going to be red-green-refactor so tests first that I review and then implmentation

3. A. option 1, I forgot about that detail in spec
5/F. Comparator okay, but we need to make it easy to replace with a real rules engine should the need arise later
C. Yes if there are truly orders with no eligible supplier, then feasible is false
D. fair proposal

Not sure where the other letters went, that last list of questions was labeled confusingly

4. B. yes midpoint between is good
E. left pad

5. ServiceArea (unit)
- If there's a negative zip, that should also be ignored

CatalogLoader - good

Validation (all return HTTP 200, feasible: false)
- Wnat to clarify your understanding. We can validate up front with this validator, but some of the feasibility will rely on the results of the routing analysis.
- I also want to ensure that we're on the same page. The API spec does not leave room for partial fulfillment. It looks like a binary all or nothing to me. I think it would be another future enhancement note to handle partial order fulfillment, but this doesn't look like the scope of MVP.
- If we're on the same page, these tests look good.

Routing - good

API and integration
- 39. we already decided that it will be 200 with feasible: false. The error reason will describe why

Good set to start. We'll see if we need to add coverage as we go along.

6. Just installed Java and Maven. I need docker, but is it safer to just podman for licensing reasons?

7. CatalogLoaderTest
- categoriesAreTrimmedAndLowercased, serviceAreaIsParsedForEachSupplier - index based lookup is brittle. better to get by order id

RouteApiTest - good

RouteRequestValidatorTest - good

RouterServiceTest - good

ServiceAreaTest - good
