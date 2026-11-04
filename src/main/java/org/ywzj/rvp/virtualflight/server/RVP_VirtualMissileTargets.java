package org.ywzj.rvp.virtualflight.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** 虚拟记录的目标适配：保留真实弹体类型、信号和碰撞规则，不把计算对象加入世界。 */
public final class RVP_VirtualMissileTargets {
    /**
     * 虚拟目标地面高度查询总开关：暂时关闭，恢复无视地形的扫描/索敌/拦截行为。
     * 高度图与基础地形估算代码全部保留；重新开启需将此值改为 true 后重新编译。
     */
    private static final boolean VIRTUAL_TARGET_TERRAIN_HEIGHT_ENABLED = false;

    /** 工具类不允许实例化。 */
    private RVP_VirtualMissileTargets() {}

    /** 合并世界实体与同维度虚拟目标，供雷达、导引头和 Gunner 使用；不申请区块。 */
    public static List<Entity> allEntities(ServerLevel level) {
        List<Entity> result = new ArrayList<>();
        level.getEntities().getAll().forEach(result::add);
        // 调用管理器取得同维度的权威目标快照。
        result.addAll(RVP_VirtualMissileManager.targets(level));
        return result;
    }

    /** 按 ID 查询真实或虚拟目标；客户端广播对象只经安全桥读取。 */
    @Nullable
    public static Entity resolve(Level level, int entityId) {
        Entity entity = level.getEntity(entityId);
        if (entity != null && entity.isAlive()) return entity;
        if (level instanceof ServerLevel serverLevel) {
            // 调用同维度记录查询，运行时 ID 不会被当成持久化身份。
            for (Entity target : RVP_VirtualMissileManager.targets(serverLevel)) {
                if (target.getId() == entityId) return target;
            }
            return null;
        }
        // 调用分端桥，公共代码不引用 LocalVehiclePlayer 等客户端类型。
        return RVP_ClientActionsAccess.resolveRemoteRadarTarget(entityId);
    }

    /** 为既有碰撞盒查询补入虚拟弹体；原过滤器仍负责敌我、弹种和引信限制。 */
    public static List<Entity> merge(Level level, AABB box, List<? extends Entity> base,
                                     Predicate<Entity> filter) {
        // 调用管理器只读取当前维度记录，客户端不会参与权威碰撞。
        List<? extends Entity> virtual = level instanceof ServerLevel serverLevel
                ? RVP_VirtualMissileManager.targets(serverLevel) : List.of();
        return mergeCandidates(base, virtual, box, filter);
    }

    /** 弹体命中链同时补齐巨型载具与虚拟弹体，直击、扫掠近炸和普通近炸共用。 */
    public static List<Entity> mergeWithVehicleIndex(Level level, AABB box, List<? extends Entity> base,
                                                    Predicate<Entity> filter) {
        // 调用原有巨型载具补筛，再追加虚拟记录，保持原过滤器和碰撞几何不变。
        return merge(level, box, org.ywzj.rvp.util.RVP_ServerVehicleIndex.mergeWithVehicleIndex(
                level, box, base, filter), filter);
    }

    /** 按实体 ID 去重、包围盒相交及原过滤器合并候选，保留原查询顺序。 */
    static List<Entity> mergeCandidates(List<? extends Entity> base, Iterable<? extends Entity> virtual,
                                         AABB box, Predicate<Entity> filter) {
        List<Entity> result = new ArrayList<>(base);
        Set<Integer> ids = new HashSet<>();
        base.forEach(entity -> ids.add(entity.getId()));
        for (Entity entity : virtual) {
            if (entity.isAlive() && !ids.contains(entity.getId())
                    && entity.getBoundingBox().intersects(box) && filter.test(entity)) {
                ids.add(entity.getId());
                result.add(entity);
            }
        }
        return result;
    }

    /** 是否暂时忽略该虚拟目标的地面高度；真实实体继续沿用既有地形判断。 */
    public static boolean ignoresTerrainHeight(@Nullable Entity target) {
        return !VIRTUAL_TARGET_TERRAIN_HEIGHT_ENABLED
                && target instanceof RVP_BaseBullet bullet && bullet.isVirtualMidcourseTarget();
    }

    /** 虚拟目标查询关闭时返回协议占位值 0；开启时保留原高度图/基础地形估算路径。 */
    public static int groundHeight(Entity target, Heightmap.Types type) {
        // 调用统一开关判定，在读取客户端高度缓存、区块状态或地形生成器之前直接退出。
        if (ignoresTerrainHeight(target)) return 0;
        Level level = target.level();
        if (target instanceof RVP_BaseBullet bullet && bullet.isVirtualMidcourseTarget()
                && bullet.getVirtualMidcourseGroundHeight() != null) {
            // 读取服务端同步的虚拟目标高度，客户端不查询尚未加载的目标地形。
            return bullet.getVirtualMidcourseGroundHeight();
        }
        if (target instanceof RVP_BaseBullet bullet && bullet.isVirtualMidcourseTarget()
                && level instanceof ServerLevel serverLevel && !level.hasChunkAt(target.blockPosition())) {
            return serverLevel.getChunkSource().getGenerator().getFirstFreeHeight(
                    target.getBlockX(), target.getBlockZ(), type, serverLevel,
                    serverLevel.getChunkSource().randomState());
        }
        return level.getHeight(type, target.getBlockX(), target.getBlockZ());
    }

    /** 在服务端真实/虚拟交接时更新已有引用；不凭空建立新接触或新锁定。 */
    public static void replaceReferences(Level level, Entity previous, @Nullable Entity replacement) {
        if (level instanceof ServerLevel serverLevel) {
            // 调用已加载实体索引，交接不读取或加载远方区块。
            // 所有类型都可虚拟后，另一枚虚拟拦截弹也可能持有旧目标引用，必须一起交接。
            replaceReferences(allEntities(serverLevel), previous, replacement);
        }
    }

    /** 同一飞行 UUID 的接触、火控锁定和已发射弹体目标一起交接，客户端可复用此入口。 */
    public static void replaceReferences(Iterable<Entity> observers, Entity previous, @Nullable Entity replacement) {
        for (Entity observer : observers) {
            if (observer instanceof AbstractVehicle vehicle) {
                // 调用本体部件枚举，覆盖搜索雷达、跟踪雷达和各武器站。
                for (PartUnit<?> part : vehicle.getPartUnits()) {
                    if (part instanceof RadarUnit radar) {
                        RadarUnit.DetectedObject detected = radar.getDetectedEntities().get(previous.getId());
                        if (detected != null && sameFlight(detected.entity, previous)) {
                            // 调用本体探测写入，使接触对象和位置同步切换。
                            if (replacement == null) radar.getDetectedEntities().remove(previous.getId());
                            else radar.detect(replacement);
                        }
                        if (sameFlight(radar.getLockedEntity(), previous)) radar.setLockedEntity(replacement);
                    } else if (part instanceof WeaponUnit weapon
                            && sameFlight(weapon.getLockedEntity(), previous)) {
                        // 调用本体火控锁定入口，让炮塔继续瞄准同一飞行目标。
                        weapon.setLockedEntity(replacement);
                    }
                }
            }
            if (observer instanceof RVP_BaseBullet bullet && sameFlight(bullet.getTargetEntity(), previous)) {
                // 调用弹体目标入口，保留同 UUID 的目标速度历史，继续拦截。
                bullet.setTargetEntity(replacement);
            }
        }
    }

    /** 仅交接同维度、同 UUID 的目标，避免实体 ID 重用后串到另一枚弹。 */
    private static boolean sameFlight(@Nullable Entity candidate, Entity previous) {
        return candidate != null && candidate.level() == previous.level()
                && candidate.getUUID().equals(previous.getUUID());
    }
}
