package org.ywzj.rvp.hitl;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;

import java.util.Objects;

/** 服务端确认的 TV 锁定结果；复用链路包同步，客户端不凭按键猜测是否接纳。
 * @param status 锁定/脱锁原因
 * @param entityId 被持续跟踪的实体 ID，无实体时为 -1
 * @param point 最后可用的世界瞄准点；无制导点时为 null
 * @param obb 选中的稳定骨身份，仅实体锁有效
 * @param noRedesignateTicks DIRCM 剩余禁锁刻数（20 tick/秒）
 */
public record RVP_HitlLockSnapshot(Status status, int entityId, @Nullable Vec3 point,
        @Nullable RVP_NamedVehicleObbs.Key obb, int noRedesignateTicks) {
    /** 空锁定，用于新镜头及结束时清理。 */
    public static final RVP_HitlLockSnapshot EMPTY = new RVP_HitlLockSnapshot(Status.NONE, -1, null, null, 0);

    /** 脱锁后只保留固定点；DIRCM 禁锁期不呈现残留实体框。 */
    public enum Status { NONE, ENTITY, POINT, SMOKE, OCCLUDED, SPEED, DIRCM }

    /** 身份/状态变化立即同步；坐标及计时变化可以合并为每 5 tick 一包。 */
    public boolean sameIdentity(RVP_HitlLockSnapshot other) {
        return other != null && status == other.status && entityId == other.entityId
                && Objects.equals(obb, other.obb) && (point == null) == (other.point == null)
                && (noRedesignateTicks == 0) == (other.noRedesignateTicks == 0);
    }
}
