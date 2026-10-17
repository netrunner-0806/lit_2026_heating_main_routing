package ru.lct.heatnet.boundary;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.*;
import static ru.lct.heatnet.SyntheticInput.c;
import static org.junit.jupiter.api.Assertions.*;

class ConvexCornerClearanceTest {
    @ParameterizedTest @CsvSource({"SMALL,1.685", "MEDIUM,2.125", "LARGE,2.725"})
    void exactCornerDistanceNotInscribedBuffer(ClearanceClass cls,double required) {
        // Independent Table 2 clearance 1 m + half Table 1 pair width: DU400/800/1400.
        // At 15 degrees between buffer vertices, the chord cuts inside the true circle.
        for (String type:new String[]{"park","water","railway","social_area","prohibited_site"}) {
            ObstacleSpace space=new ObstacleSpace(new SyntheticInput().rect("p",type,0,0,10,10).build(),cls);
            for (double delta:new double[]{-.01,0,.01}) {
                double angle=Math.toRadians(195),r=required+delta;
                double x=r*Math.cos(angle),y=r*Math.sin(angle),tx=-Math.sin(angle),ty=Math.cos(angle);
                assertEquals(delta>=0,space.check(c(x-.05*tx,y-.05*ty),c(x+.05*tx,y+.05*ty)).valid(),type+" "+delta);
                assertEquals(delta<0,space.insideForbidden(c(x,y)),type+" point "+delta);
            }
        }
    }
}
