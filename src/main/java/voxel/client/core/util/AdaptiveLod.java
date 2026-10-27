package voxel.client.core.util;

/**
 * Bounded, hysteretic LOD subdivision controller.
 *
 * <p>When the frame rate collapses while looking at a large LOD vista, making
 * the traversal subdivide less aggressively removes quads from the far ring.
 * The user's configured subdivision stays the floor; the controller only
 * coarsens above it, then walks back down when the frame rate recovers.</p>
 */
public final class AdaptiveLod {
    public static final float MIN_SUBDIVISION = 64.0f;
    public static final float MAX_SUBDIVISION = 256.0f;
    public static final float STEP_UP = 16.0f;
    public static final float STEP_DOWN = 8.0f;
    public static final int FPS_LOW = 50;
    public static final int FPS_HIGH = 70;

    private AdaptiveLod() {
    }

    /**
     * @param current    last effective subdivision size
     * @param configured user-configured size (kept as the quality floor)
     * @param fps        current smooth FPS
     * @param canDecrease true when the mesh queue is short enough to refine
     */
    public static float step(float current, float configured, int fps, boolean canDecrease) {
        float floor = Math.max(MIN_SUBDIVISION, Math.min(MAX_SUBDIVISION, configured));
        float value = Math.max(floor, Math.min(MAX_SUBDIVISION, current));
        if (fps < FPS_LOW) {
            return Math.min(MAX_SUBDIVISION, value + STEP_UP);
        }
        if (fps > FPS_HIGH && canDecrease) {
            return Math.max(floor, value - STEP_DOWN);
        }
        return value;
    }
}
