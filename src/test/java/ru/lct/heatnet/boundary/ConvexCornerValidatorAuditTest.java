package ru.lct.heatnet.boundary;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.output.OutputFeature;
import java.util.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ConvexCornerValidatorAuditTest {
    @ParameterizedTest @CsvSource({"300,300,1.575","400,500,1.685","800,4000,2.125","1400,16000,2.725"})
    void actualDuDistanceAtConvexCorner(int du,double flow,double required) {
        for(double delta:new double[]{-.01,0,.01}) {
            double a=Math.toRadians(195),r=required+delta,x=r*Math.cos(a),y=r*Math.sin(a),tx=-Math.sin(a),ty=Math.cos(a);
            double x0=x-.05*tx,y0=y-.05*ty,x1=x+.05*tx,y1=y+.05*ty;
            var in=new SyntheticInput().rect("park","park",0,0,10,10).point("cp",x0,y0,flow)
                    .line("net",du,x1,y1,x1-10,y1).build();
            List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("l","cp","root",flow,du,false,1,x0,y0,x1,y1),chamber("root",x1,y1,du)));
            fs.add(summary(fs,0));var report=validate(in,fs);
            assertEquals(delta<0,has(report,"CLEARANCE"),issues(report));
            if(delta>=0)assertTrue(report.isValid(),issues(report));
        }
    }
}
