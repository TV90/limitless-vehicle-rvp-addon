package org.ywzj.rvp.firesupport.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;

/** 将 profile 武器引用解析为本体武器索引中的 RVP 数据。 */
@FunctionalInterface
public interface RVP_FireSupportWeaponResolver {
    /** @return 实际索引中的 RVP 武器数据；不存在或非 RVP 时返回 null。 */
    @Nullable RVP_FireSupportResolvedWeapon resolve(ResourceLocation weaponId);
}
