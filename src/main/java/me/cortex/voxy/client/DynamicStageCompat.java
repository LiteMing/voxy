package me.cortex.voxy.client;

/** Runtime hooks used by Dynamic Stage without introducing a hard dependency. */
public final class DynamicStageCompat {
    private static volatile boolean preserveCameraSection;

    private DynamicStageCompat() {
    }

    public static void setPreserveCameraSection(boolean preserve) {
        preserveCameraSection = preserve;
    }

    public static boolean preserveCameraSection() {
        return preserveCameraSection;
    }
}
