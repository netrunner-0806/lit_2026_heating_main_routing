package ru.lct.heatnet.boundary;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

class OutputSchemaAuditTest {
    InputModel input=new SyntheticInput().line("net",300,-20,0,20,0).point("cp",0,50,10).build();
    List<OutputFeature> valid() {
        List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("line","cp","root",10,80,false,1,0,50,0,0),chamber("root",0,0,300)));
        fs.add(summary(fs,0));assertTrue(validate(input,fs).isValid());return fs;
    }
    @Test void fractionalRankIsRejected() { var fs=valid();fs.get(2).prop("rank",1.5);assertFalse(validate(input,fs).isValid()); }
    @Test void fractionalDiameterIsRejected() { var fs=valid();fs.get(0).prop("diameter",80.5);assertFalse(validate(input,fs).isValid()); }
    @Test void numericStringsAreNotJsonNumbers() { var fs=valid();fs.get(0).prop("flow_tph","10");assertFalse(validate(input,fs).isValid()); }
    @Test void planarDepthIsAnError() { var fs=valid();fs.get(0).prop("depth_start",3).prop("depth_end",3);assertFalse(validate(input,fs).isValid()); }
    @Test void unknownModeCannotSilentlyMeanPlanar() { var fs=valid();fs.get(2).prop("mode","typo");assertFalse(validate(input,fs).isValid()); }
    @Test void scoreErrorLargerThanFourDecimalRoundingIsRejected() {
        var fs=valid();fs.get(2).prop("score",((Number)fs.get(2).prop("score")).doubleValue()+.0008);assertFalse(validate(input,fs).isValid());
    }
    @Test void outputIdMustBeUniqueAcrossVariants() {
        var fs=valid();var copy=valid();for(var f:copy)f.prop("variant_id","v2");copy.get(2).prop("rank",2);
        fs.addAll(copy);assertFalse(validate(input,fs).isValid());
    }
    @Test void duplicatedUnconnectedConsumerIsRejected() {
        List<OutputFeature> fs=new ArrayList<>();double penalty=105_000_000;
        fs.add(summary(fs,0).prop("unconnected_oks_ids",Arrays.asList("cp","cp"))
                .prop("unconnected_penalty",penalty).prop("calculated_cost",penalty).prop("score",.7*penalty/25_000_000));
        assertFalse(validate(input,fs).isValid());
    }
    @Test void optionalModeAndDepthSummaryExtensionsAreNotRequiredByTheAnnex() {
        List<OutputFeature> fs=new ArrayList<>(Arrays.asList(depthLine("line","cp","root",10,80,false,1,3,3,0,50,0,0),chamber("root",0,0,300)));
        fs.add(summary(fs,0).prop("mode",null));
        assertTrue(validate(input,fs).isValid(),issues(validate(input,fs)));
    }
    @Test void oversizedDiameterIsDetectedEvenWithConsistentCost() {
        var fs=valid();fs.set(0,line("line","cp","root",10,100,false,1,0,50,0,0));fs.set(2,summary(fs.subList(0,2),0));
        assertTrue(has(validate(input,fs),"DU_OVERSIZED"));
    }
    @Test void rootChamberUsesAllIncidentExistingDiameters() {
        for(boolean reverse:new boolean[]{false,true}) {
            SyntheticInput b=new SyntheticInput().point("cp",0,50,10);
            if(reverse)b.line("large",800,0,0,20,0).line("small",300,-20,0,0,0);
            else b.line("small",300,-20,0,0,0).line("large",800,0,0,20,0);
            var in=b.build();
            List<OutputFeature> fs=new ArrayList<>(Arrays.asList(line("l","cp","root",10,80,false,1,0,50,0,0),chamber("root",0,0,800)));
            assertEquals(8_000_000,((Number)fs.get(1).prop("cost")).longValue());
            fs.add(summary(fs,0));assertTrue(validate(in,fs).isValid(),issues(validate(in,fs)));
            fs.set(1,chamber("root",0,0,300));fs.set(2,summary(fs.subList(0,2),0));
            assertFalse(validate(in,fs).isValid());
        }
    }
}
