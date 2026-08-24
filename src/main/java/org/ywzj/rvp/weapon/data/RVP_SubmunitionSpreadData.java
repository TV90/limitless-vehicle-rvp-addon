package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * Spread applied when spawning submunition payloads. Reuses the same sampling as
 * {@link RVP_FireData} canister ({@link org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil}).
 */
public class RVP_SubmunitionSpreadData {

    /**
     * Simple axis velocity jitter (MCH {@code BombletDiff}). Used when {@link #canisterDiff} is 0
     * and {@link #mode} is {@code box}.
     */
    @SerializedName("box_spread")
    private float boxSpread = 0f;

    public void setBoxSpread(float boxSpread) {
        this.boxSpread = boxSpread;
    }

    /**
     * 散布模式，默认 {@code box}；接受 {@code box}/{@code canister}/{@code stratified_cone}/
     * {@code cloud_radial_horizontal}。其中云径向模式仅改变速度，使用子体最终出生位置相对释放点的
     * 水平投影作为外散方向。
     */
    @SerializedName("mode")
    private String mode = "box";

    /** canister 散布类型，默认 1；0 改位置，1/2 改速度方向，读取时限制为 0～2。 */
    @SerializedName("canister_type")
    private int canisterType = 1;

    /** canister 位置或角度散布强度，默认 0.3；canister 模式下限制为非负。 */
    @SerializedName("canister_diff")
    private float canisterDiff = 0.3f;

    /** canister 采样分布，默认 {@code uniform}；仅 canister 模式生效。 */
    @SerializedName("canister_distribution")
    private String canisterDistribution = RVP_EnumSpreadDistribution.UNIFORM.getSerializedName();

    /** canister 采样形状，默认 {@code circle}；仅 canister 模式生效。 */
    @SerializedName("canister_shape")
    private String canisterShape = RVP_EnumSpreadShape.CIRCLE.getSerializedName();

    /** 分层圆锥半角，单位度，默认 60；仅 {@code mode=stratified_cone} 时限制为 0～180。 */
    @SerializedName("cone_half_angle")
    private float coneHalfAngle = 60f;

    /**
     * 分层圆锥最大径向展开速度，单位格/Tick，默认 null（沿用 {@code launch_speed}）；
     * 仅 {@code mode=stratified_cone} 时生效，配置后允许低下落速度仍快速横向展开。
     */
    @SerializedName("cone_radial_speed")
    private Float coneRadialSpeed;

    /** 圆锥轴模式，默认 {@code world_down}；首版仅接受世界正下方向，未知值回退该值。 */
    @SerializedName("cone_axis")
    private String coneAxis = "world_down";

    /** 径向采样模式，默认 {@code uniform_area}；首版未知值回退均匀立体角采样。 */
    @SerializedName("radial_distribution")
    private String radialDistribution = "uniform_area";

    /** 方位角分层内随机扰动比例，范围 0～1，默认 0；仅分层圆锥模式生效。 */
    @SerializedName("azimuth_jitter")
    private float azimuthJitter = 0f;

    /** 径向分层内随机扰动比例，范围 0～1，默认 0；仅分层圆锥模式生效。 */
    @SerializedName("radial_jitter")
    private float radialJitter = 0f;

    /**
     * 权威云水平径向方向扰动角，单位度，默认 {@code 0}；仅
     * {@code mode=cloud_radial_horizontal} 时生效。有限值限制为 {@code 0..90}，
     * 非有限值按 {@code 0}，确保扰动后的速度不会反向指回云心。
     */
    @SerializedName("cloud_direction_jitter")
    private float cloudDirectionJitter = 0f;

    /**
     * 权威云水平径向速度随机比例，默认 {@code 0}；仅
     * {@code mode=cloud_radial_horizontal} 时生效。每枚子体的 {@code launch_speed}
     * 独立乘以 {@code [1-value, 1+value]} 内的均匀随机倍率；有限值限制为
     * {@code 0..1}，非有限值按 {@code 0}。
     */
    @SerializedName("cloud_speed_jitter")
    private float cloudSpeedJitter = 0f;

    public boolean usesStratifiedCone() {
        return "stratified_cone".equalsIgnoreCase(mode);
    }

    public boolean usesCloudRadialHorizontal() {
        return "cloud_radial_horizontal".equalsIgnoreCase(mode);
    }

    public boolean usesCanister() {
        return "canister".equalsIgnoreCase(mode) || canisterDiff > 0f;
    }

    public float getBoxSpread() {
        return Math.max(boxSpread, 0f);
    }

    public int getCanisterType() {
        return Math.max(0, Math.min(canisterType, 2));
    }

    public float getCanisterDiff() {
        return Math.max(canisterDiff, 0f);
    }

    public RVP_EnumSpreadDistribution getCanisterDistribution() {
        return RVP_EnumSpreadDistribution.fromString(canisterDistribution);
    }

    public RVP_EnumSpreadShape getCanisterShape() {
        return RVP_EnumSpreadShape.forCanister(canisterShape);
    }

    public float getConeHalfAngle() {
        return Float.isFinite(coneHalfAngle) ? Math.max(0f, Math.min(coneHalfAngle, 180f)) : 60f;
    }

    public double resolveConeRadialSpeed(double fallbackSpeed) {
        if (coneRadialSpeed == null || !Float.isFinite(coneRadialSpeed)) {
            return Math.max(fallbackSpeed, 0.0D);
        }
        return Math.max(coneRadialSpeed, 0f);
    }

    public String getConeAxis() {
        return "world_down";
    }

    public String getRadialDistribution() {
        return "uniform_area";
    }

    public float getAzimuthJitter() {
        return clampJitter(azimuthJitter);
    }

    public float getRadialJitter() {
        return clampJitter(radialJitter);
    }

    public float getCloudDirectionJitter() {
        return Float.isFinite(cloudDirectionJitter)
                ? Math.max(0f, Math.min(cloudDirectionJitter, 90f))
                : 0f;
    }

    public float getCloudSpeedJitter() {
        return clampJitter(cloudSpeedJitter);
    }

    private static float clampJitter(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(value, 1f)) : 0f;
    }
}
