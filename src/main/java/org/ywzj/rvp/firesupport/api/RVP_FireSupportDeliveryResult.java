package org.ywzj.rvp.firesupport.api;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 一发炮火投送的状态与可选真实弹体。 */
public record RVP_FireSupportDeliveryResult(
        /** 任务调度器用于等待、重试或记账的状态。 */ Status status,
        /** 成功时已经加入世界的 RVP 弹体。 */ @Nullable RVP_BaseBullet projectile,
        /** 成功时的权威生成位置。 */ @Nullable Vec3 spawnPosition) {
    /** @return 是否成功生成并加入世界。 */
    public boolean delivered() { return status == Status.DELIVERED; }

    public enum Status {
        DELIVERED,
        TOO_EARLY,
        WAITING_FOR_CHUNK,
        CHUNK_LIMIT_EXCEEDED,
        CHUNK_WAIT_TIMED_OUT,
        OUTSIDE_WORLD_BORDER,
        OUTSIDE_BUILD_HEIGHT,
        UNSUPPORTED_WEAPON,
        SPAWN_FAILED,
        INVALID_CONTEXT
    }
}
