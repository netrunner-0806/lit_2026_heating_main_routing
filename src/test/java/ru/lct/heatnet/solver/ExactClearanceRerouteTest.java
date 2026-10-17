package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ExactClearanceRerouteTest {
    @Test void actualDu400CornerViolationIsReroutedInsteadOfDroppingEveryVariant() {
        double angle=Math.toRadians(195),x=1.65*Math.cos(angle),y=1.65*Math.sin(angle),tx=-Math.sin(angle),ty=Math.cos(angle);
        double cx=x-30*tx,cy=y-30*ty,rx=x+30*tx,ry=y+30*ty;
        var in=new SyntheticInput().line("net",400,rx,ry,rx,ry-1).chamber("ch",rx,ry).point("cp",cx,cy,900)
                .rect("park","park",0,0,10,10).build();
        var validator=new SolverVariantValidator(in);
        var result=new HeatnetSolver(SolverConfig.defaults(),validator).solve(in,
                Collections.singletonList(HeatnetSolver.defaultStrategies().get(0)),null);
        assertFalse(result.variants().isEmpty(),result.diagnostics().messages().toString());
        assertEquals(1,result.variants().get(0).connectedCount());
        assertTrue(result.diagnostics().messages().stream().anyMatch(m->m.getCode().equals("EXACT_CLEARANCE_RETRY")),result.diagnostics().messages().toString());
        assertEquals(Collections.emptyList(),validator.validate(result.variants().get(0)));
        // Independent exact-distance check on original polygon and actual Table 1 width (DU400=1.37).
        for(var link:result.variants().get(0).network().links()) {
            var line=new org.locationtech.jts.geom.GeometryFactory().createLineString(link.coords().toArray(new org.locationtech.jts.geom.Coordinate[0]));
            assertTrue(line.distance(in.restrictions().get(0).geometry())>=1+1.37/2-.001);
        }
    }
}
