package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.particle.RVP_NearSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_WreckSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_WreckSmokeParticle.SmokeLayer;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Snapshot;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffController;
import org.ywzj.rvp.vehicle.BoneEngineConfig;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** 客户端逐载具发射 RVP 击毁烟；与本体 tickParticle 的屏蔽注入点相互独立。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckSmokeEmitter {

    /** 客户端额外发射距离阈值，单位格；实际仍受载具实体追踪和客户端渲染距离限制。 */
    private static final double EMIT_RANGE = 1536.0;
    /** 单辆载具每轮原有短烟基础预算上限；调参数量倍率在此之后计算，最多12枚。 */
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
    /** 当前预览是否只发射短烟；原指令start入口仍默认只预览长烟。 */
    private static boolean previewNear;

    private RVP_WreckSmokeEmitter() {
    }

    /** 在指定客户端位置开启 60 秒长程烟预览，无须生成或击毁真实载具。 */
    public static void startPreview(ClientLevel level, Vec3 center) {
        // 保留原指令入口的长烟预览语义，F8通过独立选择预览短烟。
        startPreview(level, center, false);
    }

    /** 按长烟或短烟选择，在指定位置开启60秒的纯客户端预览。 */
    private static void startPreview(ClientLevel level, Vec3 center, boolean near) {
        previewLevel = level;
        previewCenter = center;
        previewNear = near;
        previewEndGameTime = level.getGameTime() + PREVIEW_DURATION_TICKS;
        previewLastEmissionGameTime = Long.MIN_VALUE;
    }

    /** F8的无伤害预览入口；使用准星命中点，未命中时使用眼前六格。 */
    public static String previewAtCrosshair(boolean near) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            throw new IllegalStateException("必须进入世界后才能预览击毁烟");
        }
        HitResult hit = minecraft.hitResult;
        Vec3 center = hit != null && hit.getType() != HitResult.Type.MISS ? hit.getLocation()
                : minecraft.player.getEyePosition().add(minecraft.player.getLookAngle().scale(6));
        // 调用正式预览烟源，复用真实残骸的生成参数和粒子，不创建实体、不造成伤害。
        startPreview(minecraft.level, center, near);
        return "已在准星位置预览击毁" + (near ? "短烟" : "长烟") + "60秒；重新预览会替换当前烟源。";
    }

    /** 停止预览并释放对客户端世界的引用。 */
    public static void stopPreview() {
        previewCenter = null;
        previewLevel = null;
        previewEndGameTime = 0;
        previewLastEmissionGameTime = Long.MIN_VALUE;
        previewNear = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            // 调用本项目殉燃清理器，退出世界时同步释放挂点和预览。
            RVP_WreckCookoffController.clear();
            // 调用本项目预览清理入口：离开世界后不再保留旧客户端世界对象。
            stopPreview();
            return;
        }
        // 调用本项目预览发射入口：准星位置的临时烟与真实残骸共用所选烟种的生成逻辑。
        emitPreviewSmoke(minecraft, level);
        // 调用本项目殉燃 tick 门控，共用当前实体遍历而不改变烟雾自身周期。
        boolean cookoffTick = RVP_WreckCookoffController.begin(level);
        for (Entity entity : level.entitiesForRendering()) {
            if (cookoffTick && entity instanceof AbstractVehicle vehicle && !(entity instanceof VehiclePart)) {
                // 调用自动殉燃观察器；它自行筛选地面车型、击毁状态以及显式预览。
                RVP_WreckCookoffController.consider(vehicle);
            }
            if (!(entity instanceof AbstractVehicle vehicle)
                    || entity instanceof VehiclePart
                    || !vehicle.isDestroyed()
                    || vehicle.isRemoved()
                    || minecraft.player.distanceToSqr(vehicle) > EMIT_RANGE * EMIT_RANGE) {
                continue;
            }
            boolean emitNear = vehicle.tickCount % Parameter.NEAR_EMIT_PERIOD.intValue() == 0
                    && Parameter.NEAR_COUNT_SCALE.value() > 0;
            boolean emitLong = vehicle.tickCount % Parameter.EMIT_PERIOD.intValue() == 0
                    && RVP_WreckCookoffController.canEmitLongSmoke(vehicle, level.getGameTime());
            if (!emitNear && !emitLong) {
                continue;
            }
            // 调用本项目残骸烟生成器：只覆盖载具本体，脱落 VehiclePart 继续使用本体特效。
            emitVehicleSmoke(minecraft, level, vehicle, emitNear, emitLong);
        }
        if (cookoffTick) {
            // 调用殉燃预算结算：先收集全部残骸，再公平分配原版火焰生成量。
            RVP_WreckCookoffController.end();
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
            // 调用每轮出生快照和小数烟量抽样；倍率1时保持原2～4枚预算及随机序列。
            Snapshot settings = RVP_WreckSmokeDebugSettings.snapshot();
            int count = RVP_WreckSmokeLogic.nearCount(
                    Mth.clamp(Mth.ceil(Math.max(width, depth) / 2.5), 2, MAX_SMOKE_PER_ROUND),
                    settings.value(Parameter.NEAR_COUNT_SCALE), random);
            // 调用本项目发动机骨骼解析入口：短程击毁烟优先从车体发动机实时 OBB 内生成。
            if (count > 0) {
                emitNearSmokeAtEngine(minecraft, level, random, vehicle, count, center, radius, height, settings);
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

    /** 在准星位置按所选烟种的真实周期发射，超时或切换世界时清理状态。 */
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
        int period = previewNear ? Parameter.NEAR_EMIT_PERIOD.intValue() : Parameter.EMIT_PERIOD.intValue();
        if (gameTime == previewLastEmissionGameTime || gameTime % period != 0) {
            return;
        }
        previewLastEmissionGameTime = gameTime;
        if (previewNear) {
            // 调用正式短烟生成入口；预览无发动机结构，用原车体范围兜底，便于观察尺寸与运动。
            Snapshot settings = RVP_WreckSmokeDebugSettings.snapshot();
            int count = RVP_WreckSmokeLogic.nearCount(2, settings.value(Parameter.NEAR_COUNT_SCALE), level.random);
            for (int i = 0; i < count; i++) {
                emitNearSmokeParticle(minecraft, level, level.random, previewCenter, PREVIEW_RADIUS, PREVIEW_HEIGHT, settings);
            }
            return;
        }
        // 调用本项目相位与发射入口：预览和真实残骸使用同一套当前调试参数。
        emitLongSmokePair(minecraft, level, level.random, previewCenter, PREVIEW_RADIUS, PREVIEW_HEIGHT,
                horizontalPhase(gameTime));
    }

    /** 在载具主结构附近随机取样，保留近处浓烟原有的尺寸、寿命和分布。 */
    private static void emitNearSmokeParticle(Minecraft minecraft, ClientLevel level, RandomSource random,
                                              Vec3 center, double radius, double height, Snapshot settings) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double offset = Math.sqrt(random.nextDouble()) * radius * 0.8 * settings.value(Parameter.NEAR_SOURCE_SCALE);
        double x = center.x + Math.cos(angle) * offset;
        double y = center.y + Math.max(0.1, height * 0.22)
                + random.nextDouble() * 0.25 * settings.value(Parameter.NEAR_SOURCE_SCALE)
                + settings.value(Parameter.NEAR_VERTICAL_OFFSET);
        double z = center.z + Math.sin(angle) * offset;
        // 调用原有车体尺寸规则后附加倍率；寿命使用与面板共用的合法随机区间。
        float size = RVP_NearSmokeParticle.resolveWreckStyleSize(radius, random.nextDouble())
                * (float) settings.value(Parameter.NEAR_SIZE_SCALE);
        int lifetime = settings.integer(Parameter.NEAR_LIFETIME_MIN) + random.nextInt(
                settings.integer(Parameter.NEAR_LIFETIME_MAX) - settings.integer(Parameter.NEAR_LIFETIME_MIN) + 1);
        int variant = random.nextInt(3);
        // 调用本项目通用近处烟粒子：击毁载具使用普通样式，不触发引擎瘫痪动画。
        addNearSmokeParticle(minecraft, level, random, x, y, z, size, lifetime, variant, settings);
    }

    /** 从水平错相的单个生成点发射略微错位的深色核心与浅色外层。 */
    private static void emitLongSmokePair(Minecraft minecraft, ClientLevel level, RandomSource random,
                                          Vec3 center, double radius, double height,
                                          double horizontalPhase) {
        double x = center.x + Math.sin(horizontalPhase) * Parameter.HORIZONTAL_OFFSET.value();
        double y = center.y + Math.max(0.1, height * 0.22) + Parameter.LONG_VERTICAL_OFFSET.value()
                + random.nextDouble() * Parameter.LONG_VERTICAL_JITTER.value();
        double z = center.z + Math.sin(horizontalPhase + Math.toRadians(Parameter.Z_PHASE.value()))
                * Parameter.HORIZONTAL_OFFSET.value();
        float coreSize = (float) Mth.clamp(radius * (0.44 + random.nextDouble() * 0.12), 0.8, 2.4)
                * Parameter.SIZE_SCALE.floatValue();
        // 调用本项目粒子添加函数：第 1 枚用 boom/smoke.png 构成较小、较深的烟柱核心。
        addSmokeParticle(minecraft, level, random, x, y, z, coreSize,
                Parameter.LIFETIME.intValue(), 0, SmokeLayer.LONG_CORE);

        double edgeAngle = horizontalPhase + 0.8 + random.nextDouble() * 1.6;
        double edgeOffset = Mth.clamp(radius * (0.22 + random.nextDouble() * 0.16), 0.35, 1.2)
                * Parameter.LONG_OUTER_OFFSET_SCALE.value();
        double edgeX = x + Math.cos(edgeAngle) * edgeOffset;
        double edgeY = y + (random.nextDouble() - 0.5) * 0.9 * Parameter.LONG_OUTER_OFFSET_SCALE.value();
        double edgeZ = z + Math.sin(edgeAngle) * edgeOffset;
        float edgeSize = coreSize * (1.28f + random.nextFloat() * 0.18f)
                * Parameter.OUTER_SIZE_SCALE.floatValue();
        // 调用本项目粒子添加函数：第 2 枚共用 boom/smoke.png，以更大、更透明的错位烟团包住核心。
        addSmokeParticle(minecraft, level, random, edgeX, edgeY, edgeZ, edgeSize,
                Parameter.LIFETIME.intValue(), 0, SmokeLayer.LONG_OUTER);
    }

    /** 添加一枚击毁短烟；专用入口与受损发动机普通入口隔离。 */
    private static void addNearSmokeParticle(Minecraft minecraft, ClientLevel level, RandomSource random,
                                             double x, double y, double z, float size, int lifetime,
                                             int variant, Snapshot settings) {
        double vx = (random.nextDouble() - 0.5) * settings.value(Parameter.NEAR_INITIAL_SPREAD);
        double vy = settings.value(Parameter.NEAR_INITIAL_UPDRAFT_MIN) + random.nextDouble()
                * (settings.value(Parameter.NEAR_INITIAL_UPDRAFT_MAX) - settings.value(Parameter.NEAR_INITIAL_UPDRAFT_MIN));
        double vz = (random.nextDouble() - 0.5) * settings.value(Parameter.NEAR_INITIAL_SPREAD);
        // 调用击毁短烟专用工厂，已有短烟保持出生快照，受损发动机继续走原create入口。
        minecraft.particleEngine.add(RVP_NearSmokeParticle.createWreck(level, x, y, z,
                vx, vy, vz, size, lifetime, variant, settings));
    }

    /**
     * 在发动机实时 OBB 内生成短程击毁烟；数量沿用车体主 OBB 的原有预算。
     *
     * <p>调用本项目 {@link RVP_VehicleHitboxFactorManager} 的公共骨骼 OBB 入口，
     * 让烟点随载具位移、姿态和发动机骨结构实时更新；发动机配置缺失时回退原主 OBB 取点。</p>
     */
    private static void emitNearSmokeAtEngine(Minecraft minecraft, ClientLevel level, RandomSource random,
                                              AbstractVehicle vehicle, int count, Vec3 fallbackCenter,
                                              double fallbackRadius, double fallbackHeight, Snapshot settings) {
        // 调用原有发动机实时包围盒入口，只调出烟取点范围，不修改模型或碰撞盒。
        List<OBB> engineObbs = resolveEngineObbs(vehicle);
        if (engineObbs.isEmpty()) {
            for (int i = 0; i < count; i++) {
                // 调用本项目旧版主 OBB 短程烟入口：无发动机骨数据时保持原有视觉兜底。
                emitNearSmokeParticle(minecraft, level, random, fallbackCenter, fallbackRadius, fallbackHeight, settings);
            }
            return;
        }
        for (int i = 0; i < count; i++) {
            OBB engineObb = engineObbs.get(i % engineObbs.size());
            // 调用本项目取点入口，以包围盒中心为基准缩放局部随机范围。
            Vec3 point = randomPointIn(engineObb, random, settings.value(Parameter.NEAR_SOURCE_SCALE));
            if (point == null) {
                continue;
            }
            Vector3f extents = engineObb.extents();
            // OBB extents 是半尺寸；用发动机 OBB 的水平半径复用击毁烟尺寸规则。
            double engineRadius = Math.max(0.5, Math.min(extents.x, extents.z));
            // 调用原有烟尺寸规则后附加击毁专用倍率，发动机受损烟不读取该倍率。
            float size = RVP_NearSmokeParticle.resolveWreckStyleSize(engineRadius, random.nextDouble())
                    * (float) settings.value(Parameter.NEAR_SIZE_SCALE);
            int lifetime = settings.integer(Parameter.NEAR_LIFETIME_MIN) + random.nextInt(
                    settings.integer(Parameter.NEAR_LIFETIME_MAX) - settings.integer(Parameter.NEAR_LIFETIME_MIN) + 1);
            int variant = random.nextInt(3);
            // 调用本项目通用近处烟粒子：发动机位置使用普通击毁烟样式。
            addNearSmokeParticle(minecraft, level, random, point.x,
                    point.y + settings.value(Parameter.NEAR_VERTICAL_OFFSET), point.z, size, lifetime, variant, settings);
        }
    }

    /** 解析载具所有配置了 ENGINE 模块的实时 OBB；失败返回空列表，由调用方决定回退。 */
    private static List<OBB> resolveEngineObbs(AbstractVehicle vehicle) {
        // 调用本项目发动机配置解析器：只取当前载具 bone_modules 中的 ENGINE 骨名。
        java.util.Map<String, BoneEngineConfig> engineModules =
                RVP_VehicleHitboxFactorManager.INSTANCE.resolveEngineModules(vehicle);
        if (engineModules == null || engineModules.isEmpty()) {
            return List.of();
        }
        List<OBB> result = new ArrayList<>();
        for (String boneName : engineModules.keySet()) {
            // 调用本项目实时骨骼 OBB 入口：与损坏引擎冒烟共用同一位置解析链路。
            result.addAll(RVP_VehicleHitboxFactorManager.INSTANCE
                    .resolveBoneObbsForSampling(vehicle, boneName));
        }
        return result;
    }

    /** 在 OBB 局部空间均匀取样一点，并转换为当前载具姿态下的世界坐标。 */
    private static Vec3 randomPointIn(OBB obb, RandomSource random, double scale) {
        Vector3f extents = obb.extents();
        Vector3f local = new Vector3f(
                (random.nextFloat() * 2.0f - 1.0f) * extents.x,
                (random.nextFloat() * 2.0f - 1.0f) * extents.y,
                (random.nextFloat() * 2.0f - 1.0f) * extents.z);
        local.mul((float) scale);
        // 调用本体局部转世界坐标入口，让烟源跟随载具当前姿态。
        Vector3f world = obb.localToWorld(local, obb.getAxes());
        return new Vec3(world.x, world.y, world.z);
    }

    /** 添加一枚长程烟粒子；核心和外层共用随机初速度。 */
    private static void addSmokeParticle(Minecraft minecraft, ClientLevel level, RandomSource random,
                                         double x, double y, double z, float size, int lifetime,
                                         int variant, SmokeLayer layer) {
        // 读取同一参数表的长烟出生速度，指令与F8修改在下一枚粒子生效。
        double vx = (random.nextDouble() - 0.5) * Parameter.LONG_INITIAL_SPREAD.value();
        double vy = Parameter.LONG_INITIAL_UPDRAFT_MIN.value() + random.nextDouble()
                * (Parameter.LONG_INITIAL_UPDRAFT_MAX.value() - Parameter.LONG_INITIAL_UPDRAFT_MIN.value());
        double vz = (random.nextDouble() - 0.5) * Parameter.LONG_INITIAL_SPREAD.value();
        // 调用本项目长程烟粒子工厂：按核心/外层选择贴图和透明度。
        minecraft.particleEngine.add(RVP_WreckSmokeParticle.create(level, x, y, z,
                vx, vy, vz, size, lifetime, variant, layer));
    }
}
