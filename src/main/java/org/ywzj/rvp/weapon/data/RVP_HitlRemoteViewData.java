package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.hitl.RVP_HitlRemoteLimits;

/** 人在回路导弹的额外观察窗口；仅 guidance_data.hitl_remote_view.enabled=true 生效。 */
public final class RVP_HitlRemoteViewData {
    /** 显式启用远程区块/实体订阅；默认 false，缺少整个对象同样不启用。 */
    @SerializedName("enabled")
    private boolean enabled;
    /** 导弹周围方形窗口半径，单位区块；默认 6，规范化为 2～32，另受服务器上限约束。 */
    @SerializedName("radius_chunks")
    private int radiusChunks = 6;
    /** 单会话每 tick 新增区块中心票据上限；默认 1，范围 1～32，受全服预算约束。 */
    @SerializedName("load_chunks_per_tick")
    private int loadChunksPerTick = 1;
    /** 单会话每 tick 首次发送的完整区块包上限；默认 2，范围 1～32，受全服预算约束。 */
    @SerializedName("send_chunks_per_tick")
    private int sendChunksPerTick = 2;
    /** 沿水平速度方向提前保活的区块数；默认 1，范围 0～2，不扩大实际观察窗口。 */
    @SerializedName("prefetch_chunks")
    private int prefetchChunks = 1;
    /** 无线电遮挡检查间隔，单位 tick；默认 5，范围 1～20，检查不加载或生成地形。 */
    @SerializedName("link_check_interval_ticks")
    private int linkCheckIntervalTicks = 5;
    /** 是否要求无线电射线全部地形已加载；默认 false。true 时未知地形按链路阻断处理。 */
    @SerializedName("require_loaded_radio_path")
    private boolean requireLoadedRadioPath;

    public boolean isEnabled() { return enabled; }
    public int getRadiusChunks() { return Math.max(2, Math.min(RVP_HitlRemoteLimits.MAX_RADIUS_CHUNKS, radiusChunks)); }
    public int getLoadChunksPerTick() { return Math.max(1, Math.min(RVP_HitlRemoteLimits.MAX_LOAD_CHUNKS_PER_TICK, loadChunksPerTick)); }
    public int getSendChunksPerTick() { return Math.max(1, Math.min(RVP_HitlRemoteLimits.MAX_SEND_CHUNKS_PER_TICK, sendChunksPerTick)); }
    public int getPrefetchChunks() { return Math.max(0, Math.min(2, prefetchChunks)); }
    public int getLinkCheckIntervalTicks() { return Math.max(1, Math.min(20, linkCheckIntervalTicks)); }
    public boolean isRequireLoadedRadioPath() { return requireLoadedRadioPath; }
}
