package ru.lct.heatnet.boundary;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.output.OutputFeature;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;
class FilledExistingChamberTest {
    @Test void tenMetreRuleIncludesNewLinesAlreadyFillingExistingChamber() {
        var input=new SyntheticInput().line("net",300,-100,0,100,0).chamber("existing",0,0)
                .point("a",-30,30,10).point("b",-30,-30,10).point("c",30,30,10).build();
        List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("a1","a","existing",10,80,false,1,-30,30,0,0),
                line("b1","b","existing",10,80,false,1,-30,-30,0,0),line("c1","c","new",10,80,false,1,30,30,5,0),
                chamber("new",5,0,300)));
        fs.add(summary(fs,2));var report=validate(input,fs);
        assertTrue(report.isValid(),issues(report));
    }
}
