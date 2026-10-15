package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientMarkedBlockState;
import org.ywzj.rvp.network.S2CMarkedBlockSync;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;

/**
 * 战术地图屏内容渲染器（2026-10-14 座舱屏幕批）。
 *
 * <p>按用户定案**去功能化**：不画雷达照射扇形、不画按钮/侧栏——只有地形瓦片
 * （复用 {@link RVP_TacticalMapCache} 已有瓦片纹理，零额外生成开销）+ 本车/探测图标层。
 * 以本车为中心固定比例（{@link #BLOCKS_PER_PIXEL}），随本车移动平移。</p>
 */
public final class RVP_MapScreenRenderer {

    /** 比例：每屏像素对应的世界格数（内容区 502px × 2 = 1004 格视野）。 */
    private static final double BLOCKS_PER_PIXEL = 2.0;
    /**
     * UV 占比：地图屏尽可能铺满（用户定案）——0.98 仅留 1% 防边缘采样渗色，
     * 底色仅在渗色边缝可见。
     */
    private static final double UV_DIAMETER_RATIO = 0.98;
    /** 本车图标半边长（像素）。 */
    private static final float SELF_ICON_HALF = 5f;
    /** 探测图标半边长（像素）。 */
    private static final float CONTACT_ICON_HALF = 3f;

    private RVP_MapScreenRenderer() {}

    /** 渲染战术地图帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle,
                          float textScale, float elemScale) {
        graphics.fill(0, 0, width, height, 0xFF0A0C0E);
        // 内容区：屏面中心 70% 正方形（坐标映射全部基于该区域）。
        int contentSize = (int) (Math.min(width, height) * UV_DIAMETER_RATIO);
        int contentLeft = (width - contentSize) / 2;
        int contentTop = (height - contentSize) / 2;
        double centerX = vehicle.getX();
        double centerZ = vehicle.getZ();
        double halfWorld = contentSize / 2.0 * BLOCKS_PER_PIXEL;
// 元素缩放（display 条目 ywzj_rvp_element_scale，缺省 1.0=历史像素）。
        int contactHalf = (int) Math.max(1, Math.round(CONTACT_ICON_HALF * elemScale));
        int selfHalf = (int) Math.max(1, Math.round(SELF_ICON_HALF * elemScale));

        // 地形瓦片：把可见范围内每块 256 格瓦片按比例 blit 到屏面（1 瓦片像素 = 1 世界格）。
        List<RVP_TacticalMapCache.TilePos> tiles = RVP_TacticalMapCache.getVisibleTiles(
                centerX - halfWorld, centerZ - halfWorld, centerX + halfWorld, centerZ + halfWorld);
        for (RVP_TacticalMapCache.TilePos tile : tiles) {
            ResourceLocation texture = RVP_TacticalMapCache.getTileTexture(tile);
            if (texture == null) {
                continue;
            }
            // 调用本项目地图缓存：瓦片左上角世界坐标 = (rx, rz) << 8（256 格/瓦片）。
            // 调用本项目地图缓存：瓦片粒度 2^8=256 格（TILE_SIZE_BITS 同源值）。
            double tileWorldX = (double) (tile.rx() << 8);
            double tileWorldZ = (double) (tile.rz() << 8);
            int dx = worldToScreenX(tileWorldX, centerX, width, contentLeft, contentSize);
            int dy = worldToScreenY(tileWorldZ, centerZ, height, contentTop, contentSize);
            int tileSizePx = (int) Math.round(256 / BLOCKS_PER_PIXEL);
            // 调用本项目地图缓存：整瓦片缩放 blit（瓦片纹理 256²，一像素一世界格）。
            graphics.blit(texture, dx, dy, tileSizePx, tileSizePx, 0, 0, 256, 256, 256, 256);
        }

        // 探测图标层（雷达探测点；与雷达屏同数据源=载具自己的雷达，2026-10-15 起不读本体席位）
        RadarUnit radar = RVP_CockpitScreenManager.mainRadarUnit(vehicle);
        // 矢量层（探测点 + 弹药点都要画）需要屏幕坐标/实体/颜色：先收集，待 flush 后再画立即线段。
        java.util.List<double[]> vectorPts = new ArrayList<>();
        java.util.List<Entity> vectorEntities = new ArrayList<>();
        java.util.List<Integer> vectorColors = new ArrayList<>();
        if (radar != null) {
            HashMap<Integer, RadarUnit.DetectedObject> detected = radar.getDetectedEntities();
            if (detected != null) {
                for (RadarUnit.DetectedObject detectedObject : detected.values()) {
                    Entity entity = detectedObject.entity;
                    Vec3Like pos = resolvePos(detectedObject);
                    if (pos == null) {
                        continue;
                    }
                    int x = worldToScreenX(pos.x(), centerX, width, contentLeft, contentSize);
                    int y = worldToScreenY(pos.z(), centerZ, height, contentTop, contentSize);
                    if (x < contentLeft || y < contentTop || x >= contentLeft + contentSize
                            || y >= contentTop + contentSize) {
                        continue;
                    }
                    // 敌我识别：友军（同队驾驶）暗绿、其余亮绿——与雷达屏同款判定（2026-10-15 用户要求）
                    int contactColor = isFriendly(detectedObject.entity, vehicle) ? 0xFF3D9140 : Color.GREEN;
                    graphics.fill(x - contactHalf, y - contactHalf,
                            x + contactHalf, y + contactHalf, contactColor);
                    vectorPts.add(new double[]{x, y});
                    vectorEntities.add(detectedObject.entity);
                    vectorColors.add(contactColor);
                }
            }
        }

        // 弹药标记层（导弹/火箭弹/炸弹）：与战术地图**同源同条件**（RVP_TacticalMapScreen.tacticalAmmoMarkerColor
        // = 弹种∈{MISSILE,ROCKET,BOMB} + 关系可见/雷达探测/战术揭示），屏面小故用点（小方块）而非 icon；
        // 颜色=关系色：己方蓝 0xFF3AA7FF / 友军绿 0xFF64E08B / 敌对红 0xFFFF5B5B / 中立白（2026-10-15 用户要求）。
        Minecraft mcMap = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer mapPlayer = mcMap.player;
        AbstractVehicle mapPlayerVehicle = mapPlayer != null
                && mapPlayer.getVehicle() instanceof AbstractVehicle playerRide ? playerRide : null;
        // 雷达探测 id 集（战术地图对应 tacticalRadarVisibleIds；地图屏雷达数据同源）
        Set<Integer> radarVisibleIds = new HashSet<>();
        if (radar != null && radar.getDetectedEntities() != null) {
            radarVisibleIds.addAll(radar.getDetectedEntities().keySet());
        }
        if (radar != null && radar.getLockedEntity() != null) {
            radarVisibleIds.add(radar.getLockedEntity().getId());
        }
        int ordnanceHalf = (int) Math.max(1, Math.round(2.5F * elemScale));
        // entitiesForRendering() 是 ClientLevel 的方法（Level 无此方法），故取客户端世界引用并判空。
        net.minecraft.client.multiplayer.ClientLevel mapLevel = mcMap.level;
        if (mapLevel != null) {
            for (Entity entity : mapLevel.entitiesForRendering()) {
                if (!(entity instanceof RVP_BaseBullet bullet)) {
                    continue;
                }
                int ordColor = RVP_TacticalMapScreen.tacticalAmmoMarkerColor(
                        mapPlayer, mapPlayerVehicle, bullet, radarVisibleIds);
                if (ordColor == 0) {
                    continue;
                }
                int ox = worldToScreenX(bullet.getX(), centerX, width, contentLeft, contentSize);
                int oy = worldToScreenY(bullet.getZ(), centerZ, height, contentTop, contentSize);
                if (ox < contentLeft || oy < contentTop || ox >= contentLeft + contentSize
                        || oy >= contentTop + contentSize) {
                    continue;
                }
                graphics.fill(ox - ordnanceHalf, oy - ordnanceHalf,
                        ox + ordnanceHalf, oy + ordnanceHalf, ordColor);
                // 弹药点同样画速度矢量（用户要求"都显示"），颜色=关系色
                vectorPts.add(new double[]{ox, oy});
                vectorEntities.add(bullet);
                vectorColors.add(ordColor);
            }
        }

        // 本车图标（居中三角，屏面随本车平移故恒在中心）
        int cx = contentLeft + contentSize / 2;
        int cy = contentTop + contentSize / 2;
        graphics.fill(cx - selfHalf, cy - selfHalf,
                cx + selfHalf, cy + selfHalf, 0xFFFFFFFF);
        graphics.fill(cx - selfHalf + 1, cy - selfHalf + 1,
                cx + selfHalf - 1, cy + selfHalf - 1, 0xFF1A73E8);

        // GPS 航点 + 吊舱标记（TGT）：与战术地图同源——RVP_ClientGPSState.getPoints()（点集，按当前维度过滤，
        // 活动航点=getNextIndex 所指，更亮更大）/ RVP_ClientMarkedBlockState.getMarkedBlocks(维度)（吊舱标记）。
        // 屏面小故用点（方块）而非 icon；色=战术地图 GPS_ICON_COLOR 亮黄 0xFFFFF08A（非活动点用暗黄区分）。
        if (mapPlayer != null) {
            ResourceLocation mapDim = mapPlayer.level().dimension().location();
            java.util.List<RVP_ClientGPSState.Point> gpsPoints = RVP_ClientGPSState.getPoints();
            if (!gpsPoints.isEmpty()) {
                int activeIdx = Math.max(0, Math.min(RVP_ClientGPSState.getPointCount() - 1,
                        RVP_ClientGPSState.getNextIndex()));
                int gpsHalf = (int) Math.max(1, Math.round(2.5F * elemScale));
                for (int i = 0; i < gpsPoints.size(); i++) {
                    RVP_ClientGPSState.Point point = gpsPoints.get(i);
                    if (!mapDim.equals(point.dimension())) {
                        continue;
                    }
                    int gx = worldToScreenX(point.pos().x, centerX, width, contentLeft, contentSize);
                    int gy = worldToScreenY(point.pos().z, centerZ, height, contentTop, contentSize);
                    if (gx < contentLeft || gy < contentTop || gx >= contentLeft + contentSize
                            || gy >= contentTop + contentSize) {
                        continue;
                    }
                    boolean active = i == activeIdx;
                    int half = active ? gpsHalf + 1 : gpsHalf;
                    int gpsColor = active ? 0xFFFFF08A : 0xFF8F8440;
                    graphics.fill(gx - half, gy - half, gx + half, gy + half, gpsColor);
                }
            }
            for (S2CMarkedBlockSync.MarkedBlockEntry mark
                    : RVP_ClientMarkedBlockState.getMarkedBlocks(mapDim)) {
                int tx = worldToScreenX(mark.x(), centerX, width, contentLeft, contentSize);
                int ty = worldToScreenY(mark.z(), centerZ, height, contentTop, contentSize);
                if (tx < contentLeft || ty < contentTop || tx >= contentLeft + contentSize
                        || ty >= contentTop + contentSize) {
                    continue;
                }
                int markHalf = (int) Math.max(1, Math.round(3.5F * elemScale));
                graphics.fill(tx - markHalf, ty - markHalf, tx + markHalf, ty + markHalf, 0xFFFFF08A);
            }
        }

        // 先落盘攒批内容（瓦片 blit + 图标 fill）：GuiGraphics 攒批要到 flush 才提交，
        // 否则下面立即模式的速度矢量会被随后 flush 的瓦片/图标整片盖掉（雷达屏同坑）。
        graphics.flush();
        // 速度矢量：地图为世界对齐（+X→右、+Z→下），故直接按世界速度分量映射，无需随航向旋转。
        var pose = graphics.pose();
        for (int i = 0; i < vectorPts.size(); i++) {
            Entity entity = vectorEntities.get(i);
            if (entity == null) {
                continue;
            }
            Vec3 velocity = entity.getDeltaMovement();
            double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (speed < 1.0E-3) {
                continue;
            }
            float length = 14.0F * elemScale;
            float sx = (float) vectorPts.get(i)[0];
            float sy = (float) vectorPts.get(i)[1];
            RVP_CockpitCanvas.drawLineImmediate(pose, sx, sy,
                    sx + (float) (velocity.x / speed) * length,
                    sy + (float) (velocity.z / speed) * length,
                    3.0F * elemScale, vectorColors.get(i));
        }
    }

    /** 简易 IFF：同队为友军（暗绿），其余按敌对亮绿（与雷达屏同款判定）。 */
    private static boolean isFriendly(Entity entity, AbstractVehicle observer) {
        var team = observer.getTeam();
        if (team == null || entity == null) {
            return false;
        }
        Entity driver = entity instanceof AbstractVehicle vehicle ? vehicle.getDriver() : entity;
        return driver != null && team.isAlliedTo(driver.getTeam());
    }

    /** 世界 X → 内容区屏像素（本车居中于 70% 内容区中心）。 */
    private static int worldToScreenX(double worldX, double centerX, int width,
                                      int contentLeft, int contentSize) {
        return (int) Math.round(contentLeft + contentSize / 2.0
                + (worldX - centerX) / BLOCKS_PER_PIXEL);
    }

    /** 世界 Z → 内容区屏像素（本车居中）。 */
    private static int worldToScreenY(double worldZ, double centerZ, int height,
                                      int contentTop, int contentSize) {
        return (int) Math.round(contentTop + contentSize / 2.0
                + (worldZ - centerZ) / BLOCKS_PER_PIXEL);
    }

    /** @return 探测位置（优先快照位置，回退实体实时位置）；两者皆无返回 null。 */
    private static Vec3Like resolvePos(RadarUnit.DetectedObject detectedObject) {
        if (detectedObject.detectedPosition != null) {
            return new Vec3Like(detectedObject.detectedPosition.x,
                    detectedObject.detectedPosition.y, detectedObject.detectedPosition.z);
        }
        Entity entity = detectedObject.entity;
        return entity == null ? null : new Vec3Like(entity.getX(), entity.getY(), entity.getZ());
    }

    /** 轻量 xyz 只读载体（避免依赖 MC Vec3 的可变性）。 */
    private record Vec3Like(double x, double y, double z) {}

    /**
     * 诊断用内容签名（2026-10-15 用户反馈"日志不到位"后补）：把"地图是否**应该**在变"量化。
     *
     * <p>判据：签名在变而 RT 画面指纹（md5）不变 ⇒ 画面真被冻住（管线问题）；
     * 签名本身不变 ⇒ 本来就无内容可动（例如驻车 + 无探测）。此前签名只写常量"地图"，
     * 在 z10 驻车场景下无法与"冻结"区分。</p>
     *
     * <p><b>2026-10-15 误报修正</b>：签名必须报**取整后的像素偏移**而非世界坐标——瓦片按整数
     * 像素 blit 且 {@code BLOCKS_PER_PIXEL=2}，本车每移动 2 格才差 1 像素。实测 8 秒内车移
     * 4.5 格（≈2px）时 md5 未变属**量化正常**，用世界坐标签名会误判成"冻结"。</p>
     */
    public static String describe(AbstractVehicle vehicle) {
        try {
            double halfWorld = 512 * UV_DIAMETER_RATIO / 2.0 * BLOCKS_PER_PIXEL;
            double cx = vehicle.getX();
            double cz = vehicle.getZ();
            List<RVP_TacticalMapCache.TilePos> tiles = RVP_TacticalMapCache.getVisibleTiles(
                    cx - halfWorld, cz - halfWorld, cx + halfWorld, cz + halfWorld);
            return String.format(java.util.Locale.ROOT, "地图 像素偏移=%d,%d 瓦片=%d",
                    Math.round(-cx / BLOCKS_PER_PIXEL), Math.round(-cz / BLOCKS_PER_PIXEL),
                    tiles.size());
        } catch (Throwable throwable) {
            return "地图 签名异常:" + throwable;
        }
    }
}
