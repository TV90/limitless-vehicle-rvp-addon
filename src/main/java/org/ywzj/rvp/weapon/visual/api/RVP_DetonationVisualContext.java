package org.ywzj.rvp.weapon.visual.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务端解析爆炸视觉所需的权威上下文。
 *
 * @param level 事件所在服务端世界
 * @param position 最终爆心
 * @param baseExplosionRadius 已解析后的最终爆炸半径（格）
 * @param detonationKind 引爆类型名称，用于未来解析器扩展
 * @param ownerUuid 发射者 UUID；无发射者时为 {@code null}
 * @param ownerEntityId 发射者实体 ID；无发射者时为 {@code -1}
 * @param startGameTime 服务端开始世界时间（tick）
 */
public record RVP_DetonationVisualContext(
        ServerLevel level,
        Vec3 position,
        float baseExplosionRadius,
        String detonationKind,
        UUID ownerUuid,
        int ownerEntityId,
        long startGameTime
) {
    public RVP_DetonationVisualContext {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(detonationKind, "detonationKind");
    }
}
