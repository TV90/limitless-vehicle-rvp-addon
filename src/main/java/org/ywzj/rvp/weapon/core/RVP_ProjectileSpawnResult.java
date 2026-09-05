package org.ywzj.rvp.weapon.core;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 一次核心实体生成的明确结果，避免把“构造成功但入世失败”误报为成功。 */
public record RVP_ProjectileSpawnResult(
        /** 生成结果状态。 */ Status status,
        /** 已构造弹体；仅 SPAWNED 保证已经加入世界。 */ @Nullable RVP_BaseBullet projectile) {
    /** @return 是否成功加入世界并已预热动态路径。 */
    public boolean spawned() { return status == Status.SPAWNED; }

    public enum Status {
        /** 弹体已加入服务端世界。 */ SPAWNED,
        /** LASER/TARGETING_POD 等不是实体弹体。 */ UNSUPPORTED_KIND,
        /** 实体类型缺失或构造失败。 */ ENTITY_CREATION_FAILED,
        /** 世界拒绝加入实体。 */ ADD_TO_WORLD_REJECTED
    }
}
