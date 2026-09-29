package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NavGraphSmokeTest {

    @Test
    void buildsGraphOnCorrectedDataset() throws Exception {
        Assumptions.assumeTrue(TestData.correctedAvailable());
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
        long t0 = System.currentTimeMillis();
        ObstacleSpace space = new ObstacleSpace(m, ClearanceClass.SMALL);
        long t1 = System.currentTimeMillis();
        NavGraph g = new NavGraph(space, 300, 80);
        long t2 = System.currentTimeMillis();
        System.out.printf("obstacles=%d vertices=%d (static) edges=%d; space %d ms, graph %d ms%n",
                space.obstacles().size(), g.staticCount(), g.edgeCount(), t1 - t0, t2 - t1);
        assertTrue(g.staticCount() > 100);
        assertTrue(g.edgeCount() > g.staticCount());
    }
}
