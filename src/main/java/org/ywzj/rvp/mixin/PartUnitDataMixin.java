package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.PartUnitDataExt;
import org.ywzj.rvp.ext.PartUnitPojoExt;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.custom.part.data.PartUnitPojo;

/**
 * 载具部件运行时数据基类（{@code PartUnitData}）的 RVP 通用扩展字段拷贝。
 *
 * <p>在基类构造器（入参 {@code PartUnitPojo}）TAIL 把 JSON 层标志拷贝到运行时字段，
 * 模式与 {@code WeaponUnitDataMixin}/{@code RadarUnitDataMixin} 完全一致。PartUnitData
 * 为双端安全类（common 数组已有 accessor.PartUnitDataAccessor 注入先例），无带毒风险。</p>
 */
@Mixin(value = PartUnitData.class, remap = false)
public class PartUnitDataMixin implements PartUnitDataExt {

    /**
     * 「无座舱视角」运行时标志，构造器 TAIL 从 {@link PartUnitPojoExt} 拷贝，默认 false。
     */
    @Unique
    private boolean ywzj_rvp$noCockpitView = false;

    @Inject(method = "<init>(Lorg/ywzj/vehicle/custom/part/data/PartUnitPojo;)V", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$init(PartUnitPojo pojo, CallbackInfo ci) {
        if (pojo instanceof PartUnitPojoExt ext) {
            // 拷贝 JSON 层「无座舱视角」标记到运行时，供客户端 RVP_ViewRestriction 判定消费
            this.ywzj_rvp$noCockpitView = ext.ywzj_rvp$isNoCockpitView();
        }
    }

    @Override
    public boolean ywzj_rvp$isNoCockpitView() {
        return ywzj_rvp$noCockpitView;
    }
}
