package com.synapse.orderrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapse.orderrouter.routing.RoutingResult.Routed;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Not part of the normal build; run with `mvn test -Pperf`. Work budgets are counts of combinations examined, which
 * are deterministic, so these do not flake the way wall-clock assertions do. Times are printed for information.
 */
@Tag("performance")
class SearchPerformanceTest {

    private record Run(RoutingResult result, long combinations, long millis) {
    }

    private static Run run(SyntheticCatalog synthetic, Order order, SearchTimeout timeout) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouterService service = new RouterService(synthetic.catalog(), new DefaultSupplierSetRanker(), registry,
                timeout);
        long start = System.nanoTime();
        RoutingResult result = service.route(order);
        long millis = (System.nanoTime() - start) / 1_000_000;
        DistributionSummary combinations = registry.get("router.search.combinations").summary();
        return new Run(result, (long) combinations.totalAmount(), millis);
    }

    private static void report(String scenario, Run run) {
        int suppliers = run.result() instanceof Routed routed ? routed.plan().suppliers().size() : 0;
        System.out.printf("PERF %-34s result=%-9s suppliers=%d combinations=%,d time=%d ms%n", scenario,
                run.result().getClass().getSimpleName(), suppliers, run.combinations(), run.millis());
    }

    @Test
    void s1SmallRealisticOrdersStayTrivial() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(100, 24, 1, 0.5, 1L);

        Run run = run(synthetic, synthetic.orderOfCategories(6), new SearchTimeout(false, Duration.ZERO));

        report("S1 100 suppliers, 6 items", run);
        assertThat(run.result()).isInstanceOf(Routed.class);
        assertThat(run.combinations()).isLessThan(10_000);
    }

    @Test
    void s2ManyProductsInFewCategories() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(200, 8, 5, 0.5, 2L);

        Run run = run(synthetic, synthetic.orderOfProducts(8, 5), new SearchTimeout(false, Duration.ZERO));

        report("S2 200 suppliers, 40 items/8 cats", run);
        assertThat(run.result()).isInstanceOf(Routed.class);
        assertThat(run.millis()).isLessThan(5_000);
    }

    @Test
    void s3AdversarialCoverNeedsAFarSmallerSearch() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(500, 24, 1, 0.12, 3L);

        Run run = run(synthetic, synthetic.orderOfCategories(12), new SearchTimeout(true, Duration.ofSeconds(10)));

        report("S3 500 suppliers, 12 cats, 3 needed", run);
        assertThat(run.result()).isInstanceOf(Routed.class);
        assertThat(((Routed) run.result()).plan().suppliers().size()).isEqualTo(3);
        assertThat(run.combinations()).isLessThan(50_000);
    }

    @Test
    void s3bAnOrderNeedingFourSuppliersStillFinishesQuickly() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(500, 24, 1, 0.12, 3L);

        Run run = run(synthetic, synthetic.orderOfCategories(16), new SearchTimeout(true, Duration.ofSeconds(10)));

        report("S3b 500 suppliers, 16 cats, 4 needed", run);
        assertThat(run.result()).isInstanceOf(Routed.class);
        assertThat(((Routed) run.result()).plan().suppliers().size()).isEqualTo(4);
        assertThat(run.combinations()).isLessThan(2_000_000);
        assertThat(run.millis()).isLessThan(2_000);
    }

    @Test
    void s4HugeSupplierPoolWithASmallOrder() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(20_000, 24, 1, 0.5, 4L);

        Run run = run(synthetic, synthetic.orderOfCategories(4), new SearchTimeout(false, Duration.ZERO));

        report("S4 20,000 suppliers, 4 items", run);
        assertThat(run.result()).isInstanceOf(Routed.class);
        assertThat(run.millis()).isLessThan(5_000);
    }

    @Test
    void s5ImpossiblyLargeSearchesAreCutOffByTheTimeout() {
        SyntheticCatalog synthetic = SyntheticCatalog.build(600, 24, 1, 0.12, 5L);
        long limitMillis = 100;

        Run run = run(synthetic, synthetic.orderOfCategories(24), new SearchTimeout(true,
                Duration.ofMillis(limitMillis)));

        report("S5 600 suppliers, 24 cats, 100 ms cap", run);
        assertThat(run.result()).isInstanceOf(RoutingResult.TimedOut.class);
        assertThat(run.millis()).isLessThan(2_000);
    }
}
