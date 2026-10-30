package org.ywzj.rvp.client.state;

/**
 * 夜视仪滤镜开关状态（仅客户端）。
 *
 * <p><b>两个状态位完全独立</b>（2026-10-31 用户定版）：观瞄视角与第三人称各开各的，
 * 只是共用同一个键位（本体热成像键 KEY_4）。切视角不联动、不自动同步——
 * 在第三人称开/关夜视不会影响观瞄态的热成像或夜视，反之亦然。</p>
 *
 * <p>语义分工：观瞄态若该武器站有热成像，开关由本体 {@code thermalImaging} 管，
 * 本类不参与；观瞄态的 {@link #isScopeEnabled()} 只在该站<b>没有</b>热成像、
 * 仅有夜视仪时才有意义（热成像与夜视仪互斥，热成像优先）。</p>
 */
public final class RVP_NvgState {

    /** 观瞄态夜视开关；仅在该武器站无热成像时参与判定。 */
    private static boolean scopeEnabled;

    /** 第三人称夜视开关；有夜视仪或有热成像均可开启（热成像在第三人称不存在，故一律走夜视滤镜）。 */
    private static boolean thirdPersonEnabled;

    /** 当前绑定的载具实体 id；换车或下车站清零，避免夜视状态跨车残留。 */
    private static int boundVehicleId = -1;

    private RVP_NvgState() {}

    public static boolean isScopeEnabled() {
        return scopeEnabled;
    }

    public static boolean isThirdPersonEnabled() {
        return thirdPersonEnabled;
    }

    /** 翻转观瞄态夜视开关（不动第三人称）。 */
    public static void toggleScope() {
        scopeEnabled = !scopeEnabled;
    }

    /** 翻转第三人称夜视开关（不动观瞄态）。 */
    public static void toggleThirdPerson() {
        thirdPersonEnabled = !thirdPersonEnabled;
    }

    /**
     * 绑定当前载具：只有在换车时才清零。
     * 每 tick 调用；同一辆车内反复调用不会重置状态。
     */
    public static void bindVehicle(int vehicleId) {
        if (boundVehicleId != vehicleId) {
            boundVehicleId = vehicleId;
            scopeEnabled = false;
            thirdPersonEnabled = false;
        }
    }

    /** 下车/退出载具时清零。 */
    public static void reset() {
        bindVehicle(-1);
    }
}
