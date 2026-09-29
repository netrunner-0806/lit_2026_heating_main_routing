package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NavGraphLimitTest {
    @Test void enormousExtentRejectedBeforeCollectingVertices() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput()
                .rect(1, "oks", 0, 0, 10, 10)
                .rect(2, "oks", 1_000_000, 1_000_000, 1_000_010, 1_000_010).build(), ClearanceClass.SMALL);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new NavGraph(space, 300, 80));
            assertTrue(ex.getMessage().contains("Navigation grid exceeds"));
        });
    }

    @Test void gridBoundIsInclusiveAndOverflowSafe() {
        assertEquals(100_000, NavGraph.checkedGridSize(new Envelope(0, 397, 0, 247), 1));
        assertThrows(IllegalArgumentException.class, () -> NavGraph.checkedGridSize(new Envelope(0, 398, 0, 247), 1));
        assertThrows(IllegalArgumentException.class, () -> NavGraph.checkedGridSize(new Envelope(-1e200, 1e200, -1e200, 1e200), 80));
        assertThrows(IllegalArgumentException.class, () -> NavGraph.checkedGridSize(new Envelope(1e200, 1e200, 1e200, 1e200), 80));
    }

    @Test void obstacleVertexCollectionIsBoundedWithoutGrid() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput().rect(1, "oks", 0, 0, 20, 20).build(), ClearanceClass.SMALL);
        assertThrows(IllegalArgumentException.class, () -> space.navigationVertices(2));
        assertTrue(new NavGraph(space, 300, 80).staticCount() > 0);
    }
}
