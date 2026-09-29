package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class ApproachPlannerTest {

    @Test
    void finalSegmentGoesFromNearestBoundaryPointToThePoint() {
        // building x 100..200, y 100..140; point inside at (150, 110): nearest wall is y=100
        InputModel in = new SyntheticInput().rect(1, "oks", 100, 100, 200, 140).point("p", 150, 110, 10).build();
        ObstacleSpace space = new ObstacleSpace(in, ClearanceClass.SMALL);
        List<Approach> aps = new ApproachPlanner(space).plan(in.connectionPoints().get(0));
        assertFalse(aps.isEmpty());
        Approach a = aps.get(0);
        assertTrue(a.nearestBoundary());
        assertEquals(c(150, 100).x, a.boundaryPoint().x, 1e-6);
        assertEquals(c(150, 100).y, a.boundaryPoint().y, 1e-6);
        assertEquals(c(150, 100).x, a.freePoint().x, 1e-6);
        assertTrue(a.freePoint().y < c(150, 100).y - 5.685, "free point is outside the clearance zone");
        assertTrue(a.freePoint().y > c(150, 100).y - 6.5);
        assertTrue(a.hasFinalSegment());
        assertEquals(a.freePoint().distance(a.target()), a.finalLength(), 1e-9);
        assertEquals(in.restrictions().get(0).id(), a.ownPolygon().id());
    }

    @Test
    void fallsBackToAnotherWallWhenTheNearestExitIsBlocked() {
        // a second building 6 m below the first blocks the nearest exit (y = 100 wall): 6 m gap < 2 * 5.685
        InputModel in = new SyntheticInput()
                .rect(1, "oks", 100, 100, 200, 140)
                .rect(2, "oks", 100, 80, 200, 94)
                .point("p", 150, 110, 10).build();
        ObstacleSpace space = new ObstacleSpace(in, ClearanceClass.SMALL);
        List<Approach> aps = new ApproachPlanner(space).plan(in.connectionPoints().get(0));
        assertFalse(aps.isEmpty());
        Approach a = aps.get(0);
        assertFalse(a.nearestBoundary());
    }

    @Test
    void pointOutsideAnyPolygonNeedsNoFinalSegment() {
        InputModel in = new SyntheticInput().rect(1, "oks", 100, 100, 200, 140).point("p", 150, 50, 10).build();
        ObstacleSpace space = new ObstacleSpace(in, ClearanceClass.SMALL);
        List<Approach> aps = new ApproachPlanner(space).plan(in.connectionPoints().get(0));
        assertEquals(1, aps.size());
        assertFalse(aps.get(0).hasFinalSegment());
        assertNull(aps.get(0).ownPolygon());
    }
}
