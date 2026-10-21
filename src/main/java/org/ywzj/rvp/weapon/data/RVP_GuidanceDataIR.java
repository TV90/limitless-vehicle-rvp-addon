package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

/**
 * IR 制导专属扩展数据（{@code guidance_data} 的按制导类型子类，仅 {@code guidance_type:"IR"} 可用；
 * 其它制导类型写本类字段会被 {@link RVP_GuidanceDataAdapter} 拒绝并抛 JsonParseException）。
 *
 * <p>承载「IR 导引头连续跟踪锁定」三参数（2026-10-21）：IR 锁定（armed）= 同一目标连续
 * {@link #seekerLockDwellTick} tick 待在该导引头自己的跟踪包线内；追踪期短暂掉出包线由
 * {@link #seekerDwellGraceTick} 宽限（不涨不降）；锁定后超包线保活由 {@link #seekerHoldGraceTick}
 * 承接（吸收原 IR_RELOCK_GRACE_TICKS=6 与 HMD 的 20）。三字段均可缺省，缺省即 20/3/6。</p>
 */
public class RVP_GuidanceDataIR extends RVP_GuidanceData {

    /** 连续跟踪多少 tick 落锁（玩家与 AI 炮手共用）；≤0 视为 0（捕获即锁）。 */
    @SerializedName("seeker_lock_dwell_tick")
    private Integer seekerLockDwellTick;

    /** 追踪期短暂掉出包线的宽限 tick（进度不涨不降）；超过则清零重扫。 */
    @SerializedName("seeker_dwell_grace_tick")
    private Integer seekerDwellGraceTick;

    /** 锁定后目标超出 hold 包线的保活 tick（吸收短暂遮挡/机动）；超时掉锁。 */
    @SerializedName("seeker_lock_hold_grace_tick")
    private Integer seekerHoldGraceTick;

    /**
     * 落锁驻留双端解析：非 IR 数据实例或未配置回退默认 20（与既有 20t 门控等值，存量手感不变）。
     */
    public static int resolveLockDwellTick(@Nullable RVP_GuidanceData data) {
        return data instanceof RVP_GuidanceDataIR ir && ir.seekerLockDwellTick != null
                ? Math.max(0, ir.seekerLockDwellTick) : 20;
    }

    /** 追踪宽限双端解析：非 IR 数据实例或未配置回退默认 3。 */
    public static int resolveDwellGraceTick(@Nullable RVP_GuidanceData data) {
        return data instanceof RVP_GuidanceDataIR ir && ir.seekerDwellGraceTick != null
                ? Math.max(0, ir.seekerDwellGraceTick) : 3;
    }

    /** 锁定保活双端解析：非 IR 数据实例或未配置回退默认 6（对齐原 IR_RELOCK_GRACE_TICKS）。 */
    public static int resolveHoldGraceTick(@Nullable RVP_GuidanceData data) {
        return data instanceof RVP_GuidanceDataIR ir && ir.seekerHoldGraceTick != null
                ? Math.max(0, ir.seekerHoldGraceTick) : 6;
    }
}
