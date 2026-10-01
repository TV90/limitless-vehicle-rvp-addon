package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.WeaponInfoExt;
import org.ywzj.vehicle.vehicle.pojo.WeaponInfo;

/**
 * 武器条目级 full_salvo 免疫开关（{@code rvp_full_salvo_immune}，默认 false）。
 *
 * <p>本体 {@code firing_mode: "full_salvo"} 站的客户端开火分发是白名单
 * （{@code InputHandler.handleShoot} 的 FULL_SALVO 分支只遍历 {@code WeaponUnit.fullSalvoWeapons}，
 * 完全不读 {@code getCurrentWeapon()}）——名单外的武器在该站<b>哑火</b>。本字段标记
 * 「该条目不受所在站 full_salvo 机制接管，走常规『当前选中即发射』路径」，
 * 消费点见 {@code InputHandlerFullSalvoImmuneMixin}。</p>
 *
 * <p>字段必须落在 {@code WeaponInfo}（{@code WeaponUnitPojo.weapons} <b>列表的元素</b>）上：
 * 现有 {@code WeaponUnitPojoMixin} 作用于最外层 pojo，管不到列表元素，故单独 mixin 本类。</p>
 *
 * <p>用原始类型 boolean：{@code WeaponInfo} 仅有 7 参构造、Gson 反序列化 pojo 走 Unsafe 分配，
 * 字段初始化器不执行，原始类型"未配置"落 false——正是默认关闭语义（不影响未配置的存量载具）。</p>
 */
@Mixin(value = WeaponInfo.class, remap = false)
public class WeaponInfoMixin implements WeaponInfoExt {

    /** 该武器条目是否免疫所在武器站的 full_salvo 齐射接管；默认false（未配置=本体原行为）。 */
    @SerializedName("rvp_full_salvo_immune")
    @Unique
    private boolean ywzj_rvp$fullSalvoImmune;

    @Override
    public boolean ywzj_rvp$fullSalvoImmune() {
        return ywzj_rvp$fullSalvoImmune;
    }
}
