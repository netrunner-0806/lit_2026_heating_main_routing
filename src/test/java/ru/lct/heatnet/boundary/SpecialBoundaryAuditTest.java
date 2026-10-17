package ru.lct.heatnet.boundary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.output.OutputFeature;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

class SpecialBoundaryAuditTest {
    @ParameterizedTest @ValueSource(strings={"road","tram_tracks","gas_pipeline","power_cable","heat_network"})
    void oversizedSpecialSectionIsAnError(String type) {
        SyntheticInput b=new SyntheticInput().line("net",300,-20,0,20,0).point("cp",0,100,10);
        boolean polygon=type.equals("road")||type.equals("tram_tracks");
        double k=type.equals("road")?1.6:type.equals("tram_tracks")?1.75:type.equals("gas_pipeline")?1.25:type.equals("power_cable")?1.15:1.05;
        if(polygon)b.rect("obstacle",type,-20,45,20,55);
        else if(type.equals("heat_network"))b.line("crossed",300,-20,50,20,50);
        else b.polyline("obstacle",type,-20,50,20,50);
        var input=b.build();
        List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("a","cp","t1",10,80,false,1,0,100,0,60),
                line("b","t1","t2",10,80,true,k,0,60,0,40),line("c","t2","root",10,80,false,1,0,40,0,0),
                tn("t1",0,60),tn("t2",0,40),chamber("root",0,0,300)));
        fs.add(summary(fs,0));var r=validate(input,fs);
        assertFalse(r.isValid(),issues(r));
        assertTrue(r.getIssues().stream().anyMatch(i->i.getCode().equals("SECTION_BOUNDARY_NOT_SPLIT")&&i.getSeverity()==ru.lct.heatnet.validation.ValidationIssue.Severity.ERROR),issues(r));
    }
    @Test void gasWithinRoadMustSplitEvenWhenMaximumCoefficientDoesNotChange() {
        var input=new SyntheticInput().line("net",300,-20,0,20,0).point("cp",0,100,10)
                .rect("road","road",-20,45,20,55).polyline("gas","gas_pipeline",-20,50,20,50).build();
        List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("a","cp","t1",10,80,false,1,0,100,0,58),
                line("b","t1","t2",10,80,true,1.6,0,58,0,42),line("c","t2","root",10,80,false,1,0,42,0,0),
                tn("t1",0,58),tn("t2",0,42),chamber("root",0,0,300)));
        fs.add(summary(fs,0));assertFalse(validate(input,fs).isValid());
    }
}
