package org.ywzj.rvp.all;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.RVP_OpticalWeaponUnit;
import org.ywzj.vehicle.all.ModRegistries;
import org.ywzj.vehicle.custom.part.PartUnitType;
import org.ywzj.vehicle.custom.part.PartUnitTypes;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;

/** 注册独立观瞄站类型，通过本体公开工厂使用子类，不修改本体或增加注入。 */
public final class RVP_PartUnitTypes {
    /** 使用本体的部件注册表；类型属于附属模组命名空间。 */
    private static final DeferredRegister<PartUnitType<?, ?>> TYPES =
            DeferredRegister.create(ModRegistries.PART_UNIT_TYPE, RVP_MOD.MOD_ID);

    /** 配置 type 为 ywzj_rvp:optical_weapon 时启用连续转角观瞄站。 */
    public static final RegistryObject<PartUnitType<RVP_OpticalWeaponUnit, WeaponUnitData>> OPTICAL_WEAPON =
            TYPES.register("optical_weapon", () -> PartUnitType.Builder
                    .<RVP_OpticalWeaponUnit, WeaponUnitData>of(RVP_MOD.modLocation("optical_weapon"))
                    .setFactory(RVP_OpticalWeaponUnit::new)
                    // 调用现有武器站解析入口，复用当前字段及 crt_ui 语法，不复制或迁移 schema。
                    .setDataSerializer(json -> PartUnitTypes.WEAPON.parseAndCreate(json).data())
                    .build());

    /** 工具类不允许实例化。 */
    private RVP_PartUnitTypes() {}

    /** 将部件类型注册挂到附属模组的生命周期。 */
    public static void register(IEventBus eventBus) {
        TYPES.register(eventBus);
    }
}
