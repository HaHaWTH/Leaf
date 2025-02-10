package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class RaytraceTracker extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".raytrace-entity-tracker";
    }

    public static boolean enabled = false;
    public static int cullingDelayTicks = 10;
    public static int raytraceCheckIntervalMillis = 20;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
        cullingDelayTicks = config.getInt(getBasePath() + ".culling-delay-ticks", cullingDelayTicks);
        raytraceCheckIntervalMillis = config.getInt(getBasePath() + ".check-interval-millis", raytraceCheckIntervalMillis);
    }
}
