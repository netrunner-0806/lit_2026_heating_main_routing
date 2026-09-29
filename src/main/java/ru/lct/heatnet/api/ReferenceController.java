package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnet.config.ChamberCostTable;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRule;
import ru.lct.heatnet.config.RestrictionRules;

@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference", description = "Справочники расчёта (таблицы актуального технического приложения)")
public class ReferenceController {

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    @Operation(summary = "Таблица ДУ, стоимость камер, правила ограничений, формула score")
    public ObjectNode reference() {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode dus = n.putArray("diameters");
        for (DiameterSpec s : DiameterTable.rows()) {
            ObjectNode o = dus.addObject();
            o.put("du", s.du());
            o.put("capacityTph", s.capacityTph());
            o.put("maxLengthM", s.maxLengthM());
            o.put("costPerMeter", s.costPerMeter());
            o.put("pairWidthM", s.pairWidthM());
            o.put("heightM", s.heightM());
        }
        ObjectNode ch = n.putObject("chamberCost");
        ch.put("du50_200", ChamberCostTable.newChamberCost(200));
        ch.put("du250_500", ChamberCostTable.newChamberCost(500));
        ch.put("du600_1000", ChamberCostTable.newChamberCost(1000));
        ch.put("du1200_1400", ChamberCostTable.newChamberCost(1400));
        ch.put("existingChamberTieIn", ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST);
        ArrayNode rules = n.putArray("restrictions");
        for (RestrictionRule r : RestrictionRules.all().values()) {
            ObjectNode o = rules.addObject();
            o.put("type", r.type());
            o.put("kind", r.kind().name());
            o.put("clearanceM", r.clearanceDependsOnDu() ? "5 (DU<500) / 7 (500..800) / 9 (>=900)" : String.valueOf(r.clearanceM(100)));
            o.put("minCrossingAngleDeg", r.minCrossingAngleDeg());
            o.put("sectionMode", r.sectionMode().name());
            o.put("sectionExtentM", r.sectionExtentM());
            o.put("kSpec", r.kSpec());
            o.put("ownWidthM", r.ownWidthM());
            ru.lct.heatnet.config.DepthRules.forType(r.type()).ifPresent(v -> o.put("depthRule", v.isUtility()
                    ? String.format(java.util.Locale.ROOT, "above/below, clearance %.2f m", v.clearanceM()) : String.format(java.util.Locale.ROOT, "under, cover >= %.1f m", v.minDepthM())));
        }
        ArrayNode custom = n.putArray("customRestrictions");
        for (RestrictionRule r : RestrictionRules.custom().values()) {
            ObjectNode o = custom.addObject();
            o.put("type", r.type());
            o.put("kind", r.kind().name());
            o.put("clearanceM", r.clearanceM(100));
            o.put("minCrossingAngleDeg", r.minCrossingAngleDeg());
            o.put("sectionExtentM", r.sectionExtentM());
            o.put("kSpec", r.kSpec());
            ru.lct.heatnet.config.DepthRules.forType(r.type()).ifPresent(v -> o.put("verticalRule", v.isUtility()
                    ? String.format(java.util.Locale.ROOT, "utility top %.2f m, height %.2f m, clearance %.2f m", v.utilityTopM(), v.utilityHeightM(), v.clearanceM())
                    : String.format(java.util.Locale.ROOT, "pass under, cover >= %.2f m", v.minDepthM())));
        }
        ObjectNode depth = n.putObject("depth");
        depth.put("normalDepthM", ru.lct.heatnet.config.DepthRules.NORMAL_DEPTH_M);
        depth.put("minDepthM", ru.lct.heatnet.config.DepthRules.MIN_DEPTH_M);
        depth.put("maxDepthM", "not limited by the annex");
        depth.put("maxSlope", ru.lct.heatnet.config.DepthRules.MAX_SLOPE);
        depth.put("kDepthFormula", "Kdepth = 1 for h <= 3.0 m; Kdepth = 1 + 0.10 * (h - 3.0) for h > 3.0 m; ramps use the mean of the end values");
        depth.put("depthDefinition", "h = distance from the conventional ground surface to the TOP of the new pair envelope (height by DU, Table 1)");
        ArrayNode vr = depth.putArray("verticalRules");
        for (String t : new String[]{RestrictionRules.ROAD, RestrictionRules.TRAM_TRACKS, RestrictionRules.GAS_PIPELINE, RestrictionRules.POWER_CABLE, RestrictionRules.EXISTING_HEAT_NETWORK}) {
            ru.lct.heatnet.config.DepthRules.VerticalRule v = ru.lct.heatnet.config.DepthRules.forType(t).get();
            ObjectNode o = vr.addObject();
            o.put("type", t);
            if (v.isUtility()) {
                o.put("rule", "pass ABOVE or BELOW");
                o.put("utilityTopM", v.utilityTopM());
                o.put("utilityHeightM", Double.isNaN(v.utilityHeightM()) ? "height of the existing DU (Table 1)" : String.valueOf(v.utilityHeightM()));
                o.put("verticalClearanceM", v.clearanceM());
            } else {
                o.put("rule", "pass UNDER");
                o.put("minDepthM", v.minDepthM());
            }
        }
        ObjectNode sc = n.putObject("score");
        sc.put("formula", "S = 0.7 * (calculated_cost / 25 000 000) + 0.3 * (new_network_length / 100)");
        sc.put("penaltyFixed", Constants.UNCONNECTED_PENALTY_FIXED);
        sc.put("penaltyPerTph", Constants.UNCONNECTED_PENALTY_PER_TPH);
        sc.put("maxTurnAngleDeg", Constants.MAX_TURN_ANGLE_DEG);
        sc.put("maxChamberDegree", Constants.MAX_CHAMBER_DEGREE);
        sc.put("existingChamberSnapDistanceM", Constants.EXISTING_CHAMBER_SNAP_DISTANCE_M);
        return n;
    }
}
