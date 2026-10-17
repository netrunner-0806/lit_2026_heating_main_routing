package ru.lct.heatnet.oracle;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.oracle.StraightCrossingOracle.*;

class IndependentCrossingAuditTest {
    List<Piece> gas(){return new ArrayList<>(Arrays.asList(new Piece("a",-30,-2,80,10,1),new Piece("b",-2,2,80,10,1.25),new Piece("c",2,120,80,10,1)));}
    List<String> check(List<ObjectShape> obstacles,List<Piece> pieces){return StraightCrossingOracle.check(-30,120,10,obstacles,pieces);}
    @Test void bentGasAndLShapedRoadFailIndependentExactDistance() {
        assertTrue(check(List.of(new ObjectShape("gas_pipeline",false,0,-60,0,1,80,1,80,60)),gas()).contains("CLEARANCE"));
        for(String type:List.of("road","tram_tracks")) {
            var road=new ObjectShape(type,true,-2,-60,2,-60,2,1,80,1,80,5,-2,5,-2,-60);
            var fs=List.of(new Piece("a",-30,-5,80,10,1),new Piece("b",-5,5,80,10,type.equals("road")?1.6:1.75),new Piece("c",5,120,80,10,1));
            assertTrue(check(List.of(road),fs).contains("CLEARANCE"));
        }
    }
    @Test void perpendicularObliqueAndDistantBendRemainValid() {
        for(ObjectShape shape:List.of(new ObjectShape("gas_pipeline",false,0,-60,0,60),new ObjectShape("gas_pipeline",false,-60,-60,60,60),
                new ObjectShape("gas_pipeline",false,0,-60,0,10,80,10)))assertEquals(List.of(),check(List.of(shape),gas()));
    }
    @Test void neighbourIsNotExempted() {
        assertTrue(check(List.of(new ObjectShape("gas_pipeline",false,0,-60,0,60),new ObjectShape("power_cable",false,10,1,100,1)),gas()).contains("CLEARANCE"));
    }
    @Test void overlappingRoadAndGasUseMaxWithAllBoundaries() {
        var obs=List.of(new ObjectShape("road",true,-2,-60,2,-60,2,60,-2,60,-2,-60),new ObjectShape("gas_pipeline",false,0,-60,0,60));
        var fs=List.of(new Piece("a",-30,-5,80,10,1),new Piece("b",-5,-2,80,10,1.6),new Piece("c",-2,2,80,10,1.6),new Piece("d",2,5,80,10,1.6),new Piece("e",5,120,80,10,1));
        assertEquals(List.of(),check(obs,fs));
        var mutation=new ArrayList<>(fs);mutation.set(2,new Piece("c",-2,2,80,10,2));
        assertTrue(check(obs,mutation).contains("KSPEC"));assertTrue(check(obs,mutation).contains("COST"));
    }
    @Test void everyMandatoryMutationIsDetected() {
        var obs=List.of(new ObjectShape("gas_pipeline",false,0,-60,0,60));
        var wrong=gas();wrong.get(1).from=-3;assertTrue(check(obs,wrong).contains("SPLIT"));
        wrong=gas();wrong.get(1).du=50;assertTrue(check(obs,wrong).contains("HYDRAULICS"));
        wrong=gas();wrong.get(2).id="a";assertTrue(check(obs,wrong).contains("ID"));
        wrong=gas();wrong.remove(2);assertTrue(check(obs,wrong).contains("CONNECTIVITY"));
        wrong=gas();wrong.get(1).k=1;assertTrue(check(obs,wrong).contains("KSPEC"));
        for(String type:List.of("water","railway"))assertTrue(check(List.of(new ObjectShape(type,true,-2,-60,2,-60,2,60,-2,60,-2,-60)),gas()).contains("FORBIDDEN"));
    }
}
