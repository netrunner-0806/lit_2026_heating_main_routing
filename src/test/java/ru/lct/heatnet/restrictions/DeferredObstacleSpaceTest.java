package ru.lct.heatnet.restrictions;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class DeferredObstacleSpaceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ClearanceClass.class)
    void linearCrossingsAndAlongsideClearancesSurviveDeferredPreparation(ClearanceClass cls) {
        for(String type:new String[]{"gas_pipeline","power_cable","heat_network"}) {
            SyntheticInput scene=new SyntheticInput();
            if(type.equals("heat_network"))scene.line(1,300,-100,0,100,0);
            else scene.polyline(1,type,-100,0,100,0);
            ObstacleSpace lazy=new ObstacleSpace(scene.build(),cls,true);
            SegmentCheck crossing=lazy.check(c(0,-30),c(0,30));
            assertTrue(crossing.valid(),type+" "+crossing.reason());
            assertEquals(4.,crossing.passages().get(0).length(),1e-9,"Exactly +/-2 m, without the pair envelope");
            double required=lazy.requiredDistance(lazy.obstacles().get(0));
            assertFalse(lazy.check(c(-20,required-.01),c(20,required-.01)).valid(),"Parallel approach still needs clearance");
            assertTrue(lazy.check(c(-20,required+.01),c(20,required+.01)).valid());
        }
    }

    @Test void indexedQueriesMatchEagerGeometryWithoutDiscardingRemoteObjects() {
        SyntheticInput scene=new SyntheticInput();
        String[] types={"water","railway","oks","park","social_area","prohibited_site","road","tram_tracks"};
        for(int i=0;i<types.length;i++) scene.rect(i+1,types[i],i*70,100,i*70+30,130);
        scene.rect(100,"water",-10000,-10,10000,10); // intersects local queries although every vertex is far away
        scene.rect(101,"road",20000,0,20100,10);
        InputModel input=scene.build();
        ObstacleSpace eager=new ObstacleSpace(input,ClearanceClass.SMALL,false);
        ObstacleSpace lazy=new ObstacleSpace(input,ClearanceClass.SMALL,true);
        assertEquals(0,lazy.preparedObjectCount());
        assertEquals(eager.obstacles().size(),lazy.obstacles().size());
        assertFalse(lazy.check(c(0,-30),c(0,30)).valid());
        assertTrue(lazy.preparedObjectCount()<input.restrictions().size());
        Random rnd=new Random(42);
        for(int i=0;i<500;i++) {
            var a=c(rnd.nextDouble()*650-50,rnd.nextDouble()*220-40);
            var b=c(rnd.nextDouble()*650-50,rnd.nextDouble()*220-40);
            SegmentCheck x=eager.check(a,b),y=lazy.check(a,b);
            assertEquals(x.valid(),y.valid());assertEquals(x.validReverse(),y.validReverse());
            if(x.valid()||x.validReverse())assertEquals(x.effectiveLength(),y.effectiveLength(),1e-8);
            assertEquals(eager.insideForbidden(a),lazy.insideForbidden(a));
            assertEquals(eager.insideAnyZone(a),lazy.insideAnyZone(a));
        }
        // A later query outside the original working area must still prepare/check the remote road.
        assertTrue(lazy.check(c(20050,-20),c(20050,30)).hasSpecial());
    }

    @Test void concurrentFirstQueriesPublishOneCompletePreparedObject() {
        ObstacleSpace lazy=new ObstacleSpace(new SyntheticInput().rect(1,"road",0,0,50,10).build(),ClearanceClass.SMALL,true);
        java.util.stream.IntStream.range(0,100).parallel().forEach(i->{
            SegmentCheck check=lazy.check(c(25,-20),c(25,30));
            assertTrue(check.valid());assertEquals(59.6,check.effectiveLength(),1e-8);
        });
        assertEquals(1,lazy.preparedObjectCount());
    }
}
