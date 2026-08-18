package me.cortex.voxy.client;

/** Runtime hooks used by Dynamic Stage without introducing a hard dependency. */
public final class DynamicStageCompat {
    private static volatile boolean preserveCameraSection;
    private static volatile float stageNearPlane;

    private DynamicStageCompat() {
    }

    public static void setPreserveCameraSection(boolean preserve) {
        preserveCameraSection = preserve;
    }

    public static boolean preserveCameraSection() {
        return preserveCameraSection;
    }

    /** A non-positive value restores Voxy's normal projection. */
    public static void setStageNearPlane(float nearPlane) {
        stageNearPlane = Float.isFinite(nearPlane) && nearPlane > 0.0f ? nearPlane : 0.0f;
    }

    public static float stageNearPlane(float fallback) {
        float configured = stageNearPlane;
        return configured > 0.0f ? configured : fallback;
    }
}
