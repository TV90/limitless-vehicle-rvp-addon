package org.ywzj.rvp.firesupport.server;

import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 炮火任务创建时冻结的一种真实武器及其类型化投送器。 */
public record RVP_FireSupportMissionWeapon(
        /** profile 中已严格校验的武器成员配置。 */ RVP_FireSupportProfile.MunitionWeapon configuration,
        /** 根据成员投送数据创建的运行时投送器。 */ RVP_FireSupportDelivery delivery,
        /** 接受请求时从本体权威索引解析的真实 RVP 武器数据。 */ RVP_WeaponData weaponData) {
    public RVP_FireSupportMissionWeapon {
        if (configuration == null || delivery == null || weaponData == null) {
            throw new IllegalArgumentException("任务武器配置、投送器和武器数据不能为空");
        }
    }
}
