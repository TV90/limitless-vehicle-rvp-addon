package org.ywzj.rvp.firesupport.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportImpactPoint;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 一发炮火投送所需的不可变服务端上下文。 */
public record RVP_FireSupportDeliveryContext(
        /** 任务所在服务端世界。 */ ServerLevel level,
        /** 伤害与击杀归属的任务发起者。 */ LivingEntity owner,
        /** 创建任务时从真实索引解析并由任务计划持有的 RVP 武器数据。 */ RVP_WeaponData weaponData,
        /** pattern 产生的权威 XZ 计划落点。 */ RVP_FireSupportImpactPoint impactPoint,
        /** 用于 Chunk 去重与确定性扰动的任务 UUID。 */ UUID missionId,
        /** 任务内从 0 开始的轮次。 */ int roundIndex,
        /** 服务端权威随机种子。 */ long authoritativeSeed,
        /** 本发相对世界的计划生成 Tick。 */ long expectedSpawnTick,
        /** 本任务最多同时占用的唯一预加载 Chunk。 */ int maxLoadedChunksPerMission) {

    public RVP_FireSupportDeliveryContext {
        if (level == null || owner == null || weaponData == null || impactPoint == null || missionId == null) {
            throw new IllegalArgumentException("投送世界、owner、武器、落点和任务 ID 不能为空");
        }
        if (owner.level() != level || roundIndex < 0 || maxLoadedChunksPerMission < 1
                || !Double.isFinite(impactPoint.x()) || !Double.isFinite(impactPoint.z())) {
            throw new IllegalArgumentException("投送上下文的世界归属、轮次、Chunk 上限或落点非法");
        }
    }
}
