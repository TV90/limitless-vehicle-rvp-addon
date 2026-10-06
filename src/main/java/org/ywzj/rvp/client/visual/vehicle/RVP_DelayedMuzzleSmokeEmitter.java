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
import org.ywzj.rvp.client.particle.RVP_WreckMuzzleSmokeParticle;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.VelocityParameter;
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
 * 每次重新解析实时炮口位置，每 1 tick 生成一枚白烟粒子。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DelayedMuzzleSmokeEmitter {

    /** 45 mm 口径边界，单位毫米；大于等于该值进入延迟白烟路径。 */
    static final float MIN_HEAVY_CALIBER = 45.0f;
    /** 延迟烟连续喷出的固定间隔，单位 tick。 */
    static final int EMIT_INTERVAL_TICKS = 1;
    /** 超过该距离后不再生成延迟烟，单位格。 */
    private static final double MAX_SPAWN_DISTANCE = 256.0;
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
        float particleSize = RVP_MuzzleSmokeEmitter.resolveParticleSize(profile.effectiveCaliber());
        for (MuzzleContext muzzleContext : muzzleContexts) {
            if (!tryReserveScheduleTick(vehicle, weaponUnit, muzzleContext.muzzleIndex(), fireTick)) {
                continue;
            }
            // 调用本体武器站上下文：保存武器站和炮口骨索引，延迟期间每次生成时重新解析实时位置。
            int delay = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.DELAY_MIN, Parameter.DELAY_MAX, random);
            int duration = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.EMIT_DURATION_MIN, Parameter.EMIT_DURATION_MAX, random);
            int lifetime = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.PARTICLE_LIFETIME_MIN, Parameter.PARTICLE_LIFETIME_MAX, random);
            long firstEmissionTick = fireTick + delay;
            enqueue(new PendingEmission(level, vehicle, weaponUnit, muzzleContext.bolt(),
                    particleSize, lifetime,
                    firstEmissionTick, firstEmissionTick + duration));
        }
    }

    /**
     * 在客户端 Tick 末尾推进延迟队列；每个条目每 1 tick 最多生成一枚粒子。
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
            if (pending.vehicle().isRemoved()
                    || !pending.weaponUnit().getBolts().contains(pending.bolt())) {
                // 调用本体实体/武器站状态：载具或炮口已不存在时停止继续追踪该延迟任务。
                iterator.remove();
                continue;
            }
            if (gameTime >= pending.endEmissionTick()) {
                iterator.remove();
                continue;
            }
            if (gameTime < pending.nextEmissionTick()) {
                continue;
            }
            AimContext currentAim = pending.resolveCurrentAimContext();
            if (currentAim == null) {
                iterator.remove();
                continue;
            }
            // 调用本项目炮口上下文工具：按当前载具/炮塔变换取得实时炮口位置。
            Vec3 realtimeMuzzle = RVP_AimContexts.muzzle(currentAim);
            if (isWithinClientRange(realtimeMuzzle)) {
                emitWhiteSmoke(level, pending, currentAim, realtimeMuzzle, level.random);
            }
            pending.advanceNextEmissionTick(gameTime + EMIT_INTERVAL_TICKS);
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

    /** 在实时炮口位置生成一枚延迟烟（2026-10-07 测试：改用殉燃炮口烟新样式，grey=1 染白）。 */
    private static void emitWhiteSmoke(ClientLevel level, PendingEmission pending,
                                       AimContext currentAim, Vec3 realtimeMuzzle,
                                       RandomSource random) {
        // 调用本项目速度计算：使用当前炮管方向，让持续喷出的烟随实时炮口姿态自然逸出。
        Vec3 velocity = resolveSmokeVelocity(currentAim, random);
        // 调用本项目殉燃炮口烟新样式（试验）：复用车顶殉燃静态贴图 + 上浮/摆动，
        // grey=1.0 染成白色替换旧 boom/smoke.png 白烟；尺寸/寿命沿用原延迟烟配置。
        RVP_WreckMuzzleSmokeParticle.spawn(
                Minecraft.getInstance().particleEngine,
                level,
                realtimeMuzzle,
                velocity,
                pending.particleSize(),
                0.9f,
                pending.particleLifetime(),
                random.nextInt(16),
                0.08d,
                0.05d,
                1.0f);
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

    /** 计算以世界竖直上浮为主、叠加少量炮管轴向和水平扰动的基础速度。 */
    private static Vec3 resolveSmokeVelocity(AimContext aim, RandomSource random) {
        // 调用本体向量工具：把炮管方向转换为少量轴向烟雾速度。
        Vec3 axis = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        // 调用本项目参数管理器：按指令设定的范围随机取得出膛方向初速度。
        double axialSpeed = RVP_DelayedMuzzleSmokeSettings.randomDoubleInclusive(
                VelocityParameter.AXIAL_SPEED_MIN, VelocityParameter.AXIAL_SPEED_MAX, random);
        double lateralX = (random.nextDouble() - 0.5) * 0.018;
        double lateralZ = (random.nextDouble() - 0.5) * 0.018;
        double upwardSpeed = 0.035 + random.nextDouble() * 0.025;
        return new Vec3(
                axis.x * axialSpeed + lateralX,
                Math.max(0.02, upwardSpeed + axis.y * axialSpeed),
                axis.z * axialSpeed + lateralZ);
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

    /** 大口径分类结果；口径用于复用现有烟团尺寸映射。 */
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

    /** 一次炮口延迟烟喷发任务的延迟参数快照和实时炮口追踪引用。 */
    private static final class PendingEmission {

        /** 任务所属的客户端世界，用于阻止跨世界生成。 */
        private final ClientLevel level;
        /** 任务关联的载具，用于检查载具是否仍存在。 */
        private final AbstractVehicle vehicle;
        /** 任务关联的武器站，用于每次生成时重新读取实时炮口变换。 */
        private final WeaponUnit weaponUnit;
        /** 任务关联的炮口骨索引对象，用于跟踪同一个实际炮口。 */
        private final Bolt bolt;
        /** 按大口径有效口径映射出的粒子尺寸。 */
        private final float particleSize;
        /** 任务中每枚白烟粒子的寿命，单位 tick。 */
        private final int particleLifetime;
        /** 下一枚烟允许生成的客户端游戏 tick。 */
        private long nextEmissionTick;
        /** 本次连续喷烟的结束 tick，采用右开区间。 */
        private final long endEmissionTick;

        private PendingEmission(ClientLevel level, AbstractVehicle vehicle,
                                WeaponUnit weaponUnit, Bolt bolt,
                                float particleSize, int particleLifetime,
                                long nextEmissionTick, long endEmissionTick) {
            this.level = level;
            this.vehicle = vehicle;
            this.weaponUnit = weaponUnit;
            this.bolt = bolt;
            this.particleSize = particleSize;
            this.particleLifetime = particleLifetime;
            this.nextEmissionTick = nextEmissionTick;
            this.endEmissionTick = endEmissionTick;
        }

        /** 返回所属客户端世界。 */
        private ClientLevel level() {
            return level;
        }

        /** 返回任务关联的载具。 */
        private AbstractVehicle vehicle() {
            return vehicle;
        }

        /** 返回任务关联的武器站。 */
        private WeaponUnit weaponUnit() {
            return weaponUnit;
        }

        /** 返回任务关联的炮口骨索引对象。 */
        private Bolt bolt() {
            return bolt;
        }

        /** 调用本体武器站接口取得当前炮塔/载具变换下的实时炮口上下文。 */
        private AimContext resolveCurrentAimContext() {
            return weaponUnit.aimContext(bolt);
        }

        /** 返回单枚粒子尺寸。 */
        private float particleSize() {
            return particleSize;
        }

        /** 返回单枚粒子寿命。 */
        private int particleLifetime() {
            return particleLifetime;
        }

        /** 返回下一次生成 tick。 */
        private long nextEmissionTick() {
            return nextEmissionTick;
        }

        /** 返回连续喷烟结束 tick。 */
        private long endEmissionTick() {
            return endEmissionTick;
        }

        /** 将下一次生成时间推进到当前生成时间之后的固定间隔。 */
        private void advanceNextEmissionTick(long nextTick) {
            this.nextEmissionTick = nextTick;
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
