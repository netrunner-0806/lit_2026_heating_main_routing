package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NavGraphLimitTest {
    @Test void edgeAndVisibilityBudgetsFailBeforeUnboundedAllocation() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput().rect(1, "water", 0, 0, 20, 20).build(), ClearanceClass.SMALL);
        assertThrows(ComplexityLimitException.class, () -> new NavGraph(space, 300, 80, 4, 100_000));
        assertThrows(ComplexityLimitException.class, () -> new NavGraph(space, 300, 80, 100_000, 4));
    }

    @Test void failedDynamicVertexDoesNotCorruptGraphAndTruncateReleasesEdges() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput().build(), ClearanceClass.SMALL);
        NavGraph graph = new NavGraph(space, 300, 0, 2, 1000);
        var ex=ru.lct.heatnet.restrictions.Exemptions.NONE;
        graph.addVertex(new org.locationtech.jts.geom.Coordinate(0,0),ex,300,null);
        graph.addVertex(new org.locationtech.jts.geom.Coordinate(10,0),ex,300,null);
        assertThrows(ComplexityLimitException.class,()->graph.addVertex(new org.locationtech.jts.geom.Coordinate(20,0),ex,300,null));
        assertEquals(2,graph.size());assertEquals(1,graph.edgeCount());
        graph.truncate(1);graph.addVertex(new org.locationtech.jts.geom.Coordinate(30,0),ex,300,null);
        assertEquals(1,graph.edgeCount());
    }
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
        assertThrows(ComplexityLimitException.class, () -> space.navigationVertices(2));
        assertTrue(new NavGraph(space, 300, 80).staticCount() > 0);
    }
}
