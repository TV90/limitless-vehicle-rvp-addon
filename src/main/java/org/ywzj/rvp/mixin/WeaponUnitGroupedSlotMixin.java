package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.vehicle.RVP_GroupedWeaponSlotAssembler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;

/**
 * 组合挂架装配（替代曾用 {@code RVP_GroupedSlotSpawnHandler} 的每 tick 延迟装配方案）。
 *
 * <p>在 {@code WeaponUnit.combineAndInit} HEAD 拦截并取消本体默认武器组装，改为 RVP 分组组装
 * （{@code merge_into_previous_slot} 槽位合并为外层 {@code VehicleMultiWeapons}）。
 * 该时机在实体初始化阶段（服务端构造 / 客户端 {@code readSpawnData}），此时：
 * <ul>
 *   <li>载具数据（{@code VehicleDataManager}）与武器注册表已就绪；</li>
 *   <li>单机下 {@code RVP_VehicleExtendedConfigManager.INSTANCE}（static 单例）已被服务端
 *       数据仓库 reload 填充，客户端共享同一实例，双端装配必然一致；</li>
 *   <li>装配在实体加入世界（{@code EntityJoinLevelEvent}）之前完成，武器对象一经创建即为
 *       合并后的 3 槽结构，弹药补给、HUD、同步数据、切换索引全部一致。</li>
 * </ul>
 * </p>
 *
 * <p>此前的 tick 延迟装配方案在实体加入世界后才替换武器对象：客户端遍历仅覆盖渲染范围内
 * 的实体（{@code entitiesForRendering}），且替换对象破坏服务端/客户端同步与 HUD 绑定，
 * 导致 T90M 出现 AP/HE 拆成两个武器栏、滚轮/按键错乱、换弹 UI 消失等一连串问题。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitGroupedSlotMixin {

    @Inject(method = "combineAndInit", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$assembleGroupedSlots(Map<String, PartUnit<?>> partUnitsView,
                                               AbstractVehicle vehicle,
                                               CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!RVP_GroupedWeaponSlotAssembler.shouldHandle(self)) {
            return;
        }
        RVP_GroupedWeaponSlotAssembler.assemble(self, partUnitsView, vehicle);
        ci.cancel();
    }
}
