package ru.lct.heatnet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime configuration of the service (not the rules of the annex). */
@Component
@ConfigurationProperties(prefix = "heatnet")
public class HeatnetProperties {
    private String storageDir = "./data/jobs";
    private int workers = 2;
    private int maxVariants = Constants.MAX_VARIANTS;
    private boolean improve = true;
    /** Configured extra restriction types (extension of the annex, see docs); standard types cannot be overridden. */
    private java.util.List<CustomRestriction> customRestrictions = new java.util.ArrayList<>();

    public static class CustomRestriction {
        private String type;
        private String behavior = "FORBIDDEN";          // FORBIDDEN | SPECIAL
        private double horizontalClearance = 1.0;
        private double minCrossingAngle = 0;
        private String sectionMode = "POINT_PLUS_EXTENT"; // POLYGON_PLUS_EXTENT | POINT_PLUS_EXTENT
        private double specialMargin = 2.0;
        private double kSpecial = 1.0;
        private double ownWidth = 0;
        /** Optional vertical rule: PASS_UNDER (minDepth) or UTILITY (top, height, clearance). */
        private String verticalRule;
        private double minDepth = 1.0;
        private double utilityTop = 3.0;
        private double utilityHeight = 0.5;
        private double verticalClearance = 0.5;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBehavior() { return behavior; }
        public void setBehavior(String behavior) { this.behavior = behavior; }
        public double getHorizontalClearance() { return horizontalClearance; }
        public void setHorizontalClearance(double v) { this.horizontalClearance = v; }
        public double getMinCrossingAngle() { return minCrossingAngle; }
        public void setMinCrossingAngle(double v) { this.minCrossingAngle = v; }
        public String getSectionMode() { return sectionMode; }
        public void setSectionMode(String v) { this.sectionMode = v; }
        public double getSpecialMargin() { return specialMargin; }
        public void setSpecialMargin(double v) { this.specialMargin = v; }
        public double getKSpecial() { return kSpecial; }
        public void setKSpecial(double v) { this.kSpecial = v; }
        public double getOwnWidth() { return ownWidth; }
        public void setOwnWidth(double v) { this.ownWidth = v; }
        public String getVerticalRule() { return verticalRule; }
        public void setVerticalRule(String v) { this.verticalRule = v; }
        public double getMinDepth() { return minDepth; }
        public void setMinDepth(double v) { this.minDepth = v; }
        public double getUtilityTop() { return utilityTop; }
        public void setUtilityTop(double v) { this.utilityTop = v; }
        public double getUtilityHeight() { return utilityHeight; }
        public void setUtilityHeight(double v) { this.utilityHeight = v; }
        public double getVerticalClearance() { return verticalClearance; }
        public void setVerticalClearance(double v) { this.verticalClearance = v; }
    }

    public java.util.List<CustomRestriction> getCustomRestrictions() { return customRestrictions; }
    public void setCustomRestrictions(java.util.List<CustomRestriction> v) { this.customRestrictions = v; }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
    public int getMaxVariants() { return maxVariants; }
    public void setMaxVariants(int maxVariants) { this.maxVariants = maxVariants; }
    public boolean isImprove() { return improve; }
    public void setImprove(boolean improve) { this.improve = improve; }
}
