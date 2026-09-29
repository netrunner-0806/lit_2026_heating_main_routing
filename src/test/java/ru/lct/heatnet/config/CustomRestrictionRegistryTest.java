package ru.lct.heatnet.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomRestrictionRegistryTest {

    @AfterEach
    void cleanup() { RestrictionRules.clearCustom(); }

    @Test
    void customRulesExtendButNeverOverrideTheStandardTable() {
        assertFalse(RestrictionRules.forType("metro").isPresent());
        HeatnetProperties props = new HeatnetProperties();
        HeatnetProperties.CustomRestriction metro = new HeatnetProperties.CustomRestriction();
        metro.setType("metro"); metro.setBehavior("FORBIDDEN"); metro.setHorizontalClearance(5.0);
        HeatnetProperties.CustomRestriction collector = new HeatnetProperties.CustomRestriction();
        collector.setType("collector"); collector.setBehavior("SPECIAL"); collector.setHorizontalClearance(2.0); collector.setKSpecial(1.30);
        collector.setVerticalRule("UTILITY"); collector.setUtilityTop(2.0); collector.setUtilityHeight(1.0); collector.setVerticalClearance(0.3);
        HeatnetProperties.CustomRestriction bad = new HeatnetProperties.CustomRestriction();
        bad.setType("oks"); bad.setHorizontalClearance(0.1);
        props.getCustomRestrictions().add(metro);
        props.getCustomRestrictions().add(collector);
        props.getCustomRestrictions().add(bad);
        new CustomRestrictionRegistrar(props).register();
        assertTrue(RestrictionRules.forType("metro").get().isForbidden());
        assertEquals(5.0, RestrictionRules.forType("metro").get().clearanceM(100));
        assertTrue(RestrictionRules.forType("collector").get().isSpecial());
        assertEquals(1.30, RestrictionRules.forType("collector").get().kSpec());
        assertTrue(DepthRules.forType("collector").get().isUtility());
        assertEquals(5.0, RestrictionRules.forType("oks").get().clearanceM(100), "standard rule untouched");
        assertFalse(RestrictionRules.isStandard("metro"));
        assertTrue(RestrictionRules.isStandard("oks"));
    }
}
