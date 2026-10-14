package org.ywzj.rvp.client.cockpit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapHost;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;

/**
 * 座舱屏幕管理器（2026-10-14 座舱屏幕批，方案 B 定案，纯客户端）。
 *
 * <p>职责：①客户端注册三张屏纹理（RL {@code rvp:cockpit/{radar,rwr,map}}，供 display
 * 文件 {@code special_bone_effects.texture} 引用）——纹理为**离屏 RT 颜色纹理的包装**
 * （bind 直绑 RT 的 GL 纹理 id，零回读零上传，方案 B：RT 画完即所见，消灭
 * DynamicTexture 回灌管线的全部 GL 生命周期问题）；②ClientTick 降频驱动内容渲染
 * （雷达/RWR 每 tick=20Hz、地图每 4 tick=5Hz）；③仅本地玩家乘坐的载具绘制动态内容。</p>
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

    /** 屏幕纹理边长默认值（正方形）；可被 display 条目 {@code ywzj_rvp_screen_size} 覆盖。 */
    public static final int SIZE = 512;

    /**
     * 屏渲染距离（格）：只给距本地玩家该范围内的载具画屏内容（2026-10-15 用户定案，
     * 取代旧"必须坐在载具上"的钳制）。范围越小越省性能，越大越容易看到邻近载具的屏。
     */
    public static final double SCREEN_RENDER_RANGE = 10.0;

    /**
     * 屏内容兜底距离（格）：本体状态短暂失效（邻近载具开火/被锁定 ⇒
     * {@code LocalVehiclePlayer.vehicle} 与 {@code onVehicle()} 被清成 null，玩家却仍在机上）
     * 时，只要"上次解析到的载具"仍存活、仍在该范围内，就继续用它当屏内容源。
     *
     * <p>为什么必须缓存而不是退化成"最近载具"：实测 2026-10-15 02:29:58–02:30:09（220 tick）
     * 本体状态清空期间，屏内容源退化成了旁边那台没有雷达/没有告警接收器的车，三屏直接变成
     * 空画面（内容签名 探测=无雷达 / 无接收器）——用户观感就是"闭屏"。</p>
     */
    public static final double SCREEN_FALLBACK_RANGE = 64.0;

    /** display 条目自定义字段：屏纹理边长档位（256/512/1024，其它值归档到最近档）。 */
    private static final String SCREEN_SIZE_FIELD = "ywzj_rvp_screen_size";

    /** 屏纹理 RL → 实际边长（资源重载时从 display JSON 解析，未配置默认 {@link #SIZE}）。 */
    private static final Map<ResourceLocation, Integer> SCREEN_SIZES = new HashMap<>();

    /**
     * display 条目自定义字段：屏骨 UV 语义。
     * {@code "model"}（**缺省**，2026-10-15 用户定案）= 直接采信模型 UV——展面决定取景/缩放/占比；
     * {@code "fill"} = 旧行为（chunk UV 包围盒归一化，动态画面铺满整个屏骨平面）。
     */
    private static final String SCREEN_UV_MODE_FIELD = "ywzj_rvp_screen_uv_mode";

    /** 显式要求旧「铺满」语义的屏纹理 RL（集合为空 = 全部按模型 UV 采样）。 */
    private static final java.util.Set<ResourceLocation> SCREEN_FILL_UV = new java.util.HashSet<>();

    /**
     * @return 屏纹理 RL 的实际边长：display 条目 {@code ywzj_rvp_screen_size} 配置值，
     * 未配置回退 {@link #SIZE}。
     */
    public static int screenSize(ResourceLocation rl) {
        return SCREEN_SIZES.getOrDefault(rl, SIZE);
    }

    /**
     * 扫描 display/vehicle JSON，收集屏骨条目（texture=rvp:cockpit/*）的分辨率配置。
     * 同一 RL 被多个载具配置时取最大（共享纹理按最高清晰度需求分配）。
     * 档位限定 256/512/1024，其它值归档到最近档。
     */
    private static void parseScreenSizes(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        SCREEN_SIZES.clear();
        SCREEN_FILL_UV.clear();
        try {
            var resources = org.ywzj.vehicle.util.ResourceScanner.scanDirectory(resourceManager,
                    net.minecraft.resources.FileToIdConverter.json("display/vehicle"),
                    org.ywzj.vehicle.custom.serialize.GsonUtil.GSON);
            for (var entry : resources.entrySet()) {
                com.google.gson.JsonObject root;
                try {
                    root = net.minecraft.util.GsonHelper.convertToJsonObject(entry.getValue(), "rvp vehicle display");
                } catch (Exception ignored) {
                    continue;
                }
                if (!root.has("special_bone_effects") || !root.get("special_bone_effects").isJsonArray()) {
                    continue;
                }
                for (var effectElement : root.getAsJsonArray("special_bone_effects")) {
                    if (!effectElement.isJsonObject()) {
                        continue;
                    }
                    var effect = effectElement.getAsJsonObject();
                    String texture = net.minecraft.util.GsonHelper.getAsString(effect, "texture", "");
                    if (!isCockpitScreenTexture(
                            ResourceLocation.tryParse(texture))) {
                        continue;
                    }
                    ResourceLocation rl = ResourceLocation.tryParse(texture);
                    if (rl == null) {
                        continue;
                    }
                    // UV 语义与分辨率同源解析（必须先于 size 的 continue：条目可能只写 uv_mode）：
                    // 缺省 "model"（按模型 UV 采样）；显式 "fill" 才登记旧「铺满」语义。
                    String uvMode = net.minecraft.util.GsonHelper.getAsString(
                            effect, SCREEN_UV_MODE_FIELD, "model");
                    if ("fill".equalsIgnoreCase(uvMode)) {
                        SCREEN_FILL_UV.add(rl);
                    }
                    if (!effect.has(SCREEN_SIZE_FIELD)) {
                        continue;
                    }
                    int raw = net.minecraft.util.GsonHelper.getAsInt(effect, SCREEN_SIZE_FIELD, SIZE);
                    // 档位归档：≤256→256，≤512→512，其余→1024
                    int size = raw <= 256 ? 256 : raw <= 512 ? 512 : 1024;
                    SCREEN_SIZES.merge(rl, size, Math::max);
                }
            }
        } catch (Exception exception) {
            RVP_CockpitDiag.log("屏幕分辨率配置解析失败（按默认 " + SIZE + " 执行）: " + exception);
        }
    }

    /** RL → 离屏渲染目标（与纹理包装一一对应）。 */
    private static final Map<ResourceLocation, com.mojang.blaze3d.pipeline.TextureTarget> TARGETS = new HashMap<>();
    /** RL → 各屏上次重绘的 GameTick。 */
    private static final Map<ScreenKind, Long> LAST_RENDER = new HashMap<>();
    /** 纹理是否已注册（懒初始化，首次 ClientTick 时执行）。 */
    private static boolean registered;
    /** 透明填充是否已写入（避免每 tick 重复填充）。 */
    private static boolean idleFilled;

    /**
     * 上一次"权威解析"（乘坐/操作/原版 getVehicle）到的载具——仅由 {@link #resolveScreenVehicle()}
     * 的权威分支更新，不用"最近载具"兜底结果污染它。用于本体状态短暂清空时继续供给屏内容，
     * 避免退化成旁边那台没有雷达/告警接收器的车（详见 {@link #SCREEN_FALLBACK_RANGE}）。
     */
    private static AbstractVehicle lastScreenVehicle;

    /** 诊断驱动节流（diag 开启时每 20 tick 一条）。 */
    private static long lastDiagTick = Long.MIN_VALUE;

    /** PNG dump 节流（每屏独立，每 2 秒一张）。 */
    private static final Map<ScreenKind, Long> LAST_DUMP = new HashMap<>();

    /** PNG dump 环形槽位（每屏 2 张轮换覆盖，避免写爆磁盘）。 */
    private static int dumpSlot;

    private RVP_CockpitScreenManager() {}

    /**
     * 懒注册：首次 ClientTick 时创建三块离屏 RT 并把"RT 颜色纹理包装"挂进 TextureManager。
     * 包装的 bind() 直绑 RT 的 GL 纹理 id——屏骨渲染每帧 setup 时绑定的就是 RT 实时画面
     * （方案 B：零回读零上传，无 DynamicTexture 生命周期问题）。初始先画一帧透明底，
     * 避免首帧屏骨渲染出未定义像素。
     */
    private static void ensureRegistered(Minecraft mc) {
        if (registered) {
            return;
        }
        for (ScreenKind kind : ScreenKind.values()) {
            ResourceLocation rl = rl(kind);
            int size = screenSize(rl);
            com.mojang.blaze3d.pipeline.TextureTarget target = RVP_CockpitCanvas.createTarget(size);
            // 初始画一帧全透明（清屏色 alpha=1 但 fillTransparent 语义由内容层负责——
            // 这里直接清屏即可，首帧内容绘制前为熄屏底色）。
            target.setClearColor(0.04f, 0.05f, 0.06f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.unbindWrite();
            AbstractTexture wrapper = new AbstractTexture() {
                @Override
                public void bind() {
                    // 强制真实绑定（2026-10-14 终极修复）：GlStateManager 的纹理绑定缓存会与
                    // 真实 GL 状态脱节（Embeddium/Oculus 等原生渲染路径绕过缓存改绑定），导致
                    // bind 被缓存命中跳过、采样落到错误纹理上（F3+T 重建缓存后才恢复的实锤根源）。
                    // 先原生 glBindTexture 强制真实绑定，再走 GlStateManager._bindTexture 把
                    // 缓存同步回来（重复真实调用一次无害，此后缓存与实际一致）。
                    int textureId = target.getColorTextureId();
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
                    com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureId);
                }

                @Override
                public void load(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
                    // 纹理内容在 GPU 侧由离屏 RT 实时绘制，无静态资源可加载（方案 B 空实现）。
                }
            };
            mc.getTextureManager().register(rl, wrapper);
            TARGETS.put(rl, target);
        }
        registered = true;
    }

    /**
     * 客户端资源重载（F3+T）会重建纹理环境——离屏 RT 虽不由 TextureManager 管理，但为保持
     * 状态纯净（并释放旧 FBO），此处重置全部离屏目标与注册标志，下个 ClientTick 重新创建。
     */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(
            net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) resourceManager -> {
                    // 先解析新一轮 display 的屏分辨率配置，再销毁重建离屏 RT（按新档位尺寸）。
                    parseScreenSizes(resourceManager);
                    for (com.mojang.blaze3d.pipeline.TextureTarget target : TARGETS.values()) {
                        try {
                            target.destroyBuffers();
                        } catch (Exception ignored) {
                            // 排查期诊断自身故障保持静默，不影响重载流程。
                        }
                    }
                    TARGETS.clear();
                    LAST_RENDER.clear();
                    idleFilled = false;
                    registered = false;
                    // 屏内容兜底缓存一并清空（实体引用跨重载不再可信）。
                    lastScreenVehicle = null;
                    RVP_CockpitDiag.log("资源重载：离屏 RT 与注册状态已重置（下个 tick 重新创建）");
                });
    }

    /**
     * 解析本地玩家当前乘坐/操作的载具：优先武器站所属载具（与雷达 HUD 同源），
     * 回退 lvp.vehicle。两处都取不到返回 null（此时屏回熄屏透明）。
     */
    private static AbstractVehicle resolveLocalVehicle() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp != null) {
            WeaponUnit weaponUnit = lvp.getWeaponUnit();
            if (weaponUnit != null) {
                // 调用本体武器站 API：武器站所属载具（雷达 HUD 数据链同源，驾驶状态必已赋值）。
                AbstractVehicle vehicle = weaponUnit.getVehicle();
                if (vehicle != null) {
                    return vehicle;
                }
            }
            if (lvp.vehicle != null) {
                return lvp.vehicle;
            }
        }
        // 原版兜底（2026-10-15）：本体状态在"被锁定/受击/切换乘员位"等情形下可能短暂把
        // lvp.vehicle 与武器站载具都清成 null（用户实测现象：敌方导弹来袭时三屏整块熄灭）。
        // 原版 getVehicle() 由实体 riding 关系直接维护、不参与本体的武器/锁定逻辑，
        // 因此作为最后一道兜底——本体状态若"撒谎"，屏不会再被误判为无载具而熄屏。
        net.minecraft.client.player.LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && localPlayer.getVehicle() instanceof AbstractVehicle ridden) {
            return ridden;
        }
        return null;
    }

    /**
     * 屏内容只画给"距本地玩家 {@link #SCREEN_RENDER_RANGE} 格内"的载具
     * （2026-10-15 用户定案：把旧钳制"必须坐在载具上"换成"距离"）。
     *
     * <p>动机：①开着载具改装/武器选择界面或暂停时，本体 {@code onVehicle()} 为 false，
     * 载具却就在身边——按距离判则屏继续刷新，不再整块熄灭；②远离的载具屏骨不再被画上
     * 本车 RT（此前每帧给场上所有带屏载具都画，直绘帧率虚高、观感串味）。</p>
     *
     * @return 是否在屏渲染距离内（用包围盒距离，坐在里面时为 0）
     */
    public static boolean isScreenRenderTarget(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        double range = SCREEN_RENDER_RANGE;
        return vehicle.getBoundingBox().distanceToSqr(player.position()) <= range * range;
    }

    /**
     * 解析"屏内容所属载具"，按下列优先级（2026-10-15 加固，针对"邻近载具开火 ⇒ 三屏变空"）：
     *
     * <ol>
     *   <li>乘坐/操作状态（武器站载具 → lvp.vehicle → 原版 {@code player.getVehicle()}）；</li>
     *   <li>**上次权威解析到的载具**：仍存活且距玩家 ≤ {@link #SCREEN_FALLBACK_RANGE} ⇒ 继续用它
     *       ——本体状态被清空时屏内容不降级（不会被旁边没雷达的车顶掉）；</li>
     *   <li>范围内的最近载具（改装界面/暂停/刚下车时屏不熄灭）。</li>
     * </ol>
     */
    private static AbstractVehicle resolveScreenVehicle() {
        AbstractVehicle ridden = resolveLocalVehicle();
        if (ridden != null) {
            lastScreenVehicle = ridden;
            return ridden;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return null;
        }
        // 兜底①：上次权威解析到的载具仍存活且在兜底范围内 ⇒ 继续用它。
        // 实测（2026-10-15 02:29:58–02:30:09，220 tick）：邻近载具开火时本体
        // LocalVehiclePlayer 把 vehicle/onVehicle 清成 null（玩家仍在机上），
        // 若此时退化成"最近载具"，屏内容会切到旁边那台无雷达/无告警接收器的车，
        // 内容签名变成 探测=无雷达 / 无接收器 ⇒ 三屏空画面（用户观感=闭屏）。
        AbstractVehicle remembered = lastScreenVehicle;
        if (remembered != null && !remembered.isRemoved() && remembered.isAlive()) {
            double rememberedSq = remembered.getBoundingBox().distanceToSqr(mc.player.position());
            if (rememberedSq <= SCREEN_FALLBACK_RANGE * SCREEN_FALLBACK_RANGE) {
                return remembered;
            }
        }
        // 兜底②：范围内的最近载具（典型：改装/武器界面开着、暂停、刚下车仍在车旁）。
        AbstractVehicle nearest = null;
        double bestSq = SCREEN_RENDER_RANGE * SCREEN_RENDER_RANGE;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle candidate) {
                double distanceSq = candidate.getBoundingBox().distanceToSqr(mc.player.position());
                if (distanceSq <= bestSq) {
                    bestSq = distanceSq;
                    nearest = candidate;
                }
            }
        }
        return nearest;
    }

    /** @return 屏型对应的注册 RL（display 文件 {@code special_bone_effects.texture} 填本值）。 */
    public static ResourceLocation rl(ScreenKind kind) {
        return ResourceLocation.fromNamespaceAndPath("rvp", "cockpit/" + kind.path);
    }

    /** @return 屏型对应的离屏渲染目标（世界空间直绘采样其颜色纹理；未注册返回 null）。 */
    public static com.mojang.blaze3d.pipeline.TextureTarget getScreenTarget(ResourceLocation rl) {
        return TARGETS.get(rl);
    }

    /**
     * @return 该纹理是否为座舱屏动态纹理（渲染侧据此为屏骨选择专用渲染路径）。
     */
    public static boolean isCockpitScreenTexture(ResourceLocation texture) {
        return texture != null && "rvp".equals(texture.getNamespace())
                && texture.getPath().startsWith("cockpit/");
    }

    /**
     * @return 该屏纹理是否使用旧「铺满」UV 语义（chunk UV 包围盒归一化）。
     * 缺省 false = **按模型 UV 采样**（2026-10-15 用户定案：一切按模型 UV，不自动铺）。
     */
    public static boolean isFillUvMode(ResourceLocation texture) {
        return texture != null && SCREEN_FILL_UV.contains(texture);
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
        // 排查期诊断走专有文件 RVP_CockpitDiag（开启时每 20 tick=1 秒写一组心跳，不进 latest.log）。
        if (RVP_CockpitDiag.isEnabled()) {
            long nowDiag = mc.level.getGameTime();
            // 哨兵首帧必须放行：lastDiagTick 初始为 Long.MIN_VALUE 时，nowDiag - lastDiagTick
            // 会因 long 溢出翻成大负数、节流判断恒 false，诊断一行都写不出（与
            // RVP_DistanceBoneHider.debugTick 同坑同修法，2026-10-14 屏不显示排查定案）。
            // trace 模式（/cockpitdiag trace <tick>）：窗口内改为逐 tick 输出，抓"戛然而止"那一瞬。
            if (lastDiagTick == Long.MIN_VALUE || RVP_CockpitDiag.isTracing()
                    || nowDiag - lastDiagTick >= 20) {
                lastDiagTick = nowDiag;
                LocalVehiclePlayer lvpDiag = LocalVehiclePlayer.instance;
                AbstractVehicle vehicleDiag = resolveLocalVehicle();
                // 屏内容源（含兜底）：与"解析载具"可能不同——本会话实测本体状态清空期间，
                // 屏实际用的是"最近载具"兜底，必须单独打出来才能解释屏上内容为何变成空画面。
                AbstractVehicle screenSrcDiag = resolveScreenVehicle();
                RVP_CockpitDiag.log("状态 lvp=" + (lvpDiag != null)
                        + " onVehicle=" + (lvpDiag != null && lvpDiag.onVehicle())
                        + " lvpVehicle=" + (lvpDiag != null ? lvpDiag.vehicle : null)
                        + " 武器站载具=" + (lvpDiag != null && lvpDiag.getWeaponUnit() != null
                                ? lvpDiag.getWeaponUnit().getVehicle() : null)
                        + " 解析载具=" + (vehicleDiag != null ? vehicleDiag.getVehicleId() : null)
                        // 原版乘坐实体（2026-10-15 加）：判定"本体状态是否撒谎"的关键字段——
                        // 有值 = 玩家确实骑在载具上（本体却报 null ⇒ 本体状态问题）；
                        // null = 玩家真的没在载具上（熄屏属设计行为）。
                        + " 原版乘坐=" + (mc.player != null ? mc.player.getVehicle() : null)
                        + " 屏内容源=" + (screenSrcDiag != null ? screenSrcDiag.getVehicleId() : null)
                        + " registered=" + registered);
                // 分层心跳：RT 侧计数（draws/md5/内容签名）+ 显示侧计数（骨直绘帧/秒）
                // + GL 状态快照 + 导弹/告警相关性——四组对齐后可判定"停在哪一层"。
                RVP_CockpitDiag.heartbeat(nowDiag, vehicleDiag);
                RVP_CockpitDiag.tickTrace();
            }
        }
        // 载具解析走武器站（与雷达 HUD 同源，不依赖 lvp.vehicle——直10 实测该字段
        // 在驾驶状态未跟上，导致屏骨被误判非本车而不渲染/不更新）；
        // 解析不到时回退距离内最近载具（2026-10-15：改装/武器界面、暂停期间屏不再熄灭）。
        AbstractVehicle vehicle = resolveScreenVehicle();
        long now = mc.level.getGameTime();
        if (vehicle == null) {
            // 范围内无载具：三块屏清为熄屏底（RT 清屏 alpha=0 → 屏骨整块不渲染；仅一次，避免重复清屏）。
            if (!idleFilled) {
                for (ScreenKind kind : ScreenKind.values()) {
                    com.mojang.blaze3d.pipeline.TextureTarget target = TARGETS.get(rl(kind));
                    if (target != null) {
                        target.setClearColor(0.04f, 0.05f, 0.06f, 0f);
                        target.clear(Minecraft.ON_OSX);
                    }
                }
                idleFilled = true;
                LAST_RENDER.clear();
            }
            return;
        }
        idleFilled = false;
        for (ScreenKind kind : ScreenKind.values()) {
            long last = LAST_RENDER.getOrDefault(kind, Long.MIN_VALUE);
            // 同 lastDiagTick 溢出坑：哨兵帧必须放行重绘，否则首轮即 continue 且 LAST_RENDER
            // 永不更新——三张屏一行内容都画不出（2026-10-14 屏骨"消失"根因之一）。
            if (last != Long.MIN_VALUE && now - last < kind.intervalTicks) {
                continue;
            }
            LAST_RENDER.put(kind, now);
            ResourceLocation rl = rl(kind);
            com.mojang.blaze3d.pipeline.TextureTarget target = TARGETS.get(rl);
            // 记录 painter 执行（RT 侧计数）+ 数据层内容签名——冻结时与 md5 对比可区分
            // "驱动没跑 / 跑了但没落进 RT / RT 正常但采样断了"三种情况。
            RVP_CockpitDiag.noteDraw(kind.path, now, describeContent(kind, vehicle));
            // 调用本项目离屏画布：渲染对应屏内容到 RT（屏骨纹理直采，无回灌）；
            // label=屏型，异常时归档到对应屏的 fail 计数与节流堆栈。
            RVP_CockpitCanvas.renderToTarget(target, kind.path, (graphics, width, height) ->
                    drawScreenContent(graphics, width, height, kind, vehicle));
            // 排查期诊断：环形 dump RT 当前帧为 PNG（每屏独立 2 秒一张、2 张轮换覆盖），
            // 供直接目检各屏映射纹理的真实画面（排查期三屏全开）。
            long nowDumpMs = System.currentTimeMillis();
            long lastDump = LAST_DUMP.getOrDefault(kind, Long.MIN_VALUE);
            if (RVP_CockpitDiag.isEnabled()
                    && (lastDump == Long.MIN_VALUE || nowDumpMs - lastDump >= 2000)) {
                LAST_DUMP.put(kind, nowDumpMs);
                dumpSlot = (dumpSlot + 1) % 2;
                try {
                    var dumpDir = RVP_CockpitDiag.DIAG_FILE.getParent().resolve("rvp_cockpit_dump");
                    java.nio.file.Files.createDirectories(dumpDir);
                    // RT 画面指纹：md5 连续不变 = 离屏画面冻结（与 draws 计数配合定位冻结层）。
                    var dumpFile = dumpDir.resolve(kind.path + "_" + dumpSlot + ".png");
                    RVP_CockpitCanvas.dumpTargetPNG(target, dumpFile.toFile());
                    RVP_CockpitDiag.noteDump(kind.path, RVP_CockpitDiag.md5Of(dumpFile));
                } catch (Exception dumpEx) {
                    RVP_CockpitDiag.log("PNG dump 失败: " + dumpEx);
                }
            }
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

    /**
     * 数据层内容签名（诊断用）：证明"数据还在变"，与 RT 画面指纹互补定位冻结层——
     * 签名在变而 md5 停滞 ⇒ 内容算出来了但没落进 RT；两者都停滞 ⇒ 驱动/数据源断了。
     */
    private static String describeContent(ScreenKind kind, AbstractVehicle vehicle) {
        try {
            return switch (kind) {
                case RADAR -> {
                    var unit = currentWeaponUnit();
                    var radar = unit == null ? null : unit.getMainRadarUnit();
                    var detected = radar == null ? null : radar.getDetectedEntities();
                    yield "探测=" + (detected == null ? "无雷达" : String.valueOf(detected.size()));
                }
                case RWR -> {
                    var receiver = vehicle.warningReceiver;
                    if (receiver == null) {
                        yield "无接收器";
                    }
                    int msl = 0;
                    int lock = 0;
                    int search = 0;
                    for (var target : receiver.targets.values()) {
                        var type = target.warnType();
                        if (type == org.ywzj.vehicle.vehicle.pojo.WarnType.MISSILE_LAUNCH) {
                            msl++;
                        } else if (type == org.ywzj.vehicle.vehicle.pojo.WarnType.RADAR_LOCK) {
                            lock++;
                        } else if (type == org.ywzj.vehicle.vehicle.pojo.WarnType.RADAR_SEARCH) {
                            search++;
                        }
                    }
                    yield "威胁=" + receiver.targets.size() + " MSL=" + msl
                            + " LOCK=" + lock + " SEARCH=" + search;
                }
                case MAP -> RVP_MapScreenRenderer.describe(vehicle);
            };
        } catch (Throwable throwable) {
            return "签名异常 " + throwable;
        }
    }

    /** @return 本地玩家当前武器站（雷达/RWR 数据入口的统一解包点；无乘载返回 null）。 */
    static WeaponUnit currentWeaponUnit() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        return lvp == null ? null : lvp.getWeaponUnit();
    }
}
