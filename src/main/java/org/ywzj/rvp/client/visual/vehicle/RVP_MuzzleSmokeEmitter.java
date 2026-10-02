package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
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
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [RVP] 载具炮口烟雾发射器。
 *
 * <p>仅消费本体成功开火后的 {@link VehicleFireEvent.Post}，因此不会为被门控、无弹药或
 * 装填中的开火请求生成假烟。炮口位置由本体 {@link WeaponUnit#aimContext()} /
 * {@link WeaponUnit#aimContexts()} 提供，口径由类型化武器数据提供，不按武器资源 ID 分支。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_MuzzleSmokeEmitter {

    /** 炮口烟的最短寿命，单位 tick。 */
    static final int MIN_LIFETIME_TICKS = 15;
    /** 炮口烟的随机寿命范围长度，最终寿命为 15～25 tick。 */
    static final int LIFETIME_RANGE_TICKS = 11;
    /** 口径归一化基准，单位毫米；与 RVP 默认机枪口径保持一致。 */
    static final float REFERENCE_CALIBER = 7.62f;
    /** 7.62 mm 炮口烟对应的 MCHR 尺寸参数。 */
    static final float REFERENCE_PARTICLE_SIZE = 2.0f;
    /** 炮口烟口径尺寸倍率下限，防止异常小口径不可见。 */
    static final float MIN_CALIBER_SCALE = 0.8f;
    /** 炮口烟口径尺寸倍率上限，防止异常大口径刷屏。 */
    static final float MAX_CALIBER_SCALE = 2.5f;
    /** 炮口烟允许的最大口径，单位毫米；45 mm 及以上火炮不生成炮口烟。 */
    static final float MAX_MUZZLE_SMOKE_CALIBER = 45.0f;
    /** 超过该距离后不生成炮口烟，单位格；直接粒子加入不会自动做距离裁剪。 */
    static final double MAX_SPAWN_DISTANCE = 256.0;
    /** {@link #MAX_SPAWN_DISTANCE} 的平方，避免每个炮口调用平方根。 */
    private static final double MAX_SPAWN_DISTANCE_SQR = MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE;
    /**
     * 每辆载具、每个武器站、每个炮口最近一次生成炮口烟的客户端游戏 tick；
     * 载具使用弱引用，卸载后不会长期占用节流状态。
     */
    private static final Map<AbstractVehicle, Map<WeaponUnit, Map<Integer, Long>>> LAST_EMISSION_TICKS =
            new WeakHashMap<>();

    private RVP_MuzzleSmokeEmitter() {
    }

    /**
     * 本体成功开火后的客户端事件入口。
     *
     * @param event 本体已经确认成功的载具开火事件
     */
    @SubscribeEvent
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 调用本体事件接口确认当前事件来自客户端，服务端不加载或生成客户端粒子。
        if (!event.isClientSide()) {
            return;
        }

        // 调用本体事件接口取得开火载具，后续用于距离裁剪、世界与随机源获取。
        AbstractVehicle vehicle = event.getVehicle();
        if (!(vehicle.level() instanceof ClientLevel level)) {
            return;
        }
        if (!isWithinClientRange(vehicle)) {
            return;
        }

        // 调用本项目代理解包器：把 VehicleWeaponAgent / VehicleMultiWeapons 解析为实际弹种。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        float caliber = resolveCaliber(weapon);
        if (weapon == null || !supportsMuzzleSmoke(caliber)) {
            return;
        }

        // 调用本体武器入口取得真实发射挂架，炮口位置必须来自实际武器而不是代理包装器。
        WeaponUnit weaponUnit = weapon.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }

        List<MuzzleContext> muzzleContexts = resolveFiringMuzzleContexts(weaponUnit);
        if (muzzleContexts.isEmpty()) {
            return;
        }

        RandomSource random = level.random;
        float particleSize = resolveParticleSize(caliber);
        // 调用本体客户端世界时钟：每个炮口独立使用同一 tick，保证不同炮口不互相限流。
        long gameTime = level.getGameTime();
        for (MuzzleContext muzzleContext : muzzleContexts) {
            if (!tryReserveMuzzleTick(vehicle, weaponUnit, muzzleContext.muzzleIndex, gameTime)) {
                continue;
            }
            AimContext aim = muzzleContext.aimContext;
            // 调用本项目炮口上下文工具：读取 AimContext.from，并保留炮管末端回退语义。
            Vec3 muzzle = RVP_AimContexts.muzzle(aim);
            Vec3 velocity = resolveSmokeVelocity(aim, random);
            int lifetime = MIN_LIFETIME_TICKS + random.nextInt(LIFETIME_RANGE_TICKS);
            // 调用本项目炮口烟粒子工厂：复用 smoke.png，并将寿命、尺寸和上浮速度传入粒子。
            RVP_MchrSmokeParticle particle = RVP_MchrSmokeParticle.ofMuzzle(
                    level, muzzle.x, muzzle.y, muzzle.z,
                    velocity.x, velocity.y, velocity.z,
                    particleSize, lifetime);
            // 调用原版客户端粒子引擎：粒子完全在客户端生命周期内运行，不产生服务端实体。
            Minecraft.getInstance().particleEngine.add(particle);
        }
    }

    /** 判断载具是否在当前客户端的烟雾生成距离内。 */
    private static boolean isWithinClientRange(AbstractVehicle vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            cameraEntity = minecraft.player;
        }
        if (cameraEntity == null) {
            return false;
        }
        // 调用原版实体距离接口：远距离直接跳过，避免 direct particleEngine.add 绕过原版距离裁剪。
        return vehicle.distanceToSqr(cameraEntity) <= MAX_SPAWN_DISTANCE_SQR;
    }

    /**
     * 根据类型化武器数据解析口径。
     *
     * <p>RVP 只接受 MACHINEGUN 类型作为炮口烟来源；本体炮则要求数据对象是
     * VehicleCannonWeaponData，避免普通导弹/火箭基础数据中的 caliber 被误判为炮口。</p>
     */
    private static float resolveCaliber(AbstractVehicleWeapon<?> weapon) {
        if (weapon == null) {
            return Float.NaN;
        }
        // 调用本体武器数据接口：读取当前实际弹种的数据对象，而不是读取资源 ID。
        Object rawData = weapon.getData();
        if (rawData instanceof RVP_WeaponData rvpData) {
            if (rvpData.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
                return Float.NaN;
            }
            // 调用本项目 RVP 数据访问器：读取恢复为弹体口径语义的 effects_data.caliber，
            // 非法值按统一默认值处理；45 mm 以上由延迟白烟路径负责，避免两条路径重复发烟。
            return sanitizeCaliber(rvpData.getEffectsData().getCaliber());
        }
        if (rawData instanceof VehicleCannonWeaponData cannonData) {
            // 调用本体炮数据访问器：兼容未迁移到 RVP 类型的本体火炮。
            return sanitizeCaliber(cannonData.getCaliber());
        }
        return Float.NaN;
    }

    /** 清洗口径，保证尺寸公式不会接收 NaN、无穷或非正数。 */
    private static float sanitizeCaliber(float caliber) {
        return Float.isFinite(caliber) && caliber > 0.0f ? caliber : REFERENCE_CALIBER;
    }

    /**
     * 口径到粒子尺寸的次线性映射；平方根保留口径差异，同时避免大炮烟团线性膨胀过度。
     */
    static float resolveParticleSize(float caliber) {
        float safeCaliber = sanitizeCaliber(caliber);
        float caliberScale = Mth.clamp(
                (float) Math.sqrt(safeCaliber / REFERENCE_CALIBER),
                MIN_CALIBER_SCALE, MAX_CALIBER_SCALE);
        return REFERENCE_PARTICLE_SIZE * caliberScale;
    }

    /** 判断口径是否满足炮口烟规则；45 mm 及以上口径明确排除。 */
    static boolean supportsMuzzleSmoke(float caliber) {
        return Float.isFinite(caliber) && caliber > 0.0f && caliber < MAX_MUZZLE_SMOKE_CALIBER;
    }

    /** 按武器站轮射/齐射模式取得本次实际开火的炮口上下文及其稳定索引。 */
    private static List<MuzzleContext> resolveFiringMuzzleContexts(WeaponUnit weaponUnit) {
        // 调用本体武器站接口：读取当前轮射或齐射模式，避免轮射时错误冒出全部炮口烟。
        WeaponUnitData.FiringMode firingMode = weaponUnit.getFiringMode();
        if (firingMode == null) {
            return List.of();
        }
        return switch (firingMode) {
            case RIPPLE -> {
                // 调用本体武器站接口：当前炮闩索引用于区分同一武器站的不同炮口。
                List<?> bolts = weaponUnit.getBolts();
                int muzzleIndex = bolts.indexOf(weaponUnit.getCurrentBolt());
                if (muzzleIndex < 0) {
                    muzzleIndex = 0;
                }
                // 调用本体单炮口瞄准接口：轮射只使用当前炮口。
                AimContext aim = weaponUnit.aimContext();
                yield aim == null ? List.of() : List.of(new MuzzleContext(muzzleIndex, aim));
            }
            case SALVO, FULL_SALVO -> {
                // 调用本体多炮口瞄准接口：齐射按炮口顺序保留所有有效炮口。
                List<AimContext> aimContexts = weaponUnit.aimContexts();
                if (aimContexts == null || aimContexts.isEmpty()) {
                    yield List.of();
                }
                List<MuzzleContext> muzzleContexts = new ArrayList<>(aimContexts.size());
                for (int i = 0; i < aimContexts.size(); i++) {
                    AimContext aim = aimContexts.get(i);
                    if (aim != null) {
                        muzzleContexts.add(new MuzzleContext(i, aim));
                    }
                }
                yield muzzleContexts;
            }
        };
    }

    /** 为指定载具的指定炮口预留当前客户端游戏 tick 的唯一炮口烟生成资格。 */
    private static boolean tryReserveMuzzleTick(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                int muzzleIndex, long gameTime) {
        synchronized (LAST_EMISSION_TICKS) {
            Map<WeaponUnit, Map<Integer, Long>> weaponTicks = LAST_EMISSION_TICKS.computeIfAbsent(
                    vehicle, ignored -> new IdentityHashMap<>());
            Map<Integer, Long> muzzleTicks = weaponTicks.computeIfAbsent(weaponUnit, ignored -> new HashMap<>());
            Long lastEmissionTick = muzzleTicks.get(muzzleIndex);
            if (lastEmissionTick != null && lastEmissionTick == gameTime) {
                return false;
            }
            muzzleTicks.put(muzzleIndex, gameTime);
            return true;
        }
    }

    /** 一次事件中待处理的炮口上下文及其武器站内稳定索引。 */
    private static final class MuzzleContext {

        /** 武器站炮闩列表中的炮口索引。 */
        private final int muzzleIndex;
        /** 本体计算出的炮口位置与方向上下文。 */
        private final AimContext aimContext;

        private MuzzleContext(int muzzleIndex, AimContext aimContext) {
            this.muzzleIndex = muzzleIndex;
            this.aimContext = aimContext;
        }
    }

    /** 计算以世界竖直上浮为主、叠加少量炮管轴向和水平扰动的烟雾速度。 */
    private static Vec3 resolveSmokeVelocity(AimContext aim, RandomSource random) {
        // 调用本体向量工具：把炮管姿态转换为少量轴向烟雾速度，保证烟从炮口自然逸出。
        Vec3 axis = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        double axialSpeed = 0.012 + random.nextDouble() * 0.010;
        double lateralX = (random.nextDouble() - 0.5) * 0.018;
        double lateralZ = (random.nextDouble() - 0.5) * 0.018;
        double upwardSpeed = 0.035 + random.nextDouble() * 0.025;
        return new Vec3(
                axis.x * axialSpeed + lateralX,
                Math.max(0.02, upwardSpeed + axis.y * axialSpeed),
                axis.z * axialSpeed + lateralZ);
    }
}
