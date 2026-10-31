# AI Prompts

### Prompt 1

Review CLAUDE.md file and compare it to the files in this project. Tell me where there are discrepancies and ask questions before we begin.

### Prompt 2

1. Maven
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

### Prompt 3

A. option 1, I forgot about that detail in spec
5/F. Comparator okay, but we need to make it easy to replace with a real rules engine should the need arise later
C. Yes if there are truly orders with no eligible supplier, then feasible is false
D. fair proposal

Not sure where the other letters went, that last list of questions was labeled confusingly

### Prompt 4

B. yes midpoint between is good
E. left pad

### Prompt 5

ServiceArea (unit)
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

### Prompt 6

Just installed Java and Maven. I need docker, but is it safer to just podman for licensing reasons?

### Prompt 7

CatalogLoaderTest
- categoriesAreTrimmedAndLowercased, serviceAreaIsParsedForEachSupplier - index based lookup is brittle. better to get by order id

RouteApiTest - good

RouteRequestValidatorTest - good

RouterServiceTest - good

ServiceAreaTest - good

### Prompt 8

1. Fine for MVP, especially since my thought is the desired post-MVP to track who won the tie most recently
2. Yes enhance the tests with real sample orders
3. Yes

And yes we should always be deterministic about the line item we want in the tests so update the lookups for names, scores, etc. by id

### Prompt 9

AI_PROMPTS.md
- I prefer each prompt in AI_PROMPTS.md to have it's own section. The ordered list renders confusingly when the prompt itself has numbers in it. Prefer a low profile header size like h3 or so

README.md
- Known limits and future work - let's treat this internally as a checklist of things that you and I can continue to improve within the time limit that I have for this project.
- We should work on logging, performance metrics instrumentation, performance tests, GH pages Mkdocs site, documentation, CLAUDE.md and skills to enforce standards in the project, code linting, at least in addition to what is already there. Add these and give a best guess estimate for impact and LOE for each item so we can tackle the highest impact, lowest effort items first

Project organization
- I want the project to make use of Java packages. The flat structure is messy to me.

Make these changes first for my ease of review and then I'll continue.

### Prompt 10

I have about 1.5 hours left.

More review:

RouterService
- not convinced that toResponse makes sense here. This adds logic to the service layer that could feasibly be used by other parts of this system that is tied to RouteController.
- a walkthrough by you would be welcome

### Prompt 11

1. Option B works. You're great at generating code so even if it touches a lot of tests I'm good. I want clean separation of concerns.
2. We'll see how long everything actually takes. You're faster than you think.

Back to the bitmasks. The latent bug makes this approach unfeasible when we need to handle incredibly large databases. Plus debugging that sounds like a beast. It's also more difficult to describe. I think another approach that is more human-readable would be better and easier to walk through. This block is not something I'm used to for example:

for (int i = 0; i < items.size(); i++) {
                if (supplier.categories().contains(items.get(i).category())) {
                    coverage |= 1 << i;
                }
            }

### Prompt 12

Start with logging

### Prompt 13

yes these are good. i was unfamiliar with the OutputCaptureExtension.class extension so that's cool. But I want to be opinionated about the implementation. I prefer that we use a logback with slf4j interface

### Prompt 14

Yes prevent fake log lines, write the test

rebuild container

yes take on linting
