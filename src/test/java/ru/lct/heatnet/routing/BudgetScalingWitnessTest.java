package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tiny real graph limits; these are mechanism witnesses, not large-scene benchmarks. */
class BudgetScalingWitnessTest {
    private NavGraph triangle(ComputeBudget budget) {
        var g=new NavGraph(new ObstacleSpace(new SyntheticInput().build(),ClearanceClass.SMALL),
                100,0,budget,new ComputeCancellation());
        g.addVertex(new Coordinate(0,0),Exemptions.NONE,100,null);
        g.addVertex(new Coordinate(1,0),Exemptions.NONE,100,null);
        g.addVertex(new Coordinate(0,1),Exemptions.NONE,100,null);
        return g;
    }
    @Test void onlyReachedEdgeLimitBenefitsFromDoubling() {
        var error=assertThrows(ComplexityLimitException.class,()->triangle(new ComputeBudget(10,3,100,100,10)));
        assertTrue(error.getMessage().contains("directed edges"));
        for(int factor:new int[]{2,3}) {
            var g=triangle(new ComputeBudget(10,3*factor,100,100,10));
            assertEquals(6,g.directedEdgeCount());assertEquals(3,g.size());
        }
    }
    @Test void onlyReachedNodeLimitBenefitsFromDoubling() {
        var error=assertThrows(ComplexityLimitException.class,()->triangle(new ComputeBudget(2,100,100,100,10)));
        assertTrue(error.getMessage().contains("nodes"));
        for(int factor:new int[]{2,3}) {
            var g=triangle(new ComputeBudget(2*factor,100,100,100,10));
            assertEquals(6,g.directedEdgeCount());assertEquals(3,g.size());
        }
    }
    @Test void onlyReachedVisibilityLimitBenefitsFromDoubling() {
        var error=assertThrows(ComplexityLimitException.class,()->triangle(new ComputeBudget(10,100,2,100,10)));
        assertTrue(error.getMessage().contains("visibility"));
        for(int factor:new int[]{2,3}) {
            var g=triangle(new ComputeBudget(10,100,2L*factor,100,10));
            assertEquals(6,g.directedEdgeCount());assertEquals(3,g.visibilityCheckCount());
        }
    }
}
