package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveLimitDiagnosticTest {
    @Test void wideTerminalBudgetIsExplicitComplexityNotNoPath() {
        var space=new ru.lct.heatnet.restrictions.ObstacleSpace(new ru.lct.heatnet.SyntheticInput().build(),ru.lct.heatnet.restrictions.ClearanceClass.SMALL);
        NavGraph graph=new NavGraph(space,0,0);
        for(int i=0;i<20_001;i++)graph.addVertex(new org.locationtech.jts.geom.Coordinate(i*10,0),ru.lct.heatnet.restrictions.Exemptions.NONE,0,null);
        var ex=assertThrows(ComplexityLimitException.class,()->AdaptiveRouting.wideTerminal(null,graph));
        assertTrue(ex.getMessage().startsWith("COMPLEXITY_LIMIT:"));
        assertEquals(20_001,graph.size());
    }
}
