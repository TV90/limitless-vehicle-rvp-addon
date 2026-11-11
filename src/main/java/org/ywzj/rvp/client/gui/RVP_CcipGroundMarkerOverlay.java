package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_CcipMarkerData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Explosion;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleAerialBomb;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

/**
 * 火箭炮/航弹 CCIP 的地面落点标记（BF6 风格）：落点处按爆炸半径做地形采样圆环 + 竖直引线。
 *
 * <p>只在准星落点确实位于地表时绘制——本体 CCIP 计算的空中保底点仍会被本体准星画出，
 * 但这里通过落点与地形高度比对将其排除，不对空绘制标记。</p>
 *
 * <p>绘制在 {@link RenderLevelStageEvent} 世界阶段：HUD 层不参与世界深度，压在土坡/山体上的
 * 圆环会整段浮在地形上。这里在所有半透明方块之后、用带 LEQUAL 深度测试的四边形绘制，
 * 陷入地形或躲在物体后的部分会被正常遮挡。</p>
 *
 * <p>视角矩阵：事件 pose 在该阶段尚未写入 RenderSystem modelview，这里把它整体复制到
 * modelview 栈，保留本体座舱滚转，再用相机相对坐标直接提交顶点。</p>
 *
 * <p>圆环高度按需求做平滑（2026-11-11 用户：“不一定要严格的地面采样，做点效果和平滑就行”）：
 * 逐列采样后做环形 3 点均值，并夹在落点地表 ±{@code ccip_marker_data.height_clamp} 格内，
 * 避免跨方块/悬崖时圆环被拉成竖条。尺寸/颜色均走武器侧 {@link RVP_CcipMarkerData}。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CcipGroundMarkerOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 圆环采样段数（几何精度，非外观参数；固定 48 段足够平滑）。 */
    private static final int RING_SEGMENTS = 48;
    /** FOV 反算失败时的兜底 tan(fov/2)（约 70°）。 */
    private static final double FALLBACK_TAN_HALF_FOV = 0.7D;
    /** 准星落点与地表高度允许偏差（格）；用于区分真实地面落点与空中保底点。 */
    private static final double GROUND_TOLERANCE = 1.5D;

    /** 临时门控诊断开关（定位完即可删）。失败原因每秒最多一条，"已绘制"只记一次。 */
    private static final boolean DIAG_ENABLED = true;
    private static long diagLastMs;
    private static boolean diagDrawnLogged;

    private RVP_CcipGroundMarkerOverlay() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || !lvp.onVehicle() || !(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return;
        }
        WeaponUnit operatorUnit = lvp.getWeaponUnit();
        if (operatorUnit == null || operatorUnit.getCurrentWeapon().isEmpty()) {
            diag("noWeaponUnit", vehicle, null, null, null, 0.0F);
            return;
        }
        WeaponUnitData.FireControlSensorType sensor = RVP_WeaponSensorHelper.effectiveSensorType(operatorUnit);
        AbstractVehicleWeapon<?> primary = RVP_WeaponResolveHelper.currentPrimary(operatorUnit);
        if (sensor != WeaponUnitData.FireControlSensorType.CCIP) {
            diag("sensor=" + sensor, vehicle, operatorUnit, primary, null, 0.0F);
            return;
        }
        if (!supportsVehicleWeapon(vehicle, primary)) {
            diag("unsupported", vehicle, operatorUnit, primary, null, 0.0F);
            return;
        }
        float radius = resolveExplosionRadius(primary);
        if (radius <= 0.05F) {
            diag("radius=0", vehicle, operatorUnit, primary, null, radius);
            return;
        }
        // 外观参数走武器侧 ccip_marker_data；非 RVP 武器回退默认外观。
        RVP_CcipMarkerData marker = resolveMarkerData(primary);
        Vec3 hit = operatorUnit.weaponHitPos;
        if (hit == null) {
            diag("hit=null", vehicle, operatorUnit, primary, null, radius);
            return;
        }
        float partialTick = event.getPartialTick();
        Vec3 previous = operatorUnit.weaponHitPosO;
        Vec3 impact = previous != null ? previous.lerp(hit, Mth.clamp(partialTick, 0.0F, 1.0F)) : hit;
        if (!isOnGround(mc.level, impact)) {
            diag("notGround", vehicle, operatorUnit, primary, impact, radius);
            return;
        }
        drawMarker(event, mc.level, impact, radius, marker);
        diag("drawn", vehicle, operatorUnit, primary, impact, radius);
    }

    /** 仅地面载具的火箭炮、空中载具的炸弹配发该标记。 */
    private static boolean supportsVehicleWeapon(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon) {
        if (weapon == null) {
            return false;
        }
        if (vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle) {
            return isRocket(weapon);
        }
        if (vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle) {
            return isBomb(weapon);
        }
        return false;
    }

    private static boolean isRocket(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleRocket) {
            return true;
        }
        return weapon instanceof RVP_WeaponBase rvp
                && rvp.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET;
    }

    private static boolean isBomb(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleAerialBomb) {
            return true;
        }
        return weapon instanceof RVP_WeaponBase rvp
                && rvp.getData().getWeaponKind() == RVP_EnumWeaponKind.BOMB;
    }

    private static float resolveExplosionRadius(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleRocket rocket && rocket.getData() != null) {
            return rocket.getData().getExplosion().radius;
        }
        if (weapon instanceof VehicleAerialBomb bomb && bomb.getData() != null) {
            return bomb.getData().getExplosion().radius;
        }
        if (weapon instanceof RVP_WeaponBase rvp) {
            RVP_Explosion explosion = rvp.getData().getExplosionData();
            return explosion != null ? explosion.radius : 0.0F;
        }
        return 0.0F;
    }

    /** 读取武器侧标记外观；非 RVP 武器（本体火箭/航弹）无法携带该配置，走默认外观。 */
    private static RVP_CcipMarkerData resolveMarkerData(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof RVP_WeaponBase rvp && rvp.getData() != null) {
            return rvp.getData().getCcipMarkerData();
        }
        return RVP_CcipMarkerData.DEFAULT;
    }

    /**
     * 指定列的地表高度（地表上方第一格空气）。
     *
     * <p>已加载区块走原版 MOTION_BLOCKING 高度图；未加载区块回退到战术地图缓存高度
     * （WORLD_SURFACE，与 MOTION_BLOCKING 在普通地形上等价），两者都拿不到返回 null。</p>
     */
    private static Integer surfaceY(Level level, int x, int z) {
        if (level.hasChunkAt(new BlockPos(x, 0, z))) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        return RVP_TacticalMapCache.getCachedHeight(x, z);
    }

    /**
     * 准星落点是否在地表。高度图为“地表上方第一格空气”，方块顶面命中与之相差约 1 格，
     * 空中保底点（沿瞄准方向 1024 格）则远高于地表。
     */
    private static boolean isOnGround(Level level, Vec3 hit) {
        Integer surface = surfaceY(level, Mth.floor(hit.x), Mth.floor(hit.z));
        return surface != null && Math.abs(hit.y - surface) <= GROUND_TOLERANCE;
    }

    private static void drawMarker(RenderLevelStageEvent event, Level level, Vec3 impact,
                                   float radius, RVP_CcipMarkerData marker) {
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        double centerDistance = cameraPos.distanceTo(impact);
        float width = (float) Mth.clamp(centerDistance * marker.getRingWidthPerBlock(),
                marker.getRingMinWidth(), marker.getRingMaxWidth());
        float lineWidth = (float) Mth.clamp(centerDistance * marker.getLineWidthPerBlock(),
                marker.getLineMinWidth(), marker.getLineMaxWidth());
        // 投影矩阵的 m11 = cot(垂直FOV/2)：世界高度 × m11 ÷ (2×距离) 就是屏幕高度占比，
        // 因此按占比反算世界高度时除以 m11，缩放的观瞄视角也能保持同样的屏幕长度。
        double projectionM11 = event.getProjectionMatrix().m11();
        if (!(projectionM11 > 1.0E-4D) || !Double.isFinite(projectionM11)) {
            projectionM11 = 1.0D / FALLBACK_TAN_HALF_FOV;
        }
        double lineHeight = Mth.clamp(
                centerDistance * marker.getLineHeightScreenFraction() * 2.0D / projectionM11,
                marker.getLineMinHeight(), marker.getLineMaxHeight());

        // 圆环半径按武器爆炸半径的设定比例，并叠加随距离增长的软下限：
        // 用平方和而非硬 max，远处只被渐进托底，同一弹种始终保持近大远小，远圈不会反超近圈。
        double physicalRadius = radius * marker.getRadiusScale();
        double minimumRadius = centerDistance * projectionM11 * marker.getMinRingScreenFraction();
        double ringRadius = Math.sqrt(physicalRadius * physicalRadius + minimumRadius * minimumRadius);

        Integer impactSurface = surfaceY(level, Mth.floor(impact.x), Mth.floor(impact.z));
        if (impactSurface == null) {
            return;
        }

        double[] ringX = new double[RING_SEGMENTS];
        double[] ringY = new double[RING_SEGMENTS];
        double[] ringZ = new double[RING_SEGMENTS];
        boolean[] visible = new boolean[RING_SEGMENTS];
        double angleStep = Math.PI * 2.0D / RING_SEGMENTS;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double angle = angleStep * i;
            double worldX = impact.x + Math.cos(angle) * ringRadius;
            double worldZ = impact.z + Math.sin(angle) * ringRadius;
            Integer surface = surfaceY(level, Mth.floor(worldX), Mth.floor(worldZ));
            if (surface == null) {
                continue;
            }
            ringX[i] = worldX;
            ringY[i] = surface;
            ringZ[i] = worldZ;
            visible[i] = true;
        }
        // 圆环高度平滑：环形 3 点均值 + 夹在落点地表附近，跨方块/悬崖时不会被拉成竖条。
        double[] smoothY = ringY.clone();
        for (int i = 0; i < RING_SEGMENTS; i++) {
            if (!visible[i]) {
                continue;
            }
            int prev = (i + RING_SEGMENTS - 1) % RING_SEGMENTS;
            int next = (i + 1) % RING_SEGMENTS;
            double sum = ringY[i];
            int count = 1;
            if (visible[prev]) {
                sum += ringY[prev];
                count++;
            }
            if (visible[next]) {
                sum += ringY[next];
                count++;
            }
            double smoothed = sum / count;
            smoothY[i] = Mth.clamp(smoothed,
                    impactSurface - marker.getHeightClamp(),
                    impactSurface + marker.getHeightClamp()) + marker.getSurfaceLift();
        }

        // 引线：世界竖直光柱，取水平面内朝向相机的法线做宽度，任何视角都不会看成一条边。
        double dx = impact.x - cameraPos.x;
        double dz = impact.z - cameraPos.z;
        double horizontalLength = Math.sqrt(dx * dx + dz * dz);
        double lineNormalX;
        double lineNormalZ;
        if (horizontalLength < 1.0E-4D) {
            lineNormalX = 0.0D;
            lineNormalZ = lineWidth * 0.5D;
        } else {
            lineNormalX = -dz / horizontalLength * lineWidth * 0.5D;
            lineNormalZ = dx / horizontalLength * lineWidth * 0.5D;
        }

        Minecraft mc = Minecraft.getInstance();
        // 冲掉世界上一个批次的待提交几何，避免与全局 Tesselator 的 builder 抢用。
        mc.renderBuffers().bufferSource().endBatch();
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        try {
            // 事件 pose 已按滚转→俯仰→偏航写入相机视图（含本体座舱滚转），
            // 这里整体复制到 modelview 栈，顶点仍用相机相对坐标。
            view.setIdentity();
            view.mulPoseMatrix(event.getPoseStack().last().pose());
            RenderSystem.applyModelViewMatrix();

            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (int i = 0; i < RING_SEGMENTS; i++) {
                int next = (i + 1) % RING_SEGMENTS;
                if (!visible[i] || !visible[next]) {
                    continue;
                }
                appendGroundSegment(buffer, cameraPos,
                        ringX[i], smoothY[i], ringZ[i],
                        ringX[next], smoothY[next], ringZ[next],
                        width, marker.getRingColor());
            }
            appendVerticalLine(buffer, cameraPos, impact, lineNormalX, lineNormalZ, lineHeight,
                    marker.getLineBottomColor(), marker.getLineTopColor());

            BufferUploader.drawWithShader(buffer.end());
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } finally {
            view.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** 贴地环段：水平面内取垂直于线段方向的法线，两个端点各自贴各自（已平滑）的地表高度。 */
    private static void appendGroundSegment(BufferBuilder buffer, Vec3 camera,
                                            double x0, double y0, double z0,
                                            double x1, double y1, double z1,
                                            float width, int color) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        double offsetX = -dz / length * width * 0.5D;
        double offsetZ = dx / length * width * 0.5D;
        vertex(buffer, camera, x0 + offsetX, y0, z0 + offsetZ, color);
        vertex(buffer, camera, x1 + offsetX, y1, z1 + offsetZ, color);
        vertex(buffer, camera, x1 - offsetX, y1, z1 - offsetZ, color);
        vertex(buffer, camera, x0 - offsetX, y0, z0 - offsetZ, color);
    }

    /** 竖直引线：底端颜色渐变到顶端颜色。 */
    private static void appendVerticalLine(BufferBuilder buffer, Vec3 camera, Vec3 impact,
                                           double normalX, double normalZ, double height,
                                           int bottomColor, int topColor) {
        double bottomY = impact.y;
        double topY = impact.y + height;
        vertex(buffer, camera, impact.x + normalX, bottomY, impact.z + normalZ, bottomColor);
        vertex(buffer, camera, impact.x + normalX, topY, impact.z + normalZ, topColor);
        vertex(buffer, camera, impact.x - normalX, topY, impact.z - normalZ, topColor);
        vertex(buffer, camera, impact.x - normalX, bottomY, impact.z - normalZ, bottomColor);
    }

    private static void vertex(BufferBuilder buffer, Vec3 camera, double x, double y, double z, int argb) {
        // 顶点使用相机相对坐标：世界 modelview 已在 drawMarker 里显式重建。
        buffer.vertex((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .color((argb >> 16 & 0xFF) / 255.0F,
                        (argb >> 8 & 0xFF) / 255.0F,
                        (argb & 0xFF) / 255.0F,
                        (argb >>> 24) / 255.0F)
                .endVertex();
    }

    /**
     * 临时门控诊断：把每个提前返回的原因和关键数值打到客户端日志，失败每秒最多一条，
     * 首次成功只记一条。定位完成后删除本方法与所有调用点。
     */
    private static void diag(String reason, AbstractVehicle vehicle, WeaponUnit unit,
                             AbstractVehicleWeapon<?> weapon, Vec3 impact, float radius) {
        if (!DIAG_ENABLED) {
            return;
        }
        if ("drawn".equals(reason) && diagDrawnLogged) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - diagLastMs < 1000L) {
            return;
        }
        diagLastMs = now;
        if ("drawn".equals(reason)) {
            diagDrawnLogged = true;
        }
        Minecraft mc = Minecraft.getInstance();
        String detail = "";
        if (impact != null && mc.level != null) {
            int x = Mth.floor(impact.x);
            int z = Mth.floor(impact.z);
            Integer surface = surfaceY(mc.level, x, z);
            detail = String.format(" surface=%s delta=%s chunk=%s dist=%.1f",
                    surface,
                    surface == null ? "?" : String.format("%.2f", impact.y - surface),
                    mc.level.hasChunkAt(new BlockPos(x, 0, z)),
                    Math.sqrt(vehicle.position().distanceToSqr(impact)));
        }
        LOGGER.info("[RVP][CcipMarker] reason={} vehicle={} unit={} weapon={} sensor={} hit={} radius={}{}",
                reason,
                vehicle.getClass().getSimpleName(),
                unit == null ? "null" : unit.getId(),
                weapon == null ? "null" : weapon.getClass().getSimpleName(),
                unit == null ? "?" : RVP_WeaponSensorHelper.effectiveSensorType(unit),
                impact,
                radius,
                detail);
    }
}
