package ru.lct.heatnet.property;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.testkit.InputGeoJson;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays one random scene: mvn -o test -Dtest=RandomCaseReplayTest -Dheatnet.replay.seed=12345 [-Dheatnet.replay.depth=true].
 * Also writes the scene to target/property-replay/seed-N.geojson for inspection in the UI.
 */
class RandomCaseReplayTest {

    @Test
    void replaySeed() throws Exception {
        String s = System.getProperty("heatnet.replay.seed");
        assumeTrue(s != null && !s.isEmpty(), "no -Dheatnet.replay.seed given");
        long seed = Long.parseLong(s.trim());
        boolean depth = Boolean.getBoolean("heatnet.replay.depth");
        RandomScene.Scene scene = RandomScene.generate(seed);
        Path out = Paths.get("target", "property-replay", "seed-" + seed + ".geojson");
        InputGeoJson.write(scene.input, out);
        System.out.println("replaying " + scene.description + " -> " + out);
        Invariants.check(scene, depth);
    }
}
