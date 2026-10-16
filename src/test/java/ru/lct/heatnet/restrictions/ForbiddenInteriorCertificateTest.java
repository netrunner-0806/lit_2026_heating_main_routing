package ru.lct.heatnet.restrictions;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ForbiddenInteriorCertificateTest {
    @Test void everyCertifiedRejectionMatchesFullCheckerAcrossDirectionsAndExemptions() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput()
                .rect(1, "water", 0, 0, 25, 40).rect(2, "road", 50, 0, 55, 40)
                .rect(3, "oks", -40, -20, -10, 20).build(), ClearanceClass.SMALL);
        Random random = new Random(20261016); int rejected = 0;
        for (int i = 0; i < 10000; i++) {
            Coordinate a = SyntheticInput.c(random.nextDouble()*140-60, random.nextDouble()*100-40);
            Coordinate b = SyntheticInput.c(random.nextDouble()*140-60, random.nextDouble()*100-40);
            Exemptions ex = i % 3 == 0 ? new Exemptions().disc(a, 15, o -> true) : Exemptions.NONE;
            if (space.definitelyBlocked(a,b,ex)) {
                rejected++;
                SegmentCheck full = space.check(a,b,ex);
                assertFalse(full.valid()); assertFalse(full.validReverse());
            }
        }
        assertTrue(rejected > 3000, "meaningful rejection rate, not an inert shortcut");
    }

    @Test void certificatesDoNotRejectSpecialPassagesTangencyOrExemptSegments() {
        ObstacleSpace space = new ObstacleSpace(new SyntheticInput()
                .rect(1,"water",0,0,10,10).rect(2,"road",20,0,25,10).build(), ClearanceClass.SMALL);
        assertFalse(space.definitelyBlocked(SyntheticInput.c(15,5),SyntheticInput.c(30,5),Exemptions.NONE));
        assertFalse(space.definitelyBlocked(SyntheticInput.c(-1,0),SyntheticInput.c(11,0),Exemptions.NONE));
        assertTrue(space.definitelyBlocked(SyntheticInput.c(-5,5),SyntheticInput.c(15,5),Exemptions.NONE));
        assertFalse(space.definitelyBlocked(SyntheticInput.c(-5,5),SyntheticInput.c(15,5),
                new Exemptions().disc(SyntheticInput.c(5,5),100,o->true)));
    }
}
