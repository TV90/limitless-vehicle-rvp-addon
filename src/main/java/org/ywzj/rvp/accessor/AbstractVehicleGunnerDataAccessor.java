package org.ywzj.rvp.accessor;

import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;

/**
 * 访问 AbstractVehicleGunnerDataMixin 注入到 AbstractVehicle 中的 rvpRemoteFaction 字段。
 */
public interface AbstractVehicleGunnerDataAccessor {

    /**
     * 获取远程实体的 Gunner faction 缓存。
     * 仅对 remote=true 且通过 ServerBroadcastEntities 同步的载具有效。
     *
     * @return faction 如果存在；否则 null
     */
    RVP_EnumGunnerFaction ywzj_rvp$getRemoteFaction();
}
