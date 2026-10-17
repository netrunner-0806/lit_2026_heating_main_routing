package ru.lct.heatnet.depth;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.depth.DepthProfileOptimizerTest.*;

class ManyDepthCrossingsTest {
    @Test void elevenOverlappingUtilitiesAllParticipate() {
        List<VerticalObstacle> obstacles=new ArrayList<>();
        for(int i=0;i<10;i++)obstacles.add(gas(40,44,.18));
        obstacles.add(cable(40,44,.18));
        var r=run(100,obstacles,null,3.0);assertTrue(r.feasible);
        assertEquals(11,r.profile.decisions().get(0).options.size());
        assertEquals(2.02,r.profile.depthAt(42),1e-9,"cable top 2.7 - clearance .5 - height .18");
    }
    @Test void thirteenthPlateauCannotRemoveTheZeroExtraCostAllAboveProfile() {
        List<VerticalObstacle> obstacles=new ArrayList<>();
        for(int i=0;i<13;i++)obstacles.add(gas(30+30*i,34+30*i,.18));
        var r=run(430,obstacles,3.0,3.0);assertTrue(r.feasible);
        // Independent witness: above every gas at 2.42 m, ramps <= .1 in 26 m gaps.
        // Entire profile <=3 => Kdepth=1 everywhere, zero is the exact lower cost bound.
        assertEquals(0,r.extraCost,1e-8);
        for(int i=0;i<13;i++)assertEquals(2.42,r.profile.depthAt(32+30*i),1e-9);
    }
    @Test void excessiveCompleteEnumerationIsExplicitComplexityRatherThanIgnoredObjects() {
        List<VerticalObstacle> obstacles=new ArrayList<>();
        for(int i=0;i<17;i++)obstacles.add(gas(30+30*i,34+30*i,.18));
        var ex=assertThrows(ru.lct.heatnet.routing.ComplexityLimitException.class,()->run(550,obstacles,3.0,3.0));
        assertTrue(ex.getMessage().contains("65536"));
    }
}
