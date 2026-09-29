package ru.lct.heatnet.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Registers configured custom restriction rules (heatnet.custom-restrictions) at startup. This is an extension
 * mechanism only: the mandatory types of Table 2 stay in the immutable standard configuration and cannot be changed.
 */
@Component
public class CustomRestrictionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CustomRestrictionRegistrar.class);
    private final HeatnetProperties props;

    public CustomRestrictionRegistrar(HeatnetProperties props) { this.props = props; }

    @PostConstruct
    public void register() {
        for (HeatnetProperties.CustomRestriction c : props.getCustomRestrictions()) {
            if (c.getType() == null || c.getType().isEmpty()) continue;
            boolean special = "SPECIAL".equalsIgnoreCase(c.getBehavior());
            RestrictionRule.SectionMode mode = !special ? RestrictionRule.SectionMode.NONE
                    : "POLYGON_PLUS_EXTENT".equalsIgnoreCase(c.getSectionMode()) ? RestrictionRule.SectionMode.POLYGON_PLUS_EXTENT : RestrictionRule.SectionMode.POINT_PLUS_EXTENT;
            RestrictionRule rule = new RestrictionRule(c.getType().trim().toLowerCase(), special ? RestrictionRule.Kind.SPECIAL : RestrictionRule.Kind.FORBIDDEN,
                    c.getHorizontalClearance(), false, c.getMinCrossingAngle(), mode, special ? c.getSpecialMargin() : 0, special ? c.getKSpecial() : 1.0, c.getOwnWidth());
            try {
                RestrictionRules.registerCustom(rule);
                if (c.getVerticalRule() != null) {
                    if ("PASS_UNDER".equalsIgnoreCase(c.getVerticalRule())) DepthRules.registerCustom(rule.type(), DepthRules.VerticalRule.passUnder(c.getMinDepth()));
                    else if ("UTILITY".equalsIgnoreCase(c.getVerticalRule())) DepthRules.registerCustom(rule.type(), DepthRules.VerticalRule.utility(c.getUtilityTop(), c.getUtilityHeight(), c.getVerticalClearance()));
                }
                log.info("custom restriction rule registered: {} ({}, clearance {} m, Kspec {})", rule.type(), rule.kind(), rule.clearanceM(100), rule.kSpec());
            } catch (IllegalArgumentException ex) {
                log.warn("custom restriction ignored: {}", ex.getMessage());
            }
        }
    }
}
