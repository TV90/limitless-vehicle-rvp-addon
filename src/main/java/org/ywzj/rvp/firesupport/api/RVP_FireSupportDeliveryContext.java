package org.ywzj.rvp.firesupport.api;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportImpactPoint;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.jetbrains.annotations.Nullable;

/** 一发炮火投送所需的不可变服务端上下文。 */
public record RVP_FireSupportDeliveryContext(
        /** 任务所在服务端世界。 */ ServerLevel level,
        /** 伤害与击杀归属的任务发起者。 */ LivingEntity owner,
        /** 创建任务时从真实索引解析并由任务计划持有的 RVP 武器数据。 */ RVP_WeaponData weaponData,
        /** pattern 产生的权威 XZ 计划落点。 */ RVP_FireSupportImpactPoint impactPoint,
        /** 任务原始目标锚点 X，固定炮位以此而非单发散布点为基准。 */ double anchorX,
        /** 任务原始目标锚点 Z，固定炮位以此而非单发散布点为基准。 */ double anchorZ,
        /** 条状/徐进落区方向，单位度，0 指向 +Z。 */ double patternHeadingDegrees,
        /** 弹体从发射点飞向目标的独立入场方向，单位度，0 指向 +Z。 */ double inboundHeadingDegrees,
        /** 用于 Chunk 去重与确定性扰动的任务 UUID。 */ UUID missionId,
        /** 任务内从 0 开始的轮次。 */ int roundIndex,
        /** 服务端权威随机种子。 */ long authoritativeSeed,
        /** 本发相对世界的计划发射或释放 Tick。 */ long scheduledTick,
        /** 本任务最多同时占用的唯一预加载 Chunk。 */ int maxLoadedChunksPerMission,
        /** 空中支援任务当前绑定的真实飞机；普通投送为 null。 */ @Nullable AbstractVehicle sourceVehicle,
        /** 空中支援任务当前挂架的世界坐标；普通投送为 null。 */ @Nullable Vec3 sourcePosition,
        /** 空中支援任务当前飞机的插件权威世界速度；普通投送为 null。 */ @Nullable Vec3 sourceMotion,
        /** 空中导弹使用的地面指定目标；非导弹或普通投送为 null。 */ @Nullable Vec3 designatedTarget,
        /** 是否把飞机当前速度叠加到投射物初速度。 */ boolean inheritVehicleVelocity) {

    /** 保留普通投送调用方的便捷构造形式。 */
    public RVP_FireSupportDeliveryContext(ServerLevel level, LivingEntity owner, RVP_WeaponData weaponData,
                                          RVP_FireSupportImpactPoint impactPoint, double anchorX, double anchorZ,
                                          double patternHeadingDegrees, double inboundHeadingDegrees, UUID missionId,
                                          int roundIndex, long authoritativeSeed, long scheduledTick,
                                          int maxLoadedChunksPerMission) {
        this(level, owner, weaponData, impactPoint, anchorX, anchorZ, patternHeadingDegrees,
                inboundHeadingDegrees, missionId, roundIndex, authoritativeSeed, scheduledTick,
                maxLoadedChunksPerMission, null, null, null, null, false);
    }

    public RVP_FireSupportDeliveryContext {
        if (level == null || owner == null || weaponData == null || impactPoint == null || missionId == null) {
            throw new IllegalArgumentException("投送世界、owner、武器、落点和任务 ID 不能为空");
        }
        if (owner.level() != level || roundIndex < 0 || maxLoadedChunksPerMission < 1
                || !Double.isFinite(impactPoint.x()) || !Double.isFinite(impactPoint.z())
                || !Double.isFinite(anchorX) || !Double.isFinite(anchorZ)
                || !Double.isFinite(patternHeadingDegrees) || !Double.isFinite(inboundHeadingDegrees)) {
            throw new IllegalArgumentException("投送上下文的世界归属、轮次、Chunk 上限或落点非法");
        }
        if (sourceVehicle != null && sourceVehicle.level() != level) {
            throw new IllegalArgumentException("投送载具必须位于生成世界");
        }
        if (sourcePosition != null && !finite(sourcePosition)) {
            throw new IllegalArgumentException("投送挂架坐标必须为有限数值");
        }
        if (sourceMotion != null && !finite(sourceMotion)) {
            throw new IllegalArgumentException("投送载具速度必须为有限数值");
        }
        if (designatedTarget != null && !finite(designatedTarget)) {
            throw new IllegalArgumentException("指定目标坐标必须为有限数值");
        }
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
