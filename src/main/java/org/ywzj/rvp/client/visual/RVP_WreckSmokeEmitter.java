package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.RVP_WreckSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_WreckSmokeParticle.SmokeLayer;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

/** 客户端逐载具发射 RVP 击毁烟；与本体 tickParticle 的屏蔽注入点相互独立。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckSmokeEmitter {

    /** 两次发射的间隔，单位 tick；每辆残骸在 60 秒内持续生成烟团。 */
    private static final int EMIT_PERIOD_TICKS = 5;
    /** 客户端额外发射距离阈值，单位格；实际仍受载具实体追踪和客户端渲染距离限制。 */
    private static final double EMIT_RANGE = 1536.0;
    /** 单辆载具每轮最多生成的近处烟团数量，控制大型载具的粒子预算。 */
    private static final int MAX_SMOKE_PER_ROUND = 4;
    /** 指令预览持续时间，单位 tick；客户端世界切换时会提前停止。 */
    private static final int PREVIEW_DURATION_TICKS = 1200;
    /** 指令预览模拟的载具主结构水平半径，单位格。 */
    private static final double PREVIEW_RADIUS = 2.0;
    /** 指令预览模拟的载具主结构高度，单位格。 */
    private static final double PREVIEW_HEIGHT = 2.0;
    /** 当前指令预览的世界坐标；null 表示没有预览。 */
    private static Vec3 previewCenter;
    /** 发起预览的客户端世界，避免切换世界后继续生成烟。 */
    private static ClientLevel previewLevel;
    /** 指令预览在客户端世界时间中的结束 tick。 */
    private static long previewEndGameTime;
    /** 上次预览发射的客户端世界 tick，防止暂停时重复发射。 */
    private static long previewLastEmissionGameTime = Long.MIN_VALUE;

    private RVP_WreckSmokeEmitter() {
    }

    /** 在指定客户端位置开启 60 秒长程烟预览，无须生成或击毁真实载具。 */
    public static void startPreview(ClientLevel level, Vec3 center) {
        previewLevel = level;
        previewCenter = center;
        previewEndGameTime = level.getGameTime() + PREVIEW_DURATION_TICKS;
        previewLastEmissionGameTime = Long.MIN_VALUE;
    }

    /** 停止预览并释放对客户端世界的引用。 */
    public static void stopPreview() {
        previewCenter = null;
        previewLevel = null;
        previewEndGameTime = 0;
        previewLastEmissionGameTime = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            // 调用本项目预览清理入口：离开世界后不再保留旧客户端世界对象。
            stopPreview();
            return;
        }
        // 调用本项目预览发射入口：准星位置的临时烟柱与真实残骸共用长程生成逻辑。
        emitPreviewSmoke(minecraft, level);
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || entity instanceof VehiclePart
                    || !vehicle.isDestroyed()
                    || vehicle.isRemoved()
                    || minecraft.player.distanceToSqr(vehicle) > EMIT_RANGE * EMIT_RANGE) {
                continue;
            }
            boolean emitNear = vehicle.tickCount % EMIT_PERIOD_TICKS == 0;
            boolean emitLong = vehicle.tickCount % Parameter.EMIT_PERIOD.intValue() == 0;
            if (!emitNear && !emitLong) {
                continue;
            }
            // 调用本项目残骸烟生成器：只覆盖载具本体，脱落 VehiclePart 继续使用本体特效。
            emitVehicleSmoke(minecraft, level, vehicle, emitNear, emitLong);
        }
    }

    /** 按主 OBB 生成到期的近处烟和长程烟，两者的发射周期相互独立。 */
    private static void emitVehicleSmoke(Minecraft minecraft, ClientLevel level, AbstractVehicle vehicle,
                                         boolean emitNear, boolean emitLong) {
        // 调用本体 OBB 公共入口：用载具主结构的真实中心和尺寸生成烟团，适配所有车型。
        VehicleCubeOBB mainCube = vehicle.getMainCubeOBB();
        AABB bounds = vehicle.getBoundingBox();
        Vec3 center = mainCube != null ? new Vec3(mainCube.obb().center()) : bounds.getCenter();
        double width = mainCube != null ? mainCube.width : bounds.getXsize();
        double depth = mainCube != null ? mainCube.depth : bounds.getZsize();
        double height = mainCube != null ? mainCube.height : bounds.getYsize();
        double radius = Math.max(0.5, Math.min(width, depth) * 0.5);
        RandomSource random = level.random;
        if (emitNear) {
            int count = Mth.clamp(Mth.ceil(Math.max(width, depth) / 2.5), 2, MAX_SMOKE_PER_ROUND);
            for (int i = 0; i < count; i++) {
                // 调用本项目短程烟发射函数：保留原有近处浓烟的数量和 42～65 tick 寿命。
                emitNearSmokeParticle(minecraft, level, random, center, radius, height);
            }
        }
        if (emitLong) {
            // 调用本项目相位计算入口：使用当前调试周期决定单点的水平错相位置。
            double horizontalPhase = horizontalPhase(vehicle.tickCount);
            // 调用本项目长程烟发射函数：每轮只有一处生成点，X/Z 位置按配置相位错开。
            emitLongSmokePair(minecraft, level, random, center, radius, height, horizontalPhase);
        }
    }

    /** 按可调周期计算水平位置相位，单位弧度。 */
    private static double horizontalPhase(long tick) {
        int period = Parameter.PHASE_PERIOD.intValue();
        return Mth.TWO_PI * (tick % period) / period;
    }

    /** 在准星位置按真实长程烟周期发射预览，并在超时或切换世界时清理状态。 */
    private static void emitPreviewSmoke(Minecraft minecraft, ClientLevel level) {
        if (previewCenter == null) {
            return;
        }
        long gameTime = level.getGameTime();
        if (previewLevel != level || gameTime >= previewEndGameTime) {
            // 调用本项目预览清理入口：预览到期或世界切换时释放状态。
            stopPreview();
            return;
        }
        if (gameTime == previewLastEmissionGameTime || gameTime % Parameter.EMIT_PERIOD.intValue() != 0) {
            return;
        }
        previewLastEmissionGameTime = gameTime;
        // 调用本项目相位与发射入口：预览和真实残骸使用同一套当前调试参数。
        emitLongSmokePair(minecraft, level, level.random, previewCenter, PREVIEW_RADIUS, PREVIEW_HEIGHT,
                horizontalPhase(gameTime));
    }

    /** 在载具主结构附近随机取样，保留近处浓烟原有的尺寸、寿命和分布。 */
    private static void emitNearSmokeParticle(Minecraft minecraft, ClientLevel level, RandomSource random,
                                              Vec3 center, double radius, double height) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double offset = Math.sqrt(random.nextDouble()) * radius * 0.8;
        double x = center.x + Math.cos(angle) * offset;
        double y = center.y + Math.max(0.1, height * 0.22) + random.nextDouble() * 0.25;
        double z = center.z + Math.sin(angle) * offset;
        float size = (float) Mth.clamp(radius * (0.28 + random.nextDouble() * 0.1), 0.45, 1.65);
        int lifetime = 42 + random.nextInt(24);
        int variant = random.nextInt(3);
        // 调用本项目粒子添加函数：近处烟沿用原有三种轮廓和随机初速度。
        addSmokeParticle(minecraft, level, random, x, y, z, size, lifetime, variant, SmokeLayer.NEAR);
    }

    /** 从水平错相的单个生成点发射略微错位的深色核心与浅色外层。 */
    private static void emitLongSmokePair(Minecraft minecraft, ClientLevel level, RandomSource random,
                                          Vec3 center, double radius, double height,
                                          double horizontalPhase) {
        double x = center.x + Math.sin(horizontalPhase) * Parameter.HORIZONTAL_OFFSET.value();
        double y = center.y + Math.max(0.1, height * 0.22) + random.nextDouble() * 0.25;
        double z = center.z + Math.sin(horizontalPhase + Math.toRadians(Parameter.Z_PHASE.value()))
                * Parameter.HORIZONTAL_OFFSET.value();
        float coreSize = (float) Mth.clamp(radius * (0.44 + random.nextDouble() * 0.12), 0.8, 2.4)
                * Parameter.SIZE_SCALE.floatValue();
        // 调用本项目粒子添加函数：第 1 枚用 boom/smoke.png 构成较小、较深的烟柱核心。
        addSmokeParticle(minecraft, level, random, x, y, z, coreSize,
                Parameter.LIFETIME.intValue(), 0, SmokeLayer.LONG_CORE);

        double edgeAngle = horizontalPhase + 0.8 + random.nextDouble() * 1.6;
        double edgeOffset = Mth.clamp(radius * (0.22 + random.nextDouble() * 0.16), 0.35, 1.2);
        double edgeX = x + Math.cos(edgeAngle) * edgeOffset;
        double edgeY = y + (random.nextDouble() - 0.5) * 0.9;
        double edgeZ = z + Math.sin(edgeAngle) * edgeOffset;
        float edgeSize = coreSize * (1.28f + random.nextFloat() * 0.18f)
                * Parameter.OUTER_SIZE_SCALE.floatValue();
        // 调用本项目粒子添加函数：第 2 枚共用 boom/smoke.png，以更大、更透明的错位烟团包住核心。
        addSmokeParticle(minecraft, level, random, edgeX, edgeY, edgeZ, edgeSize,
                Parameter.LIFETIME.intValue(), 0, SmokeLayer.LONG_OUTER);
    }

    /** 按给定烟层创建一枚粒子；长程与近处烟共用随机初速度。 */
    private static void addSmokeParticle(Minecraft minecraft, ClientLevel level, RandomSource random,
                                         double x, double y, double z, float size, int lifetime,
                                         int variant, SmokeLayer layer) {
        double vx = (random.nextDouble() - 0.5) * 0.035;
        double vy = 0.025 + random.nextDouble() * 0.025;
        double vz = (random.nextDouble() - 0.5) * 0.035;
        // 调用本项目烟粒子工厂：按烟层选贴图，长程粒子再随上升高度增强东向风。
        minecraft.particleEngine.add(RVP_WreckSmokeParticle.create(level, x, y, z,
                vx, vy, vz, size, lifetime, variant, layer));
    }
}
