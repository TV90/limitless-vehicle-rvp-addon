package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapHost;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * 座舱屏幕管理器（2026-10-14 座舱屏幕批，纯客户端）。
 *
 * <p>职责：①客户端注册三张 512² DynamicTexture（RL {@code rvp:cockpit/{radar,rwr,map}}，
 * 供 display 文件 {@code special_bone_effects.texture} 引用——本体
 * {@code TextureStateShard} 每帧按 RL 实时 bind）；②ClientTick 降频驱动内容渲染
 * （雷达/RWR 每 tick=20Hz、地图每 4 tick=5Hz）并经 {@link RVP_CockpitCanvas} 回灌；
 * ③仅本地玩家乘坐的载具绘制动态内容，其它载具保持熄屏灰。</p>
 *
 * <p>已知 V1 限制（用户默认）：DynamicTexture 为全局共享 RL，其它玩家的同款配屏车
 * 在本客户端会显示本车屏内容；打样后再评估是否加守卫。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT)
public final class RVP_CockpitScreenManager {

    /** 屏幕类型：RL path 后缀即 display 引用与内容分发键。 */
    public enum ScreenKind {
        /** 雷达屏（探测符号+扫描线）。 */ RADAR("radar", 1),
        /** RWR 告警屏（威胁罗盘）。 */ RWR("rwr", 1),
        /** 战术地图屏（瓦片+图标，去按钮/去照射扇形）。 */ MAP("map", 4);

        /** RL path 后缀。 */ final String path;
        /** 重绘间隔（Tick）。 */ final int intervalTicks;

        ScreenKind(String path, int intervalTicks) {
            this.path = path;
            this.intervalTicks = intervalTicks;
        }
    }

    /** 屏幕纹理边长（正方形）。 */
    public static final int SIZE = 512;

    /** RL → 已注册 DynamicTexture。 */
    private static final Map<ResourceLocation, DynamicTexture> TEXTURES = new HashMap<>();
    /** RL → 离屏渲染目标（与 DynamicTexture 一一对应）。 */
    private static final Map<ResourceLocation, com.mojang.blaze3d.pipeline.TextureTarget> TARGETS = new HashMap<>();
    /** 各屏上次重绘的 GameTick。 */
    private static final Map<ScreenKind, Long> LAST_RENDER = new HashMap<>();
    /** 纹理是否已注册（懒初始化，首次 ClientTick 时执行）。 */
    private static boolean registered;
    /** 透明填充是否已写入（避免每 tick 重复填充）。 */
    private static boolean idleFilled;

    private RVP_CockpitScreenManager() {}

    /** 懒注册：首次 ClientTick 时把三张 DynamicTexture 挂进 TextureManager。 */
    private static void ensureRegistered(Minecraft mc) {
        if (registered) {
            return;
        }
        for (ScreenKind kind : ScreenKind.values()) {
            ResourceLocation rl = rl(kind);
            DynamicTexture texture = new DynamicTexture(SIZE, SIZE, false);
            mc.getTextureManager().register(rl, texture);
            TEXTURES.put(rl, texture);
            TARGETS.put(rl, RVP_CockpitCanvas.createTarget(SIZE));
        }
        registered = true;
    }

    /** @return 屏型对应的注册 RL（display 文件 {@code special_bone_effects.texture} 填本值）。 */
    public static ResourceLocation rl(ScreenKind kind) {
        return ResourceLocation.fromNamespaceAndPath("rvp", "cockpit/" + kind.path);
    }

    /**
     * @return 该纹理是否为座舱屏动态纹理（渲染侧据此实现"非本车屏骨整块不渲染"：
     * 动态纹理仅承载本车内容，非本车配屏车既不能串显也不回退建模原 UV——屏 UV 占比大，
     * 原贴图过花，用户定案"要么渲染本车内容、要么整块骨不渲染"）。
     */
    public static boolean isCockpitScreenTexture(ResourceLocation texture) {
        return texture != null && "rvp".equals(texture.getNamespace())
                && texture.getPath().startsWith("cockpit/");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        ensureRegistered(mc);
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        Entity vehicle = (lvp != null && lvp.onVehicle()) ? lvp.vehicle : null;
        long now = mc.level.getGameTime();
        if (vehicle == null) {
            // 非乘载状态：三张屏填全透明（视觉=屏骨不渲染；仅一次，避免每 tick 像素循环）。
            if (!idleFilled) {
                for (ScreenKind kind : ScreenKind.values()) {
                    RVP_CockpitCanvas.fillTransparent(TEXTURES.get(rl(kind)), SIZE);
                }
                idleFilled = true;
                LAST_RENDER.clear();
            }
            return;
        }
        idleFilled = false;
        for (ScreenKind kind : ScreenKind.values()) {
            long last = LAST_RENDER.getOrDefault(kind, Long.MIN_VALUE);
            if (now - last < kind.intervalTicks) {
                continue;
            }
            LAST_RENDER.put(kind, now);
            ResourceLocation rl = rl(kind);
            DynamicTexture output = TEXTURES.get(rl);
            // 调用本项目离屏画布：渲染对应屏内容并回灌（贴面由本体 renderSpecialBones 完成）。
            RVP_CockpitCanvas.renderAndUpload(TARGETS.get(rl), output, (graphics, width, height) ->
                    drawScreenContent(graphics, width, height, kind, vehicle));
        }
    }

    /** 按屏型分发内容渲染器（仅本地玩家载具被绘制）。 */
    private static void drawScreenContent(net.minecraft.client.gui.GuiGraphics graphics, int width, int height,
                                          ScreenKind kind, Entity vehicleEntity) {
        AbstractVehicle vehicle = (AbstractVehicle) vehicleEntity;
        switch (kind) {
            case RADAR -> RVP_RadarScreenRenderer.render(graphics, width, height, vehicle);
            case RWR -> RVP_RwrScreenRenderer.render(graphics, width, height, vehicle);
            case MAP -> RVP_MapScreenRenderer.render(graphics, width, height, vehicle);
        }
    }

    /** @return 本地玩家当前武器站（雷达/RWR 数据入口的统一解包点；无乘载返回 null）。 */
    static WeaponUnit currentWeaponUnit() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        return lvp == null ? null : lvp.getWeaponUnit();
    }
}
