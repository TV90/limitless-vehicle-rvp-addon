package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

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
    private static final int SELF_ICON_HALF = 5;
    /** 探测图标半边长（像素）。 */
    private static final int CONTACT_ICON_HALF = 3;

    private RVP_MapScreenRenderer() {}

    /** 渲染战术地图帧。 */
    public static void render(GuiGraphics graphics, int width, int height, AbstractVehicle vehicle) {
        graphics.fill(0, 0, width, height, 0xFF0A0C0E);
        // 内容区：屏面中心 70% 正方形（坐标映射全部基于该区域）。
        int contentSize = (int) (Math.min(width, height) * UV_DIAMETER_RATIO);
        int contentLeft = (width - contentSize) / 2;
        int contentTop = (height - contentSize) / 2;
        double centerX = vehicle.getX();
        double centerZ = vehicle.getZ();
        double halfWorld = contentSize / 2.0 * BLOCKS_PER_PIXEL;

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

        // 探测图标层（雷达探测点；与雷达屏同数据源，仅本车）
        WeaponUnit weaponUnit = RVP_CockpitScreenManager.currentWeaponUnit();
        RadarUnit radar = weaponUnit == null ? null : weaponUnit.getMainRadarUnit();
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
                    graphics.fill(x - CONTACT_ICON_HALF, y - CONTACT_ICON_HALF,
                            x + CONTACT_ICON_HALF, y + CONTACT_ICON_HALF, Color.GREEN);
                }
            }
        }

        // 本车图标（居中三角，屏面随本车平移故恒在中心）
        int cx = contentLeft + contentSize / 2;
        int cy = contentTop + contentSize / 2;
        graphics.fill(cx - SELF_ICON_HALF, cy - SELF_ICON_HALF,
                cx + SELF_ICON_HALF, cy + SELF_ICON_HALF, 0xFFFFFFFF);
        graphics.fill(cx - SELF_ICON_HALF + 1, cy - SELF_ICON_HALF + 1,
                cx + SELF_ICON_HALF - 1, cy + SELF_ICON_HALF - 1, 0xFF1A73E8);
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
