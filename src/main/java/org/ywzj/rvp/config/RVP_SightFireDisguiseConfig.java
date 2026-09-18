package org.ywzj.rvp.config;

import com.google.gson.annotations.SerializedName;

/**
 * 观瞄视角射弹原点分离配置（载具 JSON 武器站字段 {@code rvp_sight_fire_disguise}）。
 *
 * <p>启用后：玩家处于观瞄视角（SCOPE）操作本站开火时，实际弹体从观瞄相机坐标射出
 * （方向不变，即炮管当前指向=准星方向），消除观瞄相机离炮闩枢轴过远带来的抵近射击偏差；
 * 客户端在前 {@code disguise_ticks} 内把弹体模型/曳光平移渲染成"从炮口射出"的伪装弹道，
 * 随后 {@code blend_ticks} 内平滑合流真实弹道。字段缺失 = 功能关闭，全部武器行为不变。</p>
 *
 * <p>配置在武器站层而非弹药层：观瞄是站属性（optical_sight_type/offset 同层），
 * 站上现有与未来的弹种（含 {@code modding_only_multi} 改装弹种变体）自动继承，
 * 改装工具切换弹种无需逐文件配置。</p>
 */
public class RVP_SightFireDisguiseConfig {

    /** 伪装持续 tick：真实弹出生后渲染被平移到"炮口出发的平行弹道"上的时长（2026-09-19 用户定版 1，1tick 后即开始迁移）。 */
    @SerializedName("disguise_ticks")
    private int disguiseTicks = 1;

    /** 合流 tick：伪装位置向真实弹道 smoothstep 缓入缓出过渡的时长（0 会被钳为 1；2026-09-19 用户定版延长到 3 提升平滑度）。 */
    @SerializedName("blend_ticks")
    private int blendTicks = 3;

    /** 服务端防滥用距离帽：观瞄射出点距载具原点超过该值（格）的覆盖请求被拒绝。 */
    @SerializedName("max_distance")
    private float maxDistance = 32.0f;

    public int disguiseTicks() {
        return Math.max(0, disguiseTicks);
    }

    public int blendTicks() {
        return Math.max(1, blendTicks);
    }

    public float maxDistance() {
        return Math.max(1.0f, maxDistance);
    }
}
