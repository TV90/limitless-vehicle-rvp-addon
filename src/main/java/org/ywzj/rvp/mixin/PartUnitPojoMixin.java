package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.PartUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.PartUnitPojo;

/**
 * 载具部件 Pojo 基类（{@code PartUnitPojo}）的 RVP 通用扩展字段注入。
 *
 * <p>PartUnitPojo 是全部部件 Pojo（WeaponUnitPojo/RadarUnitPojo 等）的基类，此处新增字段
 * 经 Gson 对任意类型部件的载具 JSON 生效（继承字段照常反序列化）。目标类仅依赖 gson/Vec3 等
 * 双端安全类型，common 数组注入无带毒风险（同类先例：RadarUnitPojoMixin/WeaponUnitPojoMixin）。</p>
 */
@Mixin(value = PartUnitPojo.class, remap = false)
public class PartUnitPojoMixin implements PartUnitPojoExt {

    /**
     * 无座舱视角标记（数据驱动，任意部件可写、语义作用于座位）：
     * <ul>
     *   <li>作用：被标记座位上玩家的视角循环（VIEW 键与自动降级）不含 OPERATOR 座舱第一人称，
     *       仅允许 THIRD_PERSON 与 SCOPE（观瞄 CRT）；</li>
     *   <li>默认：{@code false}（不写该字段 = 行为与本体完全一致）；</li>
     *   <li>生效条件：写在 {@code is_seat} 为 true 的座位部件上生效；典型用于双座载具的
     *       后座/武器官位（配合观瞄 CRT 使用）。非武器位座位本就只有 THIRD↔OPERATOR 两态，
     *       标记后循环停在 THIRD。</li>
     * </ul>
     */
    @SerializedName("rvp_no_cockpit_view")
    @Unique
    public boolean ywzj_rvp$noCockpitView = false;

    @Override
    public boolean ywzj_rvp$isNoCockpitView() {
        return ywzj_rvp$noCockpitView;
    }
}
