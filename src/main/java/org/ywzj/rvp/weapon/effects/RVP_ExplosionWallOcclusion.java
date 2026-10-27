package org.ywzj.rvp.weapon.effects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.debug.RVP_ExplosionDebug;

import java.util.HashMap;
import java.util.Map;

/**
 * 爆炸"墙后衰减"的遮挡快照。
 *
 * <p>{@link RVP_VehicleExplosion#explode(java.util.List)} 在结算任何伤害与方块破坏之前
 * 调用 {@link #snapshot} 算一次"爆心 → 半径内每个实体"的遮挡结论，随后
 * {@code hurt} 按实体 ID 取用：连线被方块阻断则伤害 ×{@link #WALL_DAMAGE_FACTOR}。</p>
 *
 * <p><b>为什么必须先快照：</b>{@code destroy_radius} 拆分模式下地形爆炸 A 会先于杀伤爆炸 B
 * 把方块炸掉；即便不拆分，本体沿用下来的顺序也是先 {@code hurt} 后 {@code destroyBlocks}。
 * 若等到结算时现场射线，墙体已经消失，判定恒为无遮挡。</p>
 *
 * <p><b>端点朝向判定（2026-10-25 重写）</b>：爆心常嵌在弹着点方块里（贴墙/贴地），直接 clip 会
 * 命中自己所在方块造成误判。做法是沿连线方向探 {@value #ENDPOINT_PROBE} 格看该方块在哪一侧：
 * 在连线**前方**（墙）⇒ 遮挡；在端点**身后**（地面）⇒ 只推出到方块外、不算遮挡。
 * 老版本固定推进 1.5 格会把嵌在墙里的爆心推到墙的另一侧，"朝墙炸判无遮挡 / 炸地板判遮挡"同时做反。</p>
 */
public final class RVP_ExplosionWallOcclusion {

    /** 连线被方块阻断时的伤害倍率。 */
    public static final float WALL_DAMAGE_FACTOR = 0.5F;

    /**
     * 端点朝向探针距离：判断"端点所在方块在连线的哪一侧"用。
     *
     * <p>沿连线方向探 0.05 格：
     * <ul>
     *   <li>仍在同一方块 ⇒ 该方块就在连线**前方**（爆心嵌在墙里、目标嵌在墙里）⇒ 遮挡；</li>
     *   <li>离开该方块 ⇒ 方块在端点**身后**（爆心贴地、目标贴地）⇒ 只需推出到方块外，不算遮挡。</li>
     * </ul>
     * 早期版本固定沿目标方向推进 1.5 格，会把嵌在墙里的爆心直接推到墙的另一侧，导致
     * "朝墙炸判成无遮挡 / 炸地板判成遮挡"两个场景同时做反（2026-10-25 实测日志暴露）。</p>
     */
    private static final double ENDPOINT_PROBE = 0.05D;

    /** 诊断日志限流：同一 tick 只记一条（仅开关打开时写，见 {@code /rvpdebug flags explosion}）。 */
    private static long lastLoggedTick = Long.MIN_VALUE;

    private RVP_ExplosionWallOcclusion() {}

    /**
     * 采集遮挡快照；仅服务端返回结果（伤害由服务端结算）。
     *
     * <p>遍历范围与 {@code hurt} 一致（同半径 AABB + {@code getEntitiesOfClass(Entity.class, ...)}），
     * 保证伤害结算时每个实体都能命中缓存。</p>
     *
     * @param flightDir 炮弹飞行方向（爆前动量），用于起点探针；可为 null（回退连线反向）
     * @return 实体 ID → 伤害倍率；无遮挡的实体可能不在表中（取用方按 1.0 兜底）
     */
    public static Map<Integer, Float> snapshot(Level level, Vec3 center, double radius,
                                               @org.jetbrains.annotations.Nullable Vec3 flightDir) {
        if (level == null || level.isClientSide || center == null || radius <= 0.0D) {
            return Map.of();
        }
        Map<Integer, Float> factors = new HashMap<>();
        AABB box = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        int scanned = 0;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
            scanned++;
            float factor = compute(level, center, entity, flightDir);
            if (factor < 1.0F) {
                factors.put(entity.getId(), factor);
            }
        }
        // 诊断：默认关闭，只在 /rvpdebug flags explosion on 时写独立文件 logs/rvp_explosion_debug.log，
        // 绝不进 latest.log；同一 tick 只记一条。
        if (RVP_ExplosionDebug.enabled()) {
            long tick = level.getGameTime();
            if (lastLoggedTick != tick) {
                lastLoggedTick = tick;
                RVP_ExplosionDebug.wallOcclusion(level, center, radius, scanned, factors);
            }
        }
        return factors;
    }

    /** 兼容重载：无飞行方向时按连线反向探针。 */
    public static Map<Integer, Float> snapshot(Level level, Vec3 center, double radius) {
        return snapshot(level, center, radius, null);
    }

    /**
     * 单实体遮挡倍率：给"不经 {@link RVP_VehicleExplosion} 的爆炸伤害"用（近炸引信直伤）。
     *
     * <p>近炸直伤在 {@code RVP_BaseBullet} 里直接 {@code hurt}，绕过 VehicleExplosion，
     * 因此必须单独乘这道衰减，否则带近炸引信的榴弹（如 DTB-251、XM1127）在墙后依旧满伤。</p>
     */
    public static float factorFor(Level level, Vec3 center, @org.jetbrains.annotations.Nullable Entity entity) {
        return factorFor(level, center, entity, null);
    }

    /** 带炮弹飞行方向的版本：起点探针沿飞行反方向（贴入射面语义）。 */
    public static float factorFor(Level level, Vec3 center, @org.jetbrains.annotations.Nullable Entity entity,
                                  @org.jetbrains.annotations.Nullable Vec3 flightDir) {
        if (level == null || level.isClientSide || center == null || entity == null) {
            return 1.0F;
        }
        return compute(level, center, entity, flightDir);
    }

    /** 爆心到实体包围盒中心连线是否被方块阻断。 */
    private static float compute(Level level, Vec3 center, Entity entity, @org.jetbrains.annotations.Nullable Vec3 flightDir) {
        Vec3 target = entity.getBoundingBox().getCenter();
        Vec3 delta = target.subtract(center);
        double distance = delta.length();
        if (distance < 1.0E-4D) {
            return 1.0F;
        }
        Vec3 direction = delta.scale(1.0D / distance);
        // 起点探针方向取"炮弹飞行方向的反向"：弹着点嵌在方块里时，沿入射反方向探针即可判断
        // 爆心埋得多深——仍在同一方块 ⇒ 这层墙就在弹着点上（遮挡）；离开 ⇒ 起点贴到入射面外侧。
        // 飞行方向缺失时回退到连线反向。
        Vec3 probe = flightDir != null && flightDir.lengthSqr() > 1.0E-6D
                ? flightDir.normalize().scale(-1.0D)
                : direction.scale(-1.0D);

        Vec3 start = resolveStart(level, center, probe);
        if (start == null) {
            // 爆心嵌在弹着点方块内部（炮弹已钻入墙体/地面才炸）：这层方块就在爆心与目标之间
            return WALL_DAMAGE_FACTOR;
        }
        Vec3 end = escapeEndpoint(level, target, direction.scale(-1.0D));
        if (start.distanceToSqr(end) < 1.0E-6D) {
            return 1.0F;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        return hit.getType() == HitResult.Type.MISS ? 1.0F : WALL_DAMAGE_FACTOR;
    }

    /**
     * 解析射线起点；返回 {@code null} 表示爆心仍埋在弹着点方块内部（应直接判遮挡）。
     *
     * <p>沿 {@code probe}（炮弹飞行反方向）探 {@value #ENDPOINT_PROBE} 格：
     * <ul>
     *   <li>仍在同一固体方块 ⇒ 爆心埋在该方块内部（不是贴着入射面）⇒ 遮挡；</li>
     *   <li>已离开该方块 ⇒ 爆心贴着入射面 ⇒ 起点推到入射面外侧，正常走 clip。</li>
     * </ul>
     */
    @org.jetbrains.annotations.Nullable
    private static Vec3 resolveStart(Level level, Vec3 center, Vec3 probe) {
        if (!isSolidAt(level, center)) {
            return center;
        }
        BlockPos pos = BlockPos.containing(center);
        if (BlockPos.containing(center.add(probe.scale(ENDPOINT_PROBE))).equals(pos)) {
            return null;
        }
        return center.add(probe.scale(ENDPOINT_PROBE));
    }

    /** 目标端点避让：嵌在方块里则沿反方向推出到方块外（推出后 clip 命中它仍算遮挡）。 */
    private static Vec3 escapeEndpoint(Level level, Vec3 point, Vec3 back) {
        if (!isSolidAt(level, point)) {
            return point;
        }
        BlockPos pos = BlockPos.containing(point);
        if (BlockPos.containing(point.add(back.scale(ENDPOINT_PROBE))).equals(pos)) {
            return point;
        }
        return point.add(back.scale(ENDPOINT_PROBE));
    }

    /** 该点是否落在有碰撞箱的方块内（空气/无碰撞装饰不算遮挡）。 */
    private static boolean isSolidAt(Level level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }
}
