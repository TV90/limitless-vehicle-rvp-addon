package org.ywzj.rvp.countermeasure;

import com.google.gson.annotations.SerializedName;

/**
 * 烟雾云属性（RVP_CountermeasureSmokeData），载具 JSON {@code countermeasure.smoke.smoke} 子对象。
 *
 * <p>烟雾为地面载具干扰物：生成一团随时间膨胀的云（AABB 即禁视区），实现
 * {@link org.ywzj.vehicle.api.entity.SightObstruction} 遮蔽光学制导；目标中心落入
 * 任一存活烟雾云 AABB 时 IR/AIR 脱锁进惯导。</p>
 */
public final class RVP_CountermeasureSmokeData {

    /** 云团存活 tick（含弹道飞行段），到点消散。 */
    @SerializedName("lifetime_tick")
    private int lifetimeTick = 100;

    /** 云团最终半径（格），AABB 从 0 随时间膨胀到该值；需显著大于导弹近炸半径。 */
    @SerializedName("radius")
    private float radius = 10.0F;

    /** 出膛初速（格/tick，沿发射装置瞄准方向，叠加载具速度）。 */
    @SerializedName("speed")
    private float speed = 1.0F;

    /** 下落加速度（格/tick²），用于出膛弹道飞行段。 */
    @SerializedName("gravity")
    private float gravity = 0.05F;

    /** 出膛后延迟该 tick 爆炸生成烟雾云。 */
    @SerializedName("explode_tick")
    private int explodeTick = 10;

    /** 烟雾上升速度（格/tick，即负重力语义）。 */
    @SerializedName("rise_speed")
    private float riseSpeed = 0.03F;

    /** 遮蔽强度 0~1（预留：后续分级遮蔽 / 视线衰减，当前仅作数据保留）。 */
    @SerializedName("opacity")
    private float opacity = 1.0F;

    /** 风/随机飘移幅度（格/tick，预留）。 */
    @SerializedName("drift")
    private float drift = 0.0F;

    public int getLifetimeTick() {
        return Math.max(1, lifetimeTick);
    }

    public float getRadius() {
        return Float.isFinite(radius) ? Math.max(1.0F, radius) : 10.0F;
    }

    public float getSpeed() {
        return Float.isFinite(speed) ? Math.max(0.0F, speed) : 1.0F;
    }

    public float getGravity() {
        return Float.isFinite(gravity) ? Math.max(0.0F, gravity) : 0.05F;
    }

    public int getExplodeTick() {
        return Math.max(0, explodeTick);
    }

    public float getRiseSpeed() {
        return Float.isFinite(riseSpeed) ? Math.max(0.0F, riseSpeed) : 0.03F;
    }

    public float getOpacity() {
        return Float.isFinite(opacity) ? Math.max(0.0F, Math.min(1.0F, opacity)) : 1.0F;
    }

    public float getDrift() {
        return Float.isFinite(drift) ? drift : 0.0F;
    }
}
