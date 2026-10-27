package org.ywzj.rvp.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.render.RVP_MapIconTexture;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientMarkedBlockState;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.network.S2CMarkedBlockSync;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.weapon.RVP_RocketBallistics;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 轻量化炮兵地图（2026-10-26 批）。
 *
 * <p>作用：本地玩家在炮塔席选中「炮兵地图」武器（{@code misc_data.artillery_map} + 火箭/炸弹）时，
 * 左上角直接绘制一块 4:3 展板（风格复用命中展板外框），内容是落点居中的小地图。
 * 与全屏炮兵地图的分工：全屏是「锚定落点反算俯仰」，此处是「按当前炮口朝向/俯仰正算落点」，
 * 因此左键即可按本体常规开火路径发射，不占用空格。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>只在无 Screen 时渲染（{@link IGuiOverlay} 本身就不在 Screen 打开时被调用），
 *       K 打开全屏炮兵地图时本框自动消失、关闭后自动恢复，天然互斥；</li>
 *   <li>放大跟随本体右键逻辑：读 {@link AbstractVehicle#isViewZoomed()}，短边半径 ±660 ↔ ±220 格；</li>
 *   <li>落点由公开的 {@link RVP_RocketBallistics#computeArtilleryWeaponImpact} 自行解算，
 *       不写 {@code weaponUnit.weaponHitPos}、不碰 CCIP 平滑状态、不改 mixin；</li>
 *   <li>UI 元素与全屏炮兵地图完全一致（2026-10-26 用户定版）：共用
 *       {@link RVP_TacticalMapScreen} 的 icon 解析 + {@link RVP_MapIconTexture#blitIcon} 落批管线，
 *       整体缩至 40%（载具 12→4.8px、人员 10→4px、弹药 10→4px、GPS 12→4.8px、
 *       目标圆环 6→2.4px、落点白十字半长 5→2px、虚线 6/4→2.4/1.6px），无按钮无文字。</li>
 * </ul>
 */
public final class RVP_MiniArtilleryMapOverlay implements IGuiOverlay {

    /** 展板高占屏幕实际分辨率比例（宽按 4:3 反推）。 */
    private static final double PANEL_HEIGHT_FRAC = 0.33D;
    private static final double PANEL_ASPECT = 4.0D / 3.0D;
    /** 展板距屏幕左/上边缘的物理像素边距（与命中展板一致）。 */
    private static final int MARGIN_PX = 12;
    /** 边框与地图内容之间的留白（物理像素）。 */
    private static final int FRAME_PAD_PX = 3;
    /**
     * 常规视野：展板短边半径覆盖的世界格数（±660 格）。
     *
     * <p>2026-10-26 修正：展板按物理分辨率定尺，比例若按 GUI 像素写死，GUI 缩放越大实际覆盖越小
     * （自动缩放 4 时短边半径只有 249 格）。改为按内容像素反解比例，覆盖范围与 GUI 缩放无关。</p>
     */
    private static final double VIEW_RADIUS_BLOCKS = 660.0D;
    /** 本体右键放大时视野收缩到 1/3（±220 格），沿用原 1:6 → 1:2 的三倍关系。 */
    private static final double ZOOM_VIEW_DIVISOR = 3.0D;
    /** 落点解算间隔（Tick）：1200 tick 弹道含逐 tick 世界裁剪，10Hz + 平滑已足够跟手。 */
    private static final int SOLVE_INTERVAL_TICKS = 2;

    /** 2026-10-26 用户定版：UI 元素与全屏炮兵地图完全一致（同 icon/形状/配色），整体缩至 40%。 */
    private static final float ELEMENT_SCALE = 0.40F;
    /** 载具 icon：全屏地图 drawVehicleMarker 用 12px。 */
    private static final float VEHICLE_ICON_SIZE = 12F * ELEMENT_SCALE;
    /** 步兵/生物 icon：全屏地图 drawContactMarker 用 10px。 */
    private static final float CONTACT_ICON_SIZE = 10F * ELEMENT_SCALE;
    /** 飞行弹药 icon：全屏地图 drawMissileMarker 用 10px。 */
    private static final float AMMO_ICON_SIZE = 10F * ELEMENT_SCALE;
    /** GPS 航点 / TGT 标记 icon：全屏地图用 12px 的 gps.png。 */
    private static final float GPS_ICON_SIZE = 12F * ELEMENT_SCALE;
    /** 指定目标圆环半径：全屏地图炮兵模式用 6px。 */
    private static final float TARGET_CIRCLE_RADIUS = 6.0F * ELEMENT_SCALE;
    /** 落点白十字半长：全屏地图用 5px。 */
    private static final float IMPACT_CROSS_HALF = 5F * ELEMENT_SCALE;
    /** 落点↔自身连线虚线节拍：全屏地图 6 开 / 4 停。 */
    private static final float DASH_ON_PX = 6F * ELEMENT_SCALE;
    private static final float DASH_GAP_PX = 4F * ELEMENT_SCALE;

    private static final int COLOR_MAP_BACKGROUND = 0xFF0A0C0E;
    /** GPS 元素黄（与全屏地图 GPS_ICON_COLOR 同值）：航点、TGT、目标圆环兜底色。 */
    private static final int COLOR_GPS = 0xFFFFF08A;
    /** 落点连线：全屏地图同款 GPS 黄、alpha 0xA8。 */
    private static final int COLOR_LINK = 0xA8FFF08A;
    /** 落点白十字：全屏地图同款纯白。 */
    private static final int COLOR_IMPACT_CROSS = 0xFFFFFFFF;

    /** 屏内容上下文：当前武器与发射武器站。scopeHandCrank=true 时为手摇观瞄简化模式（无落点解算）。 */
    private record Context(AbstractVehicle vehicle, WeaponUnit operatorUnit, @Nullable WeaponUnit launchUnit,
                           @Nullable RVP_WeaponData data, @Nullable ResourceLocation weaponId,
                           boolean scopeHandCrank) {}

    private static long lastSolveTick = Long.MIN_VALUE;
    private static int lastVehicleId = Integer.MIN_VALUE;
    @Nullable
    private static ResourceLocation lastWeaponId;
    @Nullable
    private static Vec3 smoothImpact;

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) {
            resetState();
            return;
        }
        Context context = resolveContext(mc, player);
        if (context == null) {
            resetState();
            return;
        }
        // 换车/换弹种：旧落点与平滑状态全部作废，避免画上一发的结果。
        if (context.vehicle().getId() != lastVehicleId || !Objects.equals(context.weaponId(), lastWeaponId)) {
            lastVehicleId = context.vehicle().getId();
            lastWeaponId = context.weaponId();
            lastSolveTick = Long.MIN_VALUE;
            smoothImpact = null;
        }
        if (!context.scopeHandCrank()) {
            solveImpact(mc, context);
        }
        Vec3 center = smoothImpact != null ? smoothImpact : context.vehicle().position();
        drawPanel(mc, graphics, player, context, center, partialTick);
    }

    /**
     * @return 当前是否正在操控炮兵地图武器（且非瞄准镜视角）；或处于手摇（need_power=false）武器观瞄视角
     * （此时返回简化模式，地图以本车为中心、不做落点解算）；否则本框不显示。
     */
    @Nullable
    private static Context resolveContext(Minecraft mc, LocalPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return null;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null) {
            return null;
        }
        WeaponUnit operatorUnit = lvp.getWeaponUnit();
        if (operatorUnit == null) {
            return null;
        }
        if (lvp.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            // 手摇观瞄简化版：仅本地判定，不依赖当前武器是炮兵弹种。
            if (operatorUnit.needPower) {
                return null;
            }
            return new Context(vehicle, operatorUnit, null, null, null, true);
        }
        AbstractVehicleWeapon<?> currentWeapon = operatorUnit.getCurrentWeapon().orElse(null);
        if (!(currentWeapon instanceof RVP_WeaponBase weapon)) {
            return null;
        }
        RVP_WeaponData data = weapon.getData();
        RVP_EnumWeaponKind kind = data.getWeaponKind();
        if (!data.getMiscData().isArtilleryMap()
                || (kind != RVP_EnumWeaponKind.ROCKET && kind != RVP_EnumWeaponKind.BOMB)) {
            return null;
        }
        WeaponUnit launchUnit = currentWeapon.getWeaponUnit();
        ResourceLocation weaponId = data.getWeaponId();
        if (launchUnit == null || weaponId == null) {
            return null;
        }
        return new Context(vehicle, operatorUnit, launchUnit, data, weaponId, false);
    }

    /**
     * 正算落点：直接用炮口实际朝向（{@code aimContexts()}）+ 1200 tick 预测，
     * 未加载区块用缓存地形高度兜底、最后退到本车高度平面。与全屏炮兵地图的解算入口同源。
     */
    private static void solveImpact(Minecraft mc, Context context) {
        long now = mc.level.getGameTime();
        if (lastSolveTick != Long.MIN_VALUE && now - lastSolveTick < SOLVE_INTERVAL_TICKS) {
            return;
        }
        lastSolveTick = now;
        Vec3 raw = RVP_RocketBallistics.computeArtilleryWeaponImpact(
                mc.level, context.launchUnit(), context.vehicle().getDeltaMovement(),
                context.data(), context.vehicle(), context.vehicle().getY(),
                RVP_TacticalMapCache::getCachedHeight);
        if (raw == null) {
            // 本轮无解（例如炮口过高、超出预测步数）：保留上一帧落点，避免中心突然跳回本车。
            return;
        }
        if (smoothImpact == null) {
            smoothImpact = raw;
            return;
        }
        double distance = raw.distanceTo(smoothImpact);
        double alpha = Math.max(0.16D, Math.min(0.62D, 0.20D + distance * 0.08D));
        smoothImpact = smoothImpact.add(raw.subtract(smoothImpact).scale(alpha));
    }

    private static void drawPanel(Minecraft mc, GuiGraphics gg, LocalPlayer player, Context context, Vec3 center,
                                  float partialTick) {
        var window = mc.getWindow();
        double guiScale = window.getGuiScale();
        int panelH = Math.max(48, (int) Math.round(window.getScreenHeight() * PANEL_HEIGHT_FRAC / guiScale));
        int panelW = Math.max(64, (int) Math.round(panelH * PANEL_ASPECT));
        int margin = Math.max(2, (int) (MARGIN_PX / guiScale));
        int x0 = margin;
        int y0 = margin;
        int x1 = x0 + panelW;
        int y1 = y0 + panelH;

        // 外框与命中展板同款（灰底 + 黑边，GL_ALWAYS 覆盖先前 HUD），先落帧再画内容。
        int border = RVP_HitIndicatorOverlay.drawPanelFrame(gg, x0, y0, x1, y1, guiScale);
        gg.flush();

        int pad = Math.max(1, (int) (FRAME_PAD_PX / guiScale));
        int contentX0 = x0 + border + pad;
        int contentY0 = y0 + border + pad;
        int contentX1 = x1 - border - pad;
        int contentY1 = y1 - border - pad;
        int midX = (contentX0 + contentX1) / 2;
        int midY = (contentY0 + contentY1) / 2;

        gg.fill(contentX0, contentY0, contentX1, contentY1, COLOR_MAP_BACKGROUND);
        gg.enableScissor(contentX0, contentY0, contentX1, contentY1);
        try {
            double radiusBlocks = VIEW_RADIUS_BLOCKS
                    / (context.vehicle().isViewZoomed() ? ZOOM_VIEW_DIVISOR : 1.0D);
            double blocksPerPixel = radiusBlocks * 2.0D / (contentY1 - contentY0);
            double halfWidth = (contentX1 - contentX0) / 2.0D * blocksPerPixel;
            double halfHeight = (contentY1 - contentY0) / 2.0D * blocksPerPixel;
            double minX = center.x - halfWidth;
            double maxX = center.x + halfWidth;
            double minZ = center.z - halfHeight;
            double maxZ = center.z + halfHeight;

            drawTiles(gg, center.x, center.z, midX, midY, blocksPerPixel, minX, minZ, maxX, maxZ);
            gg.flush();

            int ownX = worldToScreen(context.vehicle().getX(), center.x, midX, blocksPerPixel);
            int ownY = worldToScreen(context.vehicle().getZ(), center.z, midY, blocksPerPixel);

            // 落点↔自身虚线（全屏地图同款 GPS 黄 αA8）：在本框内直接连到自身；出框时把终点夹到内容区边缘。
            int[] linkEnd = clampToContent(ownX, ownY, midX, midY, contentX0, contentY0, contentX1, contentY1);
            drawDashedLine(gg, midX, midY, linkEnd[0], linkEnd[1], COLOR_LINK);

            drawEntities(gg, mc, player, context, center, midX, midY, blocksPerPixel, partialTick,
                    contentX0, contentY0, contentX1, contentY1);

            drawGpsAndMarks(gg, player, center, midX, midY, blocksPerPixel,
                    contentX0, contentY0, contentX1, contentY1);

            // 落点圆环（套住白十字）+ 落点白十字（全屏炮兵地图 CCIP 标记同款，缩至 40%）。
            // 先 flush 掉批处理的连线/地形，圆环走自定义 shader 立即模式，避免被后 flush 的批处理盖住。
            // 圆环/十字都以 (0,0) 为基准，共用同一 pose 平移到标记点。
            // 炮兵模式固定画在面板中心（=落点）；手摇观瞄简化模式画在当前观瞄射线与地形的交点
            // （交点即直射弹着参考，指向天空时交点远在图外、被裁剪板自然裁掉）。
            gg.flush();
            var impactPose = gg.pose();
            impactPose.pushPose();
            double crossX = midX;
            double crossY = midY;
            if (context.scopeHandCrank()) {
                // 调用目的：与 LocalVehiclePlayer 的观瞄 tick 同源取当前武器站瞄准落点（1 射线/帧，只读）
                Vec3 aimPos = context.operatorUnit().aimHitPosition();
                crossX = worldToScreen(aimPos.x, center.x, midX, blocksPerPixel);
                crossY = worldToScreen(aimPos.z, center.z, midY, blocksPerPixel);
            }
            impactPose.translate(crossX, crossY, 0.0D);
            drawImpactRing(gg);
            // drawCross 的 half = size/2 且为整数像素：size<4 时横线退化成 1px，故保底 4；
            // 其内部 rotateAround(45°) 绕姿态原点旋转，必须先 translate 到落点再画 (0,0)，
            // 否则十字会被甩到屏幕左下角（与全屏地图 renderArtilleryCcipMarker 同款包裹）。
            RenderHelper.drawCross(gg, 0, 0,
                    Math.max(4, Math.round(IMPACT_CROSS_HALF * 2.0F)), COLOR_IMPACT_CROSS);
            impactPose.popPose();
            gg.flush();
        } finally {
            gg.disableScissor();
        }
    }

    /** 地形瓦片：整块按 1 纹理像素 = 1 世界格，用 pose 缩放到 1/blocksPerPixel，避免整数取整接缝。 */
    private static void drawTiles(GuiGraphics gg, double centerX, double centerZ, int midX, int midY,
                                  double blocksPerPixel, double minX, double minZ, double maxX, double maxZ) {
        List<RVP_TacticalMapCache.TilePos> tiles = RVP_TacticalMapCache.getVisibleTiles(minX, minZ, maxX, maxZ);
        if (tiles.isEmpty()) {
            return;
        }
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(midX, midY, 0);
        pose.scale((float) (1.0D / blocksPerPixel), (float) (1.0D / blocksPerPixel), 1.0F);
        pose.translate(-centerX, -centerZ, 0);
        for (RVP_TacticalMapCache.TilePos tile : tiles) {
            ResourceLocation texture = RVP_TacticalMapCache.getTileTexture(tile);
            if (texture == null) {
                continue;
            }
            // 瓦片左上角世界坐标 = (rx, rz) << 8（TILE_SIZE_BITS=8，256 格/瓦片）。
            gg.blit(texture, tile.rx() << 8, tile.rz() << 8, 256, 256, 0, 0, 256, 256, 256, 256);
        }
        pose.popPose();
    }

    /**
     * 载具 / 步兵 / 生物 / 飞行弹药：筛选条件与战术地图、炮兵地图完全同源；
     * icon 选择与染色/旋转同全屏地图（drawVehicleMarker / drawContactMarker / drawMissileMarker 的 40% 版）。
     */
    private static void drawEntities(GuiGraphics gg, Minecraft mc, LocalPlayer player, Context context,
                                     Vec3 center, int midX, int midY, double blocksPerPixel, float partialTick,
                                     int contentX0, int contentY0, int contentX1, int contentY1) {
        Set<Integer> radarVisibleIds = collectRadarVisibleIds(context.operatorUnit());
        Set<Integer> drawnIds = new HashSet<>();
        ResourceLocation dimension = mc.level.dimension().location();
        for (Entity rawEntity : mc.level.entitiesForRendering()) {
            Entity entity = RVP_RadarContactHelper.resolveRadarIdentity(rawEntity);
            if (entity == null || !drawnIds.add(entity.getId()) || entity == player) {
                continue;
            }
            int x = worldToScreen(entity.getX(), center.x, midX, blocksPerPixel);
            int y = worldToScreen(entity.getZ(), center.z, midY, blocksPerPixel);
            if (x < contentX0 || y < contentY0 || x >= contentX1 || y >= contentY1) {
                continue;
            }
            if (entity instanceof RVP_BaseBullet bullet) {
                int color = RVP_TacticalMapScreen.tacticalAmmoMarkerColor(player, context.vehicle(), bullet,
                        radarVisibleIds);
                if (color == 0) {
                    continue;
                }
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.resolveMissileIcon(bullet),
                        x, y, AMMO_ICON_SIZE, color, true,
                        RVP_TacticalMapScreen.mapIconYaw(markerYaw(entity, partialTick)), true);
                continue;
            }
            int color = RVP_TacticalMapScreen.relationColorForEntity(player, entity);
            if (!RVP_TacticalMapScreen.isRealtimeVisibleRelation(color)
                    && !radarVisibleIds.contains(entity.getId())
                    && !RVP_ClientTacticalRevealState.isVisible(dimension, entity.getId())) {
                continue;
            }
            if (color == 0) {
                continue;
            }
            if (entity instanceof AbstractVehicle vehicle) {
                // 载具（含本车）：同全屏地图按载具类型选 icon 并随航向旋转。
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.resolveVehicleIcon(vehicle),
                        x, y, VEHICLE_ICON_SIZE, color, true,
                        RVP_TacticalMapScreen.mapIconYaw(vehicle.getViewYRot(partialTick)), true);
            } else if (RVP_RadarContactHelper.usesMonsterIcon(entity)) {
                // 怪物/中性生物：全屏地图同款固定白色 icon，不染色。
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.MONSTER_ICON,
                        x, y, CONTACT_ICON_SIZE, 0xFFFFFFFF, false, 0.0F, false);
            } else if (RVP_RadarContactHelper.usesNeutralIcon(entity)) {
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.NORMAL_ICON,
                        x, y, CONTACT_ICON_SIZE, 0xFFFFFFFF, false, 0.0F, false);
            } else {
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.resolveFallbackEntityIcon(entity),
                        x, y, CONTACT_ICON_SIZE, color, true,
                        RVP_TacticalMapScreen.mapIconYaw(entity.getViewYRot(partialTick)), true);
            }
        }
    }

    /** 与全屏地图 resolveMarkerYaw 同款：HBM 巡飞弹按速度方向，其余按视角航向。 */
    private static float markerYaw(Entity entity, float partialTick) {
        if (RVP_RadarContactHelper.isHbmMissile(entity)) {
            Vec3 velocity = entity.getDeltaMovement();
            double horizontalSqr = velocity.x * velocity.x + velocity.z * velocity.z;
            if (horizontalSqr > 1.0E-6D) {
                return (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D);
            }
        }
        return entity.getViewYRot(partialTick);
    }

    /** GPS 航点 + TGT 标记：全屏地图同款 gps.png 图标（GPS 黄，12→4.8px），无文字标签。 */
    private static void drawGpsAndMarks(GuiGraphics gg, LocalPlayer player, Vec3 center, int midX, int midY,
                                        double blocksPerPixel, int contentX0, int contentY0,
                                        int contentX1, int contentY1) {
        ResourceLocation dimension = player.level().dimension().location();
        List<RVP_ClientGPSState.Point> points = RVP_ClientGPSState.getPoints();
        if (!points.isEmpty()) {
            for (RVP_ClientGPSState.Point point : points) {
                if (!dimension.equals(point.dimension())) {
                    continue;
                }
                int x = worldToScreen(point.pos().x, center.x, midX, blocksPerPixel);
                int y = worldToScreen(point.pos().z, center.z, midY, blocksPerPixel);
                if (x < contentX0 || y < contentY0 || x >= contentX1 || y >= contentY1) {
                    continue;
                }
                RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.GPS_ICON,
                        x, y, GPS_ICON_SIZE, COLOR_GPS, false, 0.0F, true);
            }
        }
        for (S2CMarkedBlockSync.MarkedBlockEntry mark : RVP_ClientMarkedBlockState.getMarkedBlocks(dimension)) {
            int x = worldToScreen(mark.x(), center.x, midX, blocksPerPixel);
            int y = worldToScreen(mark.z(), center.z, midY, blocksPerPixel);
            if (x < contentX0 || y < contentY0 || x >= contentX1 || y >= contentY1) {
                continue;
            }
            RVP_MapIconTexture.blitIcon(gg, RVP_TacticalMapScreen.GPS_ICON,
                    x, y, GPS_ICON_SIZE, COLOR_GPS, false, 0.0F, true);
        }
    }

    /**
     * 落点圆环：轻量化地图专属语义（2026-10-26 用户定版，不依赖全屏地图的目标指定）——
     * 恒在面板中心（=落点）画 GPS 黄圆环套住白十字。
     */
    private static void drawImpactRing(GuiGraphics gg) {
        // drawCircle 的 thickness 是半径比例：2.4px 半径 × 0.08 只有 0.19px 环宽（亚像素不可见），
        // 按环宽 ~0.55px 反推比例，小半径下保底可见
        float ringThickness = Math.max(0.08F, 0.55F / TARGET_CIRCLE_RADIUS);
        GuiHelper.drawCircle(gg.pose(), 0.0F, 0.0F, TARGET_CIRCLE_RADIUS, COLOR_GPS, ringThickness, 0.0F, 0.0F);
    }

    /**
     * 把自身位置夹到内容区边缘：在本框内原样返回，出框时沿线缩到边框内侧。
     *
     * @return {endX, endY} 虚线终点（内容区边缘）
     */
    private static int[] clampToContent(int x, int y, int midX, int midY,
                                        int contentX0, int contentY0, int contentX1, int contentY1) {
        double dx = x - midX;
        double dy = y - midY;
        if (dx == 0 && dy == 0) {
            return new int[]{midX, midY};
        }
        double limitX = (contentX1 - contentX0) / 2.0D - 2;
        double limitY = (contentY1 - contentY0) / 2.0D - 2;
        double scale = 1.0D;
        if (Math.abs(dx) > limitX) {
            scale = Math.min(scale, limitX / Math.abs(dx));
        }
        if (Math.abs(dy) > limitY) {
            scale = Math.min(scale, limitY / Math.abs(dy));
        }
        return new int[]{(int) Math.round(midX + dx * scale), (int) Math.round(midY + dy * scale)};
    }

    private static void drawDashedLine(GuiGraphics gg, int x0, int y0, int x1, int y1, int color) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0D) {
            return;
        }
        for (double start = 0; start < length; start += DASH_ON_PX + DASH_GAP_PX) {
            double end = Math.min(length, start + DASH_ON_PX);
            drawLine(gg,
                    (float) (x0 + dx * start / length), (float) (y0 + dy * start / length),
                    (float) (x0 + dx * end / length), (float) (y0 + dy * end / length),
                    1, color);
        }
    }

    /** 任意角度线段：pose 旋转后按轴对齐 fill 落批，避免立即模式与攒批顺序互相覆盖。 */
    private static void drawLine(GuiGraphics gg, float x0, float y0, float x1, float y1, int width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5F) {
            return;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        int up = width / 2;
        int down = width - up;
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(x0, y0, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        gg.fill(0, -up, Math.round(length), down, color);
        pose.popPose();
    }

    private static Set<Integer> collectRadarVisibleIds(WeaponUnit operatorUnit) {
        Set<Integer> ids = new HashSet<>();
        for (RadarUnit radarUnit : operatorUnit.getRadarUnits()) {
            if (radarUnit == null || radarUnit.isUiHide() || !radarUnit.isOn()) {
                continue;
            }
            radarUnit.getDetectedEntities().forEach((entityId, detectedObject) -> {
                ids.add(entityId);
                if (detectedObject != null && detectedObject.entity != null) {
                    ids.add(detectedObject.entity.getId());
                }
            });
            Entity locked = radarUnit.getLockedEntity();
            if (locked != null) {
                ids.add(locked.getId());
            }
        }
        return ids;
    }

    private static int worldToScreen(double world, double center, int mid, double blocksPerPixel) {
        return (int) Math.round(mid + (world - center) / blocksPerPixel);
    }

    private static void resetState() {
        lastSolveTick = Long.MIN_VALUE;
        lastVehicleId = Integer.MIN_VALUE;
        lastWeaponId = null;
        smoothImpact = null;
    }
}
