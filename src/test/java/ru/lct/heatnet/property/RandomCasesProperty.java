package ru.lct.heatnet.property;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

/**
 * Property-based tests (phase 8, jqwik): for random small scenes, IF the solver returns a variant it must satisfy
 * every output invariant ({@link Invariants}) — validator, finiteness, references, tree structure, cost arithmetic.
 * Unconnected consumers are allowed. The jqwik seed is fixed so the sequence of scene seeds is reproducible;
 * a failing scene seed is printed and its input saved under target/property-failures.
 */
class RandomCasesProperty {

    @Provide
    Arbitrary<Long> sceneSeeds() {
        return Arbitraries.longs().between(1L, 4_000_000_000L);
    }

    @Property(tries = 60, seed = "20260929", shrinking = ShrinkingMode.OFF)
    void everyProducedPlanarVariantIsValid(@ForAll("sceneSeeds") long seed) {
        Invariants.check(RandomScene.generate(seed), false);
    }

    @Property(tries = 25, seed = "20260930", shrinking = ShrinkingMode.OFF)
    void everyProducedDepthVariantIsValid(@ForAll("sceneSeeds") long seed) {
        Invariants.check(RandomScene.generate(seed), true);
    }
}
