package org.ywzj.rvp.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.Commands;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * [RVP] 载具 OBB 命中扫描调试（2026-10-01，排查"大 OBB 导弹过穿"）：
 * 复刻本体导弹命中链的真实路径（{@code AmmoEntity.tickHit → EntityUtil.findEntityOnPath
 * → getHitResult → VectorUtil.hitOBB(getOBBs())}），把链路上每一步的运行时数据摊开：
 *
 * <ul>
 *   <li>{@code /rvpobb probe [backoff]}：沿执行者视线做一次合成扫描——
 *       报告候选载具、其 {@code getOBBs()} 总数、主物理块（mainCubeOBB）是否在列表内
 *       （盲区核查）、逐块 OBB 的中心/尺寸/体积、合成段逐块 clip 的命中结果（索引/t/命中点），
 *       以及当前世界坐标的 float32 量化值（数值精度核查）。聊天给摘要，全量写
 *       {@code logs/obb_scan.log}。</li>
 *   <li>{@code /rvpobb watch}：开关式实时监听——开启后每服务端 tick 扫描玩家附近的所有
 *       投射物，对其"与本载具实体 AABB 相交的段"执行与导弹一致的 OBB 扫描，把结果写
 *       {@code logs/obb_scan.log}；重点记录"段与实体 AABB 相交但 OBB 零命中"的过穿特征帧。</li>
 * </ul>
 *
 * <p>仅服务端执行；纯只读探测，不施加任何伤害/不修改任何状态。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_ObbScanDebug {

    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("obb_scan.log");
    /** watch 模式下投射物与任意玩家的最大关注距离（格）。 */
    private static final double WATCH_RADIUS = 512.0;
    private static volatile boolean watchEnabled = false;
    /** 上一次 tick 看到的投射物：id → [最后位置, 最后 delta, 首次见到 tick, 首次见到时是否载具旁]。 */
    private static final java.util.Map<Integer, double[]> lastSeen = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> firstSeenTick = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> lastTickCount = new java.util.HashMap<>();

    private RVP_ObbScanDebug() {}

    // ───────────────────────── 命令注册 ─────────────────────────

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("rvpobb")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("probe")
                                .executes(ctx -> probe(ctx.getSource().getPlayerOrException(), 0.0))
                                .then(Commands.argument("backoff", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 512.0))
                                        .executes(ctx -> probe(ctx.getSource().getPlayerOrException(),
                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "backoff")))))
                        .then(Commands.literal("watch")
                                .executes(ctx -> {
                                    watchEnabled = !watchEnabled;
                                    lastSeen.clear();
                                    firstSeenTick.clear();
                                    lastTickCount.clear();
                                    String state = watchEnabled ? "开启" : "关闭";
                                    log("=== watch " + state + " by " + ctx.getSource().getTextName() + " ===");
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[RVP] OBB 过穿监听已" + state + "，日志: " + LOG_PATH), false);
                                    return 1;
                                }))
                        .then(Commands.literal("querytest")
                                .executes(ctx -> querytest(ctx.getSource().getPlayerOrException()))));
    }

    /**
     * [RVP] §48 盲区可视化：对执行者 256 格内最近的载具，在其全部 OBB 中心、包络中心与
     * 包络 8 角各放一个 2 格小查询盒跑<b>原版</b> {@code getEntitiesOfClass}（与命中链
     * 修复前同款机制），报告各采样点能否找到该载具——离载具 {@code blockPosition}
     * 注册分节超过 ±2 分节的采样点 = 盲区（修复前"未找到"，注册表补筛后命中链不再依赖）。
     */
    private static int querytest(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        AbstractVehicle target = null;
        double best = 256.0 * 256.0;
        for (AbstractVehicle v : org.ywzj.rvp.util.RVP_ServerVehicleIndex.getVehicles(level)) {
            if (!v.isAlive()) {
                continue;
            }
            double d = v.distanceToSqr(player);
            if (d < best) {
                best = d;
                target = v;
            }
        }
        if (target == null) {
            player.sendSystemMessage(Component.literal("[RVP] 256 格内没有已注册载具"));
            return 1;
        }
        AABB env = target.getBoundingBox();
        log("=== querytest vehicle=" + target.getId() + "(" + target.getVehicleId().getPath() + ")"
                + " blockPos=" + target.blockPosition() + " envelope=" + fmt(new Vec3(env.minX, env.minY, env.minZ))
                + "~" + fmt(new Vec3(env.maxX, env.maxY, env.maxZ)) + " ===");
        List<Vec3> points = new ArrayList<>();
        for (OBB obb : target.getOBBs()) {
            Vector3f c = obb.center();
            points.add(new Vec3(c.x, c.y, c.z));
        }
        if (target.getMainCubeOBB() != null) {
            Vector3f c = target.getMainCubeOBB().obb().center();
            points.add(new Vec3(c.x, c.y, c.z));
        }
        points.add(env.getCenter());
        for (double cx : new double[]{env.minX, env.maxX}) {
            for (double cy : new double[]{env.minY, env.maxY}) {
                for (double cz : new double[]{env.minZ, env.maxZ}) {
                    points.add(new Vec3(cx, cy, cz));
                }
            }
        }
        int found = 0;
        int total = 0;
        for (Vec3 p : points) {
            AABB smallBox = AABB.ofSize(p, 2, 2, 2);
            boolean hit = false;
            for (AbstractVehicle v : level.getEntitiesOfClass(AbstractVehicle.class, smallBox, vv -> vv.isAlive())) {
                if (v == target) {
                    hit = true;
                    break;
                }
            }
            total++;
            if (hit) {
                found++;
            }
            log("   point=" + fmt(p) + " 原版getEntities找到=" + hit + (hit ? "" : "  ★盲区"));
        }
        log("=== querytest 汇总: 采样=" + total + " 找到=" + found + " 盲区=" + (total - found) + " ===");
        player.sendSystemMessage(Component.literal("[RVP] querytest 载具 " + target.getId()
                + "（blockPosition=" + target.blockPosition().toShortString() + "）: 采样 "
                + total + " 点，原版查询找到 " + found + "，盲区 " + (total - found)
                + "（详情见 " + LOG_PATH + "；注册表补筛后命中链不受盲区影响）"));
        return 1;
    }

    // ───────────────────────── probe：合成段探测 ─────────────────────────

    private static int probe(ServerPlayer player, double backoff) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        // 段起点 = 眼睛沿视线反向回退 backoff 格（模拟弹从远处飞来），终点 = 眼睛 + 视线×256
        Vec3 start = player.getEyePosition().subtract(look.scale(backoff));
        Vec3 end = player.getEyePosition().add(look.scale(256.0));

        log("=== probe by " + player.getGameProfile().getName()
                + " backoff=" + backoff
                + " start=" + fmt(start) + " end=" + fmt(end) + " ===");

        // 候选收集：与本体 findEntityOnPath 同款盒子（弹体盒近似用玩家眼盒替代）
        AABB query = new AABB(start, end).inflate(1.0);
        // [RVP] §48：与修复后命中链一致——原版查询 + 注册表补盲区合并
        List<Entity> vehicles = org.ywzj.rvp.util.RVP_ServerVehicleIndex.mergeWithVehicleIndex(
                level, query,
                level.getEntitiesOfClass(AbstractVehicle.class, query, v -> v.isAlive() && !v.isSpectator()),
                v -> v.isAlive() && !v.isSpectator());
        player.sendSystemMessage(Component.literal("[RVP] 视线段上候选载具: " + vehicles.size()));
        if (vehicles.isEmpty()) {
            player.sendSystemMessage(Component.literal("[RVP] 无候选——瞄的框所在载具不在候选盒内，记录见日志"));
            return 1;
        }

        for (Entity vehicleEntity : vehicles) {
            if (!(vehicleEntity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            probeVehicle(player, vehicle, start, end);
        }
        return 1;
    }

    /** 对单台载具做全量 OBB 摊开 + 合成段逐块 clip，输出到聊天摘要 + 日志全量。 */
    private static void probeVehicle(ServerPlayer player, AbstractVehicle vehicle, Vec3 start, Vec3 end) {
        List<OBB> obbs = vehicle.getOBBs();
        OBB mainCube = vehicle.getMainCubeOBB() == null ? null : vehicle.getMainCubeOBB().obb();

        // 主物理块是否在 getOBBs() 命中列表内（盲区核查：按中心+extents 值匹配）
        boolean mainInList = false;
        if (mainCube != null) {
            for (OBB obb : obbs) {
                if (obb == mainCube || isSameObb(obb, mainCube)) {
                    mainInList = true;
                    break;
                }
            }
        }

        log("-- vehicle id=" + vehicle.getId() + " type=" + vehicle.getVehicleId()
                + " pos=" + fmt(vehicle.position())
                + " posFloat32=(x=" + f32(vehicle.position().x)
                + ",y=" + f32(vehicle.position().y)
                + ",z=" + f32(vehicle.position().z) + ")"
                + " remote=" + vehicle.remote
                + " getOBBs=" + obbs.size()
                + " mainInList=" + mainInList);

        // 逐块清单（按体积降序，块索引为 getOBBs() 中的原始下标）
        for (int i = 0; i < obbs.size(); i++) {
            OBB obb = obbs.get(i);
            Vector3f c = obb.center();
            Vector3f e = obb.extents();
            double volume = (double) e.x * e.y * e.z * 8.0;
            log("   obb[" + i + "] center=(" + c.x + "," + c.y + "," + c.z + ")"
                    + " halfExtents=(" + e.x + "," + e.y + "," + e.z + ")"
                    + " full=(" + (e.x * 2) + "x" + (e.y * 2) + "x" + (e.z * 2) + ")"
                    + " vol=" + String.format("%.1f", volume));
        }
        if (mainCube != null && !mainInList) {
            Vector3f c = mainCube.center();
            Vector3f e = mainCube.extents();
            log("   MAIN(不在命中列表!) center=(" + c.x + "," + c.y + "," + c.z + ")"
                    + " halfExtents=(" + e.x + "," + e.y + "," + e.z + ")"
                    + " full=(" + (e.x * 2) + "x" + (e.y * 2) + "x" + (e.z * 2) + ")");
        }

        // 合成段逐块 clip：与导弹链同一入口
        List<OBB> withMain = new ArrayList<>(obbs);
        if (mainCube != null && !mainInList) {
            withMain.add(mainCube); // 对照组：补上主块再看能否命中
        }
        int crossings = 0;
        for (int i = 0; i < withMain.size(); i++) {
            OBB obb = withMain.get(i);
            Vector3f from = start.toVector3f();
            Vector3f to = end.toVector3f();
            var hit = obb.clip(from, to).orElse(null);
            if (hit == null) {
                continue;
            }
            crossings++;
            Vector3f e = obb.extents();
            log("   CROSS obb[" + i + "]"
                            + (mainCube != null && obb == mainCube ? "(MAIN)" : "")
                            + " halfExtents=(" + e.x + "," + e.y + "," + e.z + ")"
                            + " hitPos=(" + hit.x + "," + hit.y + "," + hit.z + ")"
                            + " t=" + String.format("%.3f", new Vec3(hit.x, hit.y, hit.z).distanceTo(start) / Math.max(start.distanceTo(end), 1.0E-6)));
        }
        boolean mainCrossed = mainCube != null && mainCube.clip(start.toVector3f(), end.toVector3f()).isPresent();
        log("   crossings=" + crossings + " mainCrossed=" + mainCrossed);

        // 聊天摘要
        player.sendSystemMessage(Component.literal("[RVP] 载具 " + vehicle.getId() + " ("
                + vehicle.getVehicleId().getPath() + "): OBB=" + obbs.size()
                + " 主块在命中列表=" + (mainCube == null ? "无主块" : mainInList)
                + " 视线穿过=" + crossings + " 块"
                + (mainCube != null ? " 主块自身相交=" + mainCrossed : "")));
    }

    // ───────────────────────── watch：实时弹道监听 ─────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !watchEnabled) {
            return;
        }
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        java.util.Set<Integer> seenThisTick = new java.util.HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof Projectile projectile) || !projectile.isAlive()) {
                    continue;
                }
                if (!nearAnyPlayer(projectile)) {
                    continue;
                }
                seenThisTick.add(projectile.getId());
                Vec3 start = projectile.position();
                Vec3 delta = projectile.getDeltaMovement();
                if (delta.lengthSqr() < 1.0E-12) {
                    continue;
                }
                Vec3 end = start.add(delta);
                AABB query = new AABB(start, end).inflate(1.0);
                List<Entity> vehicles = org.ywzj.rvp.util.RVP_ServerVehicleIndex.mergeWithVehicleIndex(
                        level, query,
                        level.getEntitiesOfClass(AbstractVehicle.class, query, v -> v.isAlive() && !v.isSpectator()),
                        v -> v.isAlive() && !v.isSpectator());
                if (vehicles.isEmpty()) {
                    continue;
                }
                firstSeenTick.putIfAbsent(projectile.getId(), level.getGameTime());
                for (Entity vehicleEntity : vehicles) {
                    if (!(vehicleEntity instanceof AbstractVehicle vehicle)) {
                        continue;
                    }
                    // 排除射手自己的载具：本体命中规则（canDamageEntity）本来就排除，
                    // watch 不复刻会把"导弹从自己载具旁飞过"记成假阳性过穿
                    Entity owner = projectile.getOwner();
                    if (owner != null && (owner == vehicle || owner.getVehicle() == vehicle)) {
                        continue;
                    }
                    watchScanVehicle(level, projectile, vehicle, start, end);
                }
                lastSeen.put(projectile.getId(),
                        new double[]{start.x, start.y, start.z, delta.x, delta.y, delta.z});
                lastTickCount.put(projectile.getId(), projectile.tickCount);
            }
        }
        // 消失检测：上一 tick 还在、本 tick 不见了的弹——重扫其最后段（用消失时刻的新 OBB），
        // 记录飞行时长与是否穿入船体 OBB，区分"命中爆炸/近炸自毁/其它消亡"
        for (Integer id : new java.util.ArrayList<>(lastSeen.keySet())) {
            if (seenThisTick.contains(id)) {
                continue;
            }
            double[] last = lastSeen.remove(id);
            long startTick = firstSeenTick.remove(id);
            Vec3 lastPos = new Vec3(last[0], last[1], last[2]);
            Vec3 lastDelta = new Vec3(last[3], last[4], last[5]);
            Vec3 lastEnd = lastPos.add(lastDelta);
            long gameTick = server.overworld().getGameTime();
            StringBuilder summary = new StringBuilder();
            for (ServerLevel level : server.getAllLevels()) {
                AABB vanishBox = new AABB(lastPos, lastEnd).inflate(8.0);
                for (Entity vehicleEntity : org.ywzj.rvp.util.RVP_ServerVehicleIndex.mergeWithVehicleIndex(
                        level, vanishBox,
                        level.getEntitiesOfClass(AbstractVehicle.class, vanishBox, v -> v.isAlive()),
                        v -> v.isAlive())) {
                    if (!(vehicleEntity instanceof AbstractVehicle vehicle)) {
                        continue;
                    }
                    List<OBB> obbs = vehicle.getOBBs();
                    int hits = 0;
                    for (OBB obb : obbs) {
                        if (obb.clip(lastPos.toVector3f(), lastEnd.toVector3f()).isPresent()) {
                            hits++;
                        }
                    }
                    OBB mainCube = vehicle.getMainCubeOBB() == null ? null : vehicle.getMainCubeOBB().obb();
                    boolean mainHit = mainCube != null
                            && mainCube.clip(lastPos.toVector3f(), lastEnd.toVector3f()).isPresent();
                    summary.append(" vehicle=").append(vehicle.getId())
                            .append("(").append(vehicle.getVehicleId().getPath()).append(")")
                            .append(" obbs=").append(obbs.size())
                            .append(" 最后段命中=").append(hits)
                            .append(" 主块相交=").append(mainHit).append(';');
                }
            }
            log("VANISHED proj=" + id
                    + " 包络内ticks=" + (gameTick - startTick)
                    + " 总飞行tick=" + (lastTickCount.containsKey(id) ? lastTickCount.get(id) : -1)
                    + " lastSeg=" + fmt(lastPos) + "->" + fmt(lastEnd)
                    + " lastDelta=" + fmt(lastDelta)
                    + summary);
        }
    }

    /** 对单发投射物 × 单台载具执行与导弹一致的 OBB 扫描并记录结果。 */
    private static void watchScanVehicle(ServerLevel level, Projectile projectile, AbstractVehicle vehicle,
                                         Vec3 start, Vec3 end) {
        List<OBB> obbs = vehicle.getOBBs();
        OBB mainCube = vehicle.getMainCubeOBB() == null ? null : vehicle.getMainCubeOBB().obb();
        int hitCount = 0;
        int mainIdx = -1;
        StringBuilder crossed = new StringBuilder();
        for (int i = 0; i < obbs.size(); i++) {
            OBB obb = obbs.get(i);
            if (mainCube != null && (obb == mainCube || isSameObb(obb, mainCube))) {
                mainIdx = i;
            }
            var hit = obb.clip(start.toVector3f(), end.toVector3f()).orElse(null);
            if (hit != null) {
                hitCount++;
                if (crossed.length() > 0) {
                    crossed.append(',');
                }
                crossed.append(i).append("(t=").append(String.format("%.2f",
                        new Vec3(hit.x, hit.y, hit.z).distanceTo(start) / Math.max(start.distanceTo(end), 1.0E-6))).append(')');
            }
        }
        // 特征帧：实弹段与载具实体 AABB 相交（候选成立）但 OBB 扫描零命中 = 过穿现场；
        // 或主块相交但主块不在列表（盲区特征）
        boolean mainBlind = false;
        if (mainCube != null && mainIdx < 0) {
            var mainHit = mainCube.clip(start.toVector3f(), end.toVector3f()).orElse(null);
            if (mainHit != null) {
                mainBlind = true;
            }
        }
        if (hitCount == 0 || mainBlind) {
            log("WATCH tick=" + level.getGameTime()
                    + " proj=" + projectile.getId() + "(" + projectile.getType().toString() + ")"
                    + " flight=" + projectile.tickCount
                    + " seg=" + fmt(start) + "->" + fmt(end)
                    + " vehicle=" + vehicle.getId() + "(" + vehicle.getVehicleId().getPath() + ")"
                    + " obbs=" + obbs.size() + " mainInList=" + (mainIdx >= 0)
                    + " crossings=" + hitCount + (crossed.length() > 0 ? " [" + crossed + "]" : "")
                    + (mainBlind ? " ★主块相交但不在命中列表(盲区)" : hitCount == 0 ? " ★AABB相交但OBB零命中(过穿)" : ""));
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static boolean nearAnyPlayer(Entity entity) {
        for (net.minecraft.world.entity.player.Player player : entity.level().players()) {
            if (player.distanceToSqr(entity) < WATCH_RADIUS * WATCH_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** 值相等判定（中心与半尺寸），用于把主物理块与列表内条目对上（引用可能不同）。 */
    private static boolean isSameObb(OBB a, OBB b) {
        Vector3f ca = a.center();
        Vector3f cb = b.center();
        Vector3f ea = a.extents();
        Vector3f eb = b.extents();
        return Math.abs(ca.x - cb.x) < 1.0E-4 && Math.abs(ca.y - cb.y) < 1.0E-4 && Math.abs(ca.z - cb.z) < 1.0E-4
                && Math.abs(ea.x - eb.x) < 1.0E-4 && Math.abs(ea.y - eb.y) < 1.0E-4 && Math.abs(ea.z - eb.z) < 1.0E-4;
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    /** float32 量化显示：把 double 转成游戏内 OBB 实际持有的 float 值再打印。 */
    private static String f32(double v) {
        return Float.toString((float) v);
    }

    private static synchronized void log(String line) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            Files.write(LOG_PATH,
                    (LocalDateTime.now() + " " + line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
