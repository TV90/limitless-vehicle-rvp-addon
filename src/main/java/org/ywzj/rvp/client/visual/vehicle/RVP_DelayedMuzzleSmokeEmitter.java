package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.RVP_WreckMuzzleSmokeSelfRenderer;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Snapshot;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.VehicleCannonWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [RVP] 独立的大口径延迟炮口烟管理器。
 *
 * <p>本类不修改现有 {@link RVP_MuzzleSmokeEmitter} 的小口径即时烟路径。它只消费本体已经
 * 成功的 {@link VehicleFireEvent.Post}，在开火后排队一段时间，再按武器站和炮口骨索引
 * 每次重新解析实时炮口位置，每刻按参数快照决定烟量与运动。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DelayedMuzzleSmokeEmitter {

    /** 45 mm 口径边界，单位毫米；大于等于该值进入延迟白烟路径。 */
    static final float MIN_HEAVY_CALIBER = 45.0f;
    /** 延迟烟连续喷出的固定间隔，单位 tick。 */
    static final int EMIT_INTERVAL_TICKS = 1;
    /** 超过该距离后不再生成延迟烟，单位格。 */
    private static final double MAX_SPAWN_DISTANCE = RVP_DelayedMuzzleSmokeLogic.MAX_DISTANCE;
    /** {@link #MAX_SPAWN_DISTANCE} 的平方，避免重复开平方。 */
    private static final double MAX_SPAWN_DISTANCE_SQR = MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE;
    /** 待处理的延迟烟队列；所有条目都只在客户端线程访问。 */
    private static final ArrayDeque<PendingEmission> PENDING_EMISSIONS = new ArrayDeque<>();
    /** 队列安全上限，防止大量齐射或异常事件无限积累客户端状态。 */
    private static final int MAX_PENDING_EMISSIONS = 512;
    /** 每辆载具、每个武器站、每个炮口最近一次排队的客户端游戏 tick。 */
    private static final Map<AbstractVehicle, Map<WeaponUnit, Map<Integer, Long>>> LAST_SCHEDULE_TICKS =
            new WeakHashMap<>();

    private RVP_DelayedMuzzleSmokeEmitter() {
    }

    /**
     * 接收本体确认成功的开火事件，并为符合大口径条件的炮口建立延迟发射条目。
     *
     * @param event 本体成功开火事件
     */
    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 调用本体事件接口确认只在客户端创建视觉粒子，避免服务端加载客户端类型。
        if (!event.isClientSide()) {
            return;
        }
        AbstractVehicle vehicle = event.getVehicle();
        if (!(vehicle.level() instanceof ClientLevel level)
                || !isWithinClientRange(vehicle.position())) {
            return;
        }

        // 调用本项目代理解包器：从本体包装武器中取出实际类型化弹种。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        HeavySmokeProfile profile = resolveHeavySmokeProfile(weapon);
        if (profile == null || weapon.getWeaponUnit() == null) {
            return;
        }

        WeaponUnit weaponUnit = weapon.getWeaponUnit();
        List<MuzzleContext> muzzleContexts = resolveFiringMuzzleContexts(weaponUnit);
        if (muzzleContexts.isEmpty()) {
            return;
        }

        RandomSource random = level.random;
        long fireTick = level.getGameTime();
        // 调用完整参数快照：本次开火的待发烟和已生成烟不受之后调参影响。
        Snapshot settings = RVP_DelayedMuzzleSmokeSettings.snapshot();
        for (MuzzleContext muzzleContext : muzzleContexts) {
            if (!tryReserveScheduleTick(vehicle, weaponUnit, muzzleContext.muzzleIndex(), fireTick)) {
                continue;
            }
            // 调用本体武器站上下文：保存武器站和炮口骨索引，延迟期间每次生成时重新解析实时位置。
            int delay = settings.randomInt(Parameter.DELAY_MIN, Parameter.DELAY_MAX, random);
            int duration = settings.randomInt(Parameter.EMIT_DURATION_MIN, Parameter.EMIT_DURATION_MAX, random);
            // 调用本体炮口上下文作为运动采样起点；延迟期间继续逐刻采样，包含炮塔转动速度。
            AimContext initialAim = weaponUnit.aimContext(muzzleContext.bolt());
            if (initialAim == null) { continue; }
            Vec3 initialMuzzle = RVP_AimContexts.muzzle(initialAim);
            long firstEmissionTick = fireTick + delay;
            enqueue(new PendingEmission(level, vehicle, weaponUnit, muzzleContext.bolt(),
                    profile.effectiveCaliber(), settings, firstEmissionTick, firstEmissionTick + duration,
                    initialMuzzle, fireTick, null));
        }
    }

    /**
     * 在客户端 Tick 末尾推进延迟队列，按每发参数快照生成白烟。
     *
     * @param event 客户端 Tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clearPendingEmissions();
            return;
        }

        long gameTime = level.getGameTime();
        Iterator<PendingEmission> iterator = PENDING_EMISSIONS.iterator();
        while (iterator.hasNext()) {
            PendingEmission pending = iterator.next();
            if (pending.level() != level) {
                // 调用本项目队列清理语义：世界切换后丢弃旧世界武器站引用，避免跨维度冒烟。
                iterator.remove();
                continue;
            }
            if (pending.preview == null && (pending.vehicle.isRemoved()
                    || !pending.weaponUnit.getBolts().contains(pending.bolt))) {
                // 调用本体状态检查；预览没有载具，真实任务的载具或炮口消失就停止生成。
                iterator.remove();
                continue;
            }
            if (gameTime >= pending.endEmissionTick) {
                iterator.remove();
                continue;
            }
            Vec3 realtimeMuzzle;
            Vec3 axis;
            if (pending.preview != null) {
                realtimeMuzzle = pending.preview.position();
                axis = pending.preview.axis();
            } else {
                // 调用本体实时瞄准上下文，只让新烟跟随炮口；旧烟仍留在世界坐标中。
                AimContext currentAim = pending.resolveCurrentAimContext();
                if (currentAim == null) {
                    iterator.remove();
                    continue;
                }
                realtimeMuzzle = RVP_AimContexts.muzzle(currentAim);
                axis = VectorUtil.rotToVec(currentAim.direction.x, currentAim.direction.y).normalize();
            }
            // 延迟等待期间也采样位置，避免第一次喷烟继承整个等待期的平均速度。
            Vec3 muzzleVelocity = pending.sampleMuzzleVelocity(realtimeMuzzle, gameTime);
            if (gameTime < pending.nextEmissionTick) { continue; }
            double distance = clientDistance(realtimeMuzzle);
            if (distance <= MAX_SPAWN_DISTANCE) {
                // 调用正式生成计算，预览与真实开火共用节奏、距离减量和粒子预算。
                emitWhiteSmoke(level, pending, realtimeMuzzle, axis, muzzleVelocity, distance, gameTime, level.random);
            }
            pending.nextEmissionTick = gameTime + EMIT_INTERVAL_TICKS;
        }
    }

    /** 添加待处理条目；超出预算时丢弃最早条目，避免客户端队列无限增长。 */
    private static void enqueue(PendingEmission pending) {
        while (PENDING_EMISSIONS.size() >= MAX_PENDING_EMISSIONS) {
            PENDING_EMISSIONS.removeFirst();
        }
        PENDING_EMISSIONS.addLast(pending);
    }

    /** 清理所有待发射的延迟烟。 */
    static void clearPendingEmissions() {
        PENDING_EMISSIONS.clear();
        synchronized (LAST_SCHEDULE_TICKS) {
            LAST_SCHEDULE_TICKS.clear();
        }
    }

    /** 用同一份快照生成白烟；不再把寿命、初速、尺寸和浓度写死在生成器里。 */
    private static void emitWhiteSmoke(ClientLevel level, PendingEmission pending, Vec3 muzzle,
                                       Vec3 axis, Vec3 muzzleVelocity, double distance, long gameTime,
                                       RandomSource random) {
        // 调用实际喷烟包络：起烟渐强、后段泄劲与收尾保留比例同时作用于烟量和喷出状态。
        double strength = RVP_DelayedMuzzleSmokeLogic.emissionStrength(pending.settings,
                (int) (gameTime - pending.startEmissionTick),
                (int) (pending.endEmissionTick - pending.startEmissionTick));
        int count = pending.particleCounter.next(pending.settings, strength, distance);
        for (int index = 0; index < count; index++) {
            // 调用纯计算入口，确保面板设置确实进入正式出生位置、初速、大小、浓度和寿命。
            RVP_DelayedMuzzleSmokeLogic.Spawn spawn = RVP_DelayedMuzzleSmokeLogic.sample(pending.settings,
                    pending.caliber, muzzle, axis, muzzleVelocity, index, count, strength, random);
            // 调用既有自绘烟管线的新白烟入口；殉燃烟继续使用原入口，不读取F8白烟参数。
            if (!RVP_WreckMuzzleSmokeSelfRenderer.spawnDelayed(level, spawn, pending.settings, pending.sourceKey())) {
                break;
            }
        }
    }

    /** 本地静止炮口预览；共用真实延时队列，不生成弹体、不发送网络包。 */
    public static void previewAtPlayer(float caliber) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            throw new IllegalStateException("需要先进入世界才能预览白烟。");
        }
        if (!supportsDelayedMuzzleSmoke(caliber)) {
            throw new IllegalArgumentException("白烟预览口径必须至少为45毫米。");
        }
        // 调用当前视线，只固定预览源；生成后的烟继续使用真实世界坐标与正式运动逻辑。
        Vec3 axis = minecraft.player.getLookAngle().normalize();
        Vec3 position = minecraft.player.getEyePosition().add(axis.scale(4)).add(0, -0.5, 0);
        Snapshot settings = RVP_DelayedMuzzleSmokeSettings.snapshot();
        long now = level.getGameTime();
        int delay = settings.randomInt(Parameter.DELAY_MIN, Parameter.DELAY_MAX, level.random);
        int duration = settings.randomInt(Parameter.EMIT_DURATION_MIN, Parameter.EMIT_DURATION_MAX, level.random);
        enqueue(new PendingEmission(level, null, null, null, caliber, settings, now + delay,
                now + delay + duration, position, now, new PreviewSource(position, axis)));
    }

    /** 返回相机到烟源距离；没有相机时使用无穷大，禁止无效生成。 */
    private static double clientDistance(Vec3 position) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) { cameraEntity = minecraft.player; }
        return cameraEntity == null ? Double.POSITIVE_INFINITY : cameraEntity.position().distanceTo(position);
    }

    /** 判断坐标是否在当前客户端烟雾生成范围内。 */
    private static boolean isWithinClientRange(Vec3 position) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            cameraEntity = minecraft.player;
        }
        return cameraEntity != null
                && cameraEntity.position().distanceToSqr(position) <= MAX_SPAWN_DISTANCE_SQR;
    }

    /** 只为 RVP 机炮或本体炮解析大口径延迟白烟的类型化资格和有效口径。 */
    private static HeavySmokeProfile resolveHeavySmokeProfile(AbstractVehicleWeapon<?> weapon) {
        if (weapon == null) {
            return null;
        }
        // 调用本体武器数据接口：按类型化数据判定，不读取武器资源 ID。
        Object rawData = weapon.getData();
        if (rawData instanceof RVP_WeaponData rvpData) {
            if (rvpData.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
                return null;
            }
            // 调用本项目 RVP 数据访问器：effects_data.caliber 已恢复为弹体口径，
            // 只有其达到 45 mm 才进入延迟白烟路径；不再用弹药类型绕过数值阈值。
            float caliber = rvpData.getEffectsData().getCaliber();
            if (supportsDelayedMuzzleSmoke(caliber)) {
                return new HeavySmokeProfile(Math.max(caliber, MIN_HEAVY_CALIBER));
            }
            return null;
        }
        if (rawData instanceof VehicleCannonWeaponData cannonData) {
            Float caliber = cannonData.getCaliber();
            if (caliber != null && supportsDelayedMuzzleSmoke(caliber)) {
                return new HeavySmokeProfile(caliber);
            }
        }
        return null;
    }

    /** 判断口径是否达到延迟白烟的 45 mm 边界，供分类逻辑和定向测试复用。 */
    static boolean supportsDelayedMuzzleSmoke(float caliber) {
        return Float.isFinite(caliber) && caliber >= MIN_HEAVY_CALIBER;
    }

    /** 按本体武器站的轮射/齐射模式解析本次真正开火的炮口。 */
    private static List<MuzzleContext> resolveFiringMuzzleContexts(WeaponUnit weaponUnit) {
        // 调用本体武器站接口：按当前 FiringMode 选择单炮口或全部齐射炮口。
        WeaponUnitData.FiringMode firingMode = weaponUnit.getFiringMode();
        if (firingMode == null) {
            return List.of();
        }
        return switch (firingMode) {
            case RIPPLE -> {
                List<Bolt> bolts = weaponUnit.getBolts();
                if (bolts == null || bolts.isEmpty()) {
                    yield List.of();
                }
                Bolt bolt = weaponUnit.getCurrentBolt();
                if (bolt == null) {
                    yield List.of();
                }
                int muzzleIndex = bolts.indexOf(bolt);
                if (muzzleIndex < 0) {
                    muzzleIndex = 0;
                }
                // 调用本体单炮口瞄准接口：轮射只排队当前炮口的延迟烟。
                AimContext aim = weaponUnit.aimContext(bolt);
                yield aim == null ? List.of() : List.of(new MuzzleContext(muzzleIndex, bolt));
            }
            case SALVO, FULL_SALVO -> {
                // 调用本体多炮口瞄准接口：齐射为每个有效炮口独立建立延迟条目。
                List<AimContext> aimContexts = weaponUnit.aimContexts();
                List<Bolt> bolts = weaponUnit.getBolts();
                if (aimContexts == null || aimContexts.isEmpty()) {
                    yield List.of();
                }
                List<MuzzleContext> muzzleContexts = new ArrayList<>(aimContexts.size());
                for (int i = 0; i < aimContexts.size(); i++) {
                    AimContext aim = aimContexts.get(i);
                    if (aim != null && i < bolts.size()) {
                        muzzleContexts.add(new MuzzleContext(i, bolts.get(i)));
                    }
                }
                yield muzzleContexts;
            }
        };
    }

    /** 为指定炮口保留一个客户端游戏 tick 的唯一排队资格。 */
    private static boolean tryReserveScheduleTick(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                  int muzzleIndex, long gameTime) {
        synchronized (LAST_SCHEDULE_TICKS) {
            Map<WeaponUnit, Map<Integer, Long>> weaponTicks = LAST_SCHEDULE_TICKS.computeIfAbsent(
                    vehicle, ignored -> new IdentityHashMap<>());
            Map<Integer, Long> muzzleTicks = weaponTicks.computeIfAbsent(weaponUnit,
                    ignored -> new HashMap<>());
            Long lastScheduleTick = muzzleTicks.get(muzzleIndex);
            if (lastScheduleTick != null && lastScheduleTick == gameTime) {
                return false;
            }
            muzzleTicks.put(muzzleIndex, gameTime);
            return true;
        }
    }

    /** 大口径分类结果；真实口径供可调的烟团及出生范围比例使用。 */
    private static final class HeavySmokeProfile {

        /** 大口径烟团尺寸计算使用的有效口径，单位毫米。 */
        private final float effectiveCaliber;

        private HeavySmokeProfile(float effectiveCaliber) {
            this.effectiveCaliber = effectiveCaliber;
        }

        /** 返回烟团尺寸计算使用的有效口径。 */
        private float effectiveCaliber() {
            return effectiveCaliber;
        }
    }

    /** 预览使用固定世界烟源，不依赖实际载具和武器。 */
    private record PreviewSource(
            /** 固定世界炮口位置。 */ Vec3 position,
            /** 固定出膛方向单位向量。 */ Vec3 axis) { }

    /** 一次开火的参数快照、实时炮口引用和小数烟量累积。 */
    private static final class PendingEmission {
        /** 任务所属世界，换世界后丢弃。 */
        private final ClientLevel level;
        /** 真实载具；静止预览为 null。 */
        private final AbstractVehicle vehicle;
        /** 真实武器站；静止预览为 null。 */
        private final WeaponUnit weaponUnit;
        /** 同一实际炮口的身份，同时作为连续开火粒子预算键；预览为 null。 */
        private final Bolt bolt;
        /** 武器的真实口径，单位毫米。 */
        private final float caliber;
        /** 本次开火完整参数，已生成和待生成烟均保持此快照。 */
        private final Snapshot settings;
        /** 第一刻喷烟时间，用于计算喷烟强弱。 */
        private final long startEmissionTick;
        /** 喷烟结束时间，右开区间。 */
        private final long endEmissionTick;
        /** 下一次允许生成的世界刻。 */
        private long nextEmissionTick;
        /** 小数烟量累积器，防止低烟量或远处效果完全消失。 */
        private final RVP_DelayedMuzzleSmokeLogic.EmissionCounter particleCounter =
                new RVP_DelayedMuzzleSmokeLogic.EmissionCounter();
        /** 上次采样的炮口世界位置。 */
        private Vec3 lastMuzzlePosition;
        /** 上次炮口采样对应的世界刻。 */
        private long lastMuzzleTick;
        /** 仅预览时存在的固定炮口，真实任务为 null。 */
        private final PreviewSource preview;

        private PendingEmission(ClientLevel level, AbstractVehicle vehicle, WeaponUnit weaponUnit, Bolt bolt,
                                float caliber, Snapshot settings, long start, long end,
                                Vec3 initialMuzzle, long initialTick, PreviewSource preview) {
            this.level = level;
            this.vehicle = vehicle;
            this.weaponUnit = weaponUnit;
            this.bolt = bolt;
            this.caliber = caliber;
            this.settings = settings;
            this.startEmissionTick = start;
            this.nextEmissionTick = start;
            this.endEmissionTick = end;
            this.lastMuzzlePosition = initialMuzzle;
            this.lastMuzzleTick = initialTick;
            this.preview = preview;
        }

        private ClientLevel level() { return level; }

        /** 调用稳定器和本体上下文，采样当刻真实炮口变换。 */
        private AimContext resolveCurrentAimContext() {
            // 调用本项目稳定器约束，避免本刻尚未限位的父炮塔姿态被白烟提前缓存。
            org.ywzj.rvp.event.RVP_TurretTraverseHandler.constrain(weaponUnit);
            return weaponUnit.aimContext(bolt);
        }

        /** 同一炮口的连续开火共用预算；不同预览使用各自的预算。 */
        private Object sourceKey() { return bolt == null ? this : bolt; }

        /** 用逐刻世界位置差同时包含载具平移与炮塔转动，瞬移不继承异常巨大速度。 */
        private Vec3 sampleMuzzleVelocity(Vec3 muzzle, long gameTime) {
            if (gameTime <= lastMuzzleTick) { return Vec3.ZERO; }
            Vec3 velocity = muzzle.subtract(lastMuzzlePosition).scale(1.0 / (gameTime - lastMuzzleTick));
            lastMuzzlePosition = muzzle;
            lastMuzzleTick = gameTime;
            // 每刻超过16格按瞬移处理，避免把传送变成数百格长的白烟拖尾。
            return velocity.lengthSqr() > 256 ? Vec3.ZERO : velocity;
        }

    }

    /** 一次事件中待处理的炮口上下文及其武器站内稳定索引。 */
    private static final class MuzzleContext {

        /** 武器站炮闩列表中的炮口索引，用于逐炮口限频。 */
        private final int muzzleIndex;
        /** 该任务对应的本体炮口骨索引对象。 */
        private final Bolt bolt;

        private MuzzleContext(int muzzleIndex, Bolt bolt) {
            this.muzzleIndex = muzzleIndex;
            this.bolt = bolt;
        }

        /** 返回炮口索引。 */
        private int muzzleIndex() {
            return muzzleIndex;
        }

        /** 返回炮口骨索引对象。 */
        private Bolt bolt() {
            return bolt;
        }
    }
}
