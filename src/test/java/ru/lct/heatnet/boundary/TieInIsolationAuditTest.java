package ru.lct.heatnet.boundary;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.restrictions.ClearanceClass;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;
class TieInIsolationAuditTest {
    @Test void neighboringLineHasNoTieInExemption() {
        var in=new SyntheticInput().line("A",300,-10,0,10,0).line("B",300,-10,-.6,10,-.6).point("cp",0,4,1).build();
        // Independent geometry oracle: the nearest approach-to-B distance is .6m,
        // while Table 2 + the Table 1 widths require 1.0 + .400/2 + 1.150/2 = 1.775m.
        var approach=ru.lct.heatnet.geo.GeometryUtils.line(c(0,4),c(0,0));
        assertEquals(.6,approach.distance(in.networkLines().get(1).line()),1e-9);
        assertTrue(.6 < 1.0+.400/2+1.150/2);
        var fs=new ArrayList<>(Arrays.asList(line("new","cp","root",1,50,false,1,0,4,0,0),chamber("root",0,0,300)));
        fs.add(summary(fs,0));
        assertTrue(has(validate(in,fs),"CLEARANCE"),issues(validate(in,fs)));
    }
    @Test void routingExemptionDoesNotHideNeighbor() {
        var in=new SyntheticInput().line("A",300,-10,0,10,0).line("B",300,-10,-.6,10,-.6).point("cp",0,4,1).build();
        var r=new SolverContext(in,SolverConfig.defaults()).resources(ClearanceClass.SMALL);
        var tie=r.tieIns.stream().filter(t->t.point().distance(c(0,0))<1e-6).findFirst().orElseThrow();
        assertFalse(r.space.check(c(0,4),c(0,0),r.graph.exemptions(tie.vertex())).valid());
    }
    @Test void isolatedChamberIsNotARootOrSnapCandidate() {
        var in=new SyntheticInput().line("A",300,-10,0,10,0).chamber("orphan",0,30).point("cp",0,40,1).build();
        assertTrue(new ExistingNetworkIndex(in).usableChambersNear(c(0,30)).isEmpty());
        var fs=new ArrayList<>(Collections.singletonList(line("new","cp","orphan",1,50,false,1,0,40,0,30)));
        fs.add(summary(fs,1));
        assertTrue(has(validate(in,fs),"ISOLATED_CHAMBER"),issues(validate(in,fs)));
    }
    @Test void incidentLinesAtSameNodeRemainLegal() {
        var in=new SyntheticInput().line("A",300,-10,0,0,0).line("B",300,0,0,10,0).point("cp",0,4,1).build();
        var fs=new ArrayList<>(Arrays.asList(line("new","cp","root",1,50,false,1,0,4,0,0),chamber("root",0,0,300)));
        fs.add(summary(fs,0));assertTrue(validate(in,fs).isValid(),issues(validate(in,fs)));
    }
    @Test void isolatedNearbyChamberDoesNotSuppressLegalTieIn() {
        var in=new SyntheticInput().line("A",300,-10,0,10,0).chamber("orphan",0,2).point("cp",0,20,1).build();
        var cfg=SolverConfig.defaults();cfg.improve=false;
        var result=new HeatnetSolver(cfg,new ru.lct.heatnet.validation.SolverVariantValidator(in))
                .solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        assertEquals(1,result.variants().get(0).connectedCount());
        assertTrue(result.variants().get(0).network().roots().stream().allMatch(n->n.coord().distance(c(0,2))>.5));
        assertTrue(result.diagnostics().messages().stream().anyMatch(m->"ISOLATED_CHAMBER".equals(m.getCode()) && m.getText().contains("orphan")));
    }
    @Test void existingChamberAtIncidentLinesRemainsLegal() {
        var in=new SyntheticInput().line("A",300,-10,0,0,0).line("B",300,0,0,10,0).chamber("root",0,0).point("cp",0,4,1).build();
        var fs=new ArrayList<>(Collections.singletonList(line("new","cp","root",1,50,false,1,0,4,0,0)));
        fs.add(summary(fs,1));assertTrue(validate(in,fs).isValid(),issues(validate(in,fs)));
    }

}
