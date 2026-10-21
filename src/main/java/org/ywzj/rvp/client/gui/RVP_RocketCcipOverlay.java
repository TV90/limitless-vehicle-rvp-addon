package org.ywzj.rvp.client.gui;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.renderer.texture.TextureManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.ext.VehicleRocketWeaponDataExt;
import org.ywzj.rvp.client.state.RVP_RocketCcipScreenState;
import org.ywzj.rvp.weapon.RVP_RocketBallistics;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RocketCcipOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("rvp", "textures/ui/ccip.png");
    private static final ResourceLocation REGISTERED_TEXTURE = RVP_MOD.modLocation("dynamic/ccip");
    private static final int CCIP_SIZE = 32;
    private static final int CCIP_TEXTURE_SIZE = 128;
    private static final boolean ywzj_rvp$debugForceCenter = false;
    private static final boolean ywzj_rvp$enableForgeOverlayEvent = false;
    private static long ywzj_rvp$lastTextureCheckMs;
    private static long ywzj_rvp$loadedTextureMtime = Long.MIN_VALUE;
    private static DynamicTexture ywzj_rvp$dynamicTexture;
    private static ResourceLocation ywzj_rvp$activeTexture = FALLBACK_TEXTURE;
    private static boolean ywzj_rvp$hasTexture;
    private static String ywzj_rvp$lastTextureDebugState = "";

    /**
     * 当前贴图的实际像素尺寸（载入时读取，绘制时按尺寸整体缩放）。
     *
     * <p>历史 bug（2026-10-22）：绘制固定用 {@code blit(tex, x, y, 0, 0, 32, 32, 128, 128)}，
     * 采样源区恒为贴图左上角 0~32 像素。包内 ccip.png 是 128×128、图案只落在
     * x44~83 / y22~95，于是每次都采样到纯透明区域 ⇒ 门控/贴图/锚点全部正常、
     * 绘制调用也跑了上千次，屏幕上却什么都没有。改为按真实尺寸整图采样 + pose 缩放。</p>
     */
    private static int ywzj_rvp$texW = CCIP_TEXTURE_SIZE;
    private static int ywzj_rvp$texH = CCIP_TEXTURE_SIZE;

    private RVP_RocketCcipOverlay() {}

    private record ActiveRocketContext(AbstractVehicle vehicle, WeaponUnit weaponUnit, AbstractVehicleWeapon<?> weapon) {}

    public static boolean isBallisticRocketWeapon(AbstractVehicleWeapon<?> weapon, WeaponUnit activeWeaponUnit) {
        if (weapon == null || activeWeaponUnit == null) {
            return false;
        }
        if (RVP_WeaponSensorHelper.effectiveSensorType(activeWeaponUnit) != WeaponUnitData.FireControlSensorType.CCIP) {
            return false;
        }
        if (weapon instanceof VehicleRocket rocket) {
            return rocket.getData() instanceof VehicleRocketWeaponDataExt ext && ext.ywzj_rvp$isBallisticEnabled();
        }
        if (weapon instanceof RVP_ProjectileWeapon rvpWeapon) {
            return rvpWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET;
        }
        return false;
    }

    private static ActiveRocketContext getActiveRocketContext() {
        if (LocalVehiclePlayer.instance == null) {
            return null;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return null;
        }
        if (weaponUnit.getCurrentWeapon().isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().get();
        if (!isBallisticRocketWeapon(weapon, weaponUnit)) {
            return null;
        }
        WeaponUnit rocketWeaponUnit = weapon.getWeaponUnit();
        if (rocketWeaponUnit == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return null;
        }
        return new ActiveRocketContext(vehicle, rocketWeaponUnit, weapon);
    }

    public static boolean isBallisticRocketActive() {
        return getActiveRocketContext() != null;
    }

    /**
     * 是否启用「用 RVP 自己的贴图替换本体准星」这套老方案。
     *
     * <p><b>2026-10-22 起关闭</b>：用户确认一直使用的就是<b>本体样式</b>的 CCIP 准星
     * （本体在配 {@code ccip} 传感器的站上把准星染绿、画在弹着预测点），
     * 包内 {@code assets/rvp/textures/ui/ccip.png} 是早年废弃素材（128×128 里只有
     * 一小块 T 形图案，且原绘制固定采样左上角 0~32 像素，取到的全是透明）。
     * 适配新本体时把这套替换接进了 {@code drawCrosshair} 新的分发路径，
     * 结果变成「隐藏本体准星 + 画一张看不见的图」⇒ 常规火箭弹 CCIP 准星凭空消失。
     * 因此改为只保留本体的准星，RVP 不再隐藏、不再替换。</p>
     *
     * <p>RVP 仍然负责「落点」：{@code LocalVehiclePlayerRocketAimMixin} 在 tickAim 尾部
     * 把 {@code weaponHitPos/weaponHitPosO} 覆盖成弹道预测点，本体据此把 CCIP 准星
     * 画在正确位置。若哪天要恢复贴图替换，把此开关改回 true 即可。</p>
     */
    private static final boolean ywzj_rvp$enableLegacyIconReplace = false;

    public static boolean isLegacyIconReplaceEnabled() {
        return ywzj_rvp$enableLegacyIconReplace;
    }

    public static boolean shouldReplaceReticle() {
        return ywzj_rvp$enableLegacyIconReplace && isBallisticRocketActive() && ensureTexture();
    }

    /** 本帧是否已在「本体准星锚点」处画过 CCIP 图标（mixin 重定向内绘制时置位）。 */
    private static boolean ywzj_rvp$drewAtAnchorThisFrame;

    /** 每帧渲染入口调用：清本帧锚点绘制标记。 */
    public static void beginRenderFrame() {
        ywzj_rvp$drewAtAnchorThisFrame = false;
    }

    /** 本帧是否已由锚点路径绘制（供 TAIL 兜底路径避免重复绘制）。 */
    public static boolean drewAtAnchorThisFrame() {
        return ywzj_rvp$drewAtAnchorThisFrame;
    }

    /**
     * 在本体准星锚点处直接绘制 CCIP 图标（2026-10-22）。
     *
     * <p>本体 HUD 重制后，准星绘制被移入 {@code VehicleAimAtOverlay.drawCrosshair} 分发，
     * 且锚点已经由本体摆到「弹着预测点」并做过 {@code translate/scale}——
     * 此时 pose 原点就是准星位置。原先依赖 {@code render} TAIL +
     * {@link #getCurrentScreenHitPos()} 重新投影的路径在新本体下拿不到有效点，
     * 表现为：本体准星被隐藏、RVP 图标又没画出来 ⇒ 常规火箭弹 CCIP 准星空白
     * （激光制导火箭弹是 {@code rvp:missile}，门控 false 不隐藏本体准星，所以它反而有准星）。
     * 因此把「隐藏本体准星」与「画 CCIP 图标」合并到同一处，锚点直接复用本体已算好的结果。</p>
     */
    public static void drawAtCrosshairAnchor(GuiGraphics guiGraphics, int size) {
        if (!ensureTexture()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float roll = 0.0f;
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            roll = Mth.lerp(mc.getFrameTime(), vehicle.zRotO, vehicle.getZRot());
        }
        ywzj_rvp$drewAtAnchorThisFrame = true;
        ywzj_rvp$dbgAnchorDraws++;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ywzj_rvp$blitIcon(guiGraphics, roll, size);
        // 视觉探针（/rvpdebug flags ccip on）：图元画的品红方框与贴图管线无关，必定可见。
        // 方框出现=锚点绘制确实跑到（问题在贴图 blit）；方框不出现=锚点路径没执行。
        if (RVP_DebugFlags.CCIP.isEnabled()) {
            int probe = 0xFFFF00FF;
            int half = size / 2;
            guiGraphics.fill(-half - 3, -half - 3, half + 3, -half - 2, probe);
            guiGraphics.fill(-half - 3, half + 2, half + 3, half + 3, probe);
            guiGraphics.fill(-half - 3, -half - 3, -half - 2, half + 3, probe);
            guiGraphics.fill(half + 2, -half - 3, half + 3, half + 3, probe);
        }
        RenderSystem.disableBlend();
    }

    /**
     * 按贴图真实尺寸整图绘制 CCIP 图标，再用 pose 缩放到目标尺寸。
     *
     * <p>不再假设图案铺满整张贴图，也不再硬编码 128，因此换成任意尺寸的 ccip.png 都能正确显示。
     * 调用方负责把 pose 平移到锚点，本方法只叠加横滚旋转与缩放。</p>
     */
    private static void ywzj_rvp$blitIcon(GuiGraphics guiGraphics, float roll, int size) {
        int texW = Math.max(1, ywzj_rvp$texW);
        int texH = Math.max(1, ywzj_rvp$texH);
        float scale = size / (float) Math.max(texW, texH);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(roll));
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.blit(ywzj_rvp$activeTexture, -texW / 2, -texH / 2,
                0, 0, texW, texH, texW, texH);
        guiGraphics.pose().popPose();
    }

    /** 记录贴图真实像素尺寸，供 {@link #ywzj_rvp$blitIcon} 整图采样。 */
    private static void ywzj_rvp$applyImageMetrics(NativeImage image) {
        ywzj_rvp$texW = Math.max(1, image.getWidth());
        ywzj_rvp$texH = Math.max(1, image.getHeight());
    }

    // ===================== /rvpdebug ccip 诊断（2026-10-22） =====================
    // 目的：常规火箭弹 CCIP 图标不显示时，一次命令定位断点在哪一段：
    // 门控（传感器/kind）→ 贴图 → 锚点 → 渲染期调用计数 → TAIL 兜底分支。

    public static long ywzj_rvp$dbgRenderCalls;
    public static long ywzj_rvp$dbgCrosshairCalls;
    public static long ywzj_rvp$dbgReplaceTrue;
    public static long ywzj_rvp$dbgReplaceFalse;
    public static long ywzj_rvp$dbgAnchorDraws;
    public static long ywzj_rvp$dbgTailDraws;
    public static String ywzj_rvp$dbgLastStyle = "-";
    public static String ywzj_rvp$dbgTailState = "未进入";
    public static Vec3 ywzj_rvp$dbgLastHit;
    public static Vec3 ywzj_rvp$dbgLastScreen;

    public static void dbgNoteRender() {
        ywzj_rvp$dbgRenderCalls++;
    }

    public static void dbgNoteCrosshair(String styleName, boolean replace) {
        ywzj_rvp$dbgCrosshairCalls++;
        ywzj_rvp$dbgLastStyle = styleName == null ? "null" : styleName;
        if (replace) {
            ywzj_rvp$dbgReplaceTrue++;
        } else {
            ywzj_rvp$dbgReplaceFalse++;
        }
    }

    public static void dbgNoteTail(String state) {
        ywzj_rvp$dbgTailState = state;
    }

    public static void dbgResetCounters() {
        ywzj_rvp$dbgRenderCalls = 0;
        ywzj_rvp$dbgCrosshairCalls = 0;
        ywzj_rvp$dbgReplaceTrue = 0;
        ywzj_rvp$dbgReplaceFalse = 0;
        ywzj_rvp$dbgAnchorDraws = 0;
        ywzj_rvp$dbgTailDraws = 0;
        ywzj_rvp$dbgLastStyle = "-";
        ywzj_rvp$dbgTailState = "未进入";
        ywzj_rvp$dbgLastHit = null;
        ywzj_rvp$dbgLastScreen = null;
    }

    /** 供命令输出：外部 ccip.png 是否被找到（找不到则为 null，走资源包回退）。 */
    public static String dbgTexturePath() {
        try {
            Path p = resolvePackTexturePath();
            return p == null ? "null(走资源包回退)" : p.toString();
        } catch (Throwable t) {
            return "异常:" + t.getClass().getSimpleName();
        }
    }

    /** 一次性诊断快照（/rvpdebug ccip 写入 logs/ccipdebug.log）。 */
    public static String dbgSnapshot() {
        StringBuilder sb = new StringBuilder();
        Minecraft mc = Minecraft.getInstance();
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        sb.append("=== RVP Rocket CCIP dump ===\n");
        sb.append("viewType=").append(lvp == null ? "null" : lvp.viewType)
                .append(" onVehicle=").append(lvp != null && lvp.onVehicle())
                .append(" hideGui=").append(mc.options.hideGui)
                .append('\n');
        WeaponUnit unit = lvp == null ? null : lvp.getWeaponUnit();
        if (unit == null) {
            sb.append("unit=null（当前不在武器站）\n");
            return sb.toString();
        }
        sb.append("unit=").append(unit.getId()).append(" index=").append(unit.getIndex())
                .append(" currentWeaponIndex=").append(unit.getCurrentWeaponIndex())
                .append(" crosshairStyle=").append(unit.crosshairStyle)
                .append('\n');
        AbstractVehicleWeapon<?> current = unit.getCurrentWeapon().orElse(null);
        sb.append("currentWeapon=").append(current == null ? "null" : current.getClass().getSimpleName())
                .append('\n');
        AbstractVehicleWeapon<?> primary = org.ywzj.rvp.util.RVP_WeaponResolveHelper.currentPrimary(unit);
        if (primary instanceof RVP_WeaponBase rvp) {
            sb.append("  primaryRvp=").append(rvp.getData().getWeaponId())
                    .append(" kind=").append(rvp.getData().getWeaponKind())
                    .append(" sensorMode='").append(rvp.getData().getFireControlSensorMode()).append('\'')
                    .append(" sensorOverride=").append(rvp.getData().getFireControlSensorTypeOverride())
                    .append('\n');
        } else {
            sb.append("  primaryRvp=null（解包后不是 RVP 武器，门控必 false）\n");
        }
        sb.append("gate:\n");
        try {
            sb.append("  effectiveSensorType(unit)=").append(RVP_WeaponSensorHelper.effectiveSensorType(unit)).append('\n');
        } catch (Throwable t) {
            sb.append("  effectiveSensorType 异常=").append(t).append('\n');
        }
        sb.append("  unit.staticSensor=").append(unit.getFireControlSensorType()).append('\n');
        if (current != null && current.getWeaponUnit() != null) {
            sb.append("  weaponStation=").append(current.getWeaponUnit().getId())
                    .append(" staticSensor=").append(current.getWeaponUnit().getFireControlSensorType())
                    .append('\n');
        }
        sb.append("  isBallisticRocketWeapon=").append(isBallisticRocketWeapon(current, unit))
                .append(" isActive=").append(isBallisticRocketActive())
                .append(" shouldReplace=").append(isBallisticRocketActive() && ensureTexture())
                .append('\n');
        sb.append("  legacyIconReplace=").append(ywzj_rvp$enableLegacyIconReplace)
                .append(ywzj_rvp$enableLegacyIconReplace ? "（走 RVP 贴图替换）" : "（用本体样式准星，RVP 不隐藏不替换）")
                .append('\n');
        sb.append("texture:\n");
        sb.append("  ensureTexture=").append(ensureTexture())
                .append(" active=").append(ywzj_rvp$activeTexture)
                .append(" packPath=").append(dbgTexturePath())
                .append('\n');
        sb.append("  texSize=").append(ywzj_rvp$texW).append('x').append(ywzj_rvp$texH).append('\n');
        ActiveRocketContext ctx = getActiveRocketContext();
        sb.append("anchor:\n");
        if (ctx == null) {
            sb.append("  context=null\n");
        } else {
            sb.append("  context.unit=").append(ctx.weaponUnit().getId())
                    .append(" weaponHitPos=").append(ctx.weaponUnit().weaponHitPos)
                    .append(" weaponHitPosO=").append(ctx.weaponUnit().weaponHitPosO).append('\n');
            Vec3 screen = getCurrentScreenHitPos();
            sb.append("  screenHitPos=").append(screen)
                    .append(screen == null ? " (null => 不画)" : (screen.z < 0 ? " (z<0 在相机背后 => 不画)" : ""))
                    .append('\n');
        }
        sb.append("frame(last-reset累计): renderCalls=").append(ywzj_rvp$dbgRenderCalls)
                .append(" drawCrosshairCalls=").append(ywzj_rvp$dbgCrosshairCalls)
                .append(" replaceTrue=").append(ywzj_rvp$dbgReplaceTrue)
                .append(" replaceFalse=").append(ywzj_rvp$dbgReplaceFalse)
                .append(" anchorDraws=").append(ywzj_rvp$dbgAnchorDraws)
                .append(" tailDraws=").append(ywzj_rvp$dbgTailDraws)
                .append(" lastStyle=").append(ywzj_rvp$dbgLastStyle)
                .append(" tailState=").append(ywzj_rvp$dbgTailState)
                .append('\n');
        sb.append("判读：drawCrosshairCalls=0 ⇒ 本体没画准星（样式为空/落点为空，隐藏也无从发生）；\n");
        sb.append("      replaceTrue>0 且 anchorDraws=0 ⇒ 隐藏了但没进绘制（重定向未生效）；\n");
        sb.append("      anchorDraws>0 但仍看不到 ⇒ 贴图 blit 在该 HUD 相位不可见（需改图元绘制）。\n");
        return sb.toString();
    }


    public static void draw(GuiGraphics guiGraphics, float partialTick) {
        draw(guiGraphics, partialTick, CCIP_SIZE);
    }

    public static void draw(GuiGraphics guiGraphics, float partialTick, int size) {
        if (!ensureTexture()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float roll = 0.0f;
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            roll = Mth.lerp(partialTick, vehicle.zRotO, vehicle.getZRot());
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(roll));
        ywzj_rvp$blitIcon(guiGraphics, 0.0f, size);
        guiGraphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    public static void drawAtScreen(GuiGraphics guiGraphics, double x, double y, float partialTick, int size) {
        if (!ensureTexture()) {
            return;
        }
        ywzj_rvp$dbgTailDraws++;
        Minecraft mc = Minecraft.getInstance();
        float roll = 0.0f;
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            roll = Mth.lerp(partialTick, vehicle.zRotO, vehicle.getZRot());
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(roll));
        ywzj_rvp$blitIcon(guiGraphics, 0.0f, size);
        guiGraphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    public static Vec3 getCurrentScreenHitPos() {
        ActiveRocketContext context = getActiveRocketContext();
        if (context == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getFrameTime();
        Vec3 prevHit = context.weaponUnit().weaponHitPosO;
        Vec3 currHit = context.weaponUnit().weaponHitPos;
        Vec3 hitPos = currHit;
        if (prevHit != null && currHit != null) {
            hitPos = prevHit.lerp(currHit, Mth.clamp(partialTick, 0.0f, 1.0f));
        }
        if (hitPos == null) {
            return null;
        }
        Vec3 screenPos = VectorUtil.worldToScreen(hitPos);
        if (screenPos == null) {
            return null;
        }
        ResourceLocation weaponId = ywzj_rvp$getWeaponId(context.weapon());
        double hitDistanceMeters = Math.sqrt(context.vehicle().position().distanceToSqr(hitPos));
        Vec3 smoothed = RVP_RocketCcipScreenState.smooth(
                context.vehicle().getId(),
                weaponId,
                context.vehicle().tickCount,
                screenPos,
                hitDistanceMeters
        );
        return smoothed != null ? smoothed : screenPos;
    }

    private static ResourceLocation ywzj_rvp$getWeaponId(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleRocket rocket) {
            return rocket.getData().getWeaponId();
        }
        if (weapon instanceof RVP_ProjectileWeapon rvpWeapon) {
            return rvpWeapon.getData().getWeaponId();
        }
        return null;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        if (!ywzj_rvp$enableForgeOverlayEvent) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ActiveRocketContext context = getActiveRocketContext();
        if (player == null || mc.options.hideGui || context == null || !ensureTexture()) {
            return;
        }
        Vec3 screenHitPos = getCurrentScreenHitPos();
        if (screenHitPos == null || screenHitPos.z < 0) {
            return;
        }
        if (!Double.isFinite(screenHitPos.x) || !Double.isFinite(screenHitPos.y) || !Double.isFinite(screenHitPos.z)) {
            return;
        }
        float partialTick = mc.getFrameTime();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double x = ywzj_rvp$clamp(screenHitPos.x, 0.0, sw);
        double y = ywzj_rvp$clamp(screenHitPos.y, 0.0, sh);
        drawAtScreen(event.getGuiGraphics(), x, y, partialTick, CCIP_SIZE);
    }

    private static double ywzj_rvp$clamp(double v, double min, double max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static boolean ensureTexture() {
        long now = System.currentTimeMillis();
        if (now - ywzj_rvp$lastTextureCheckMs < 1000L) {
            return ywzj_rvp$hasTexture;
        }
        ywzj_rvp$lastTextureCheckMs = now;

        Minecraft mc = Minecraft.getInstance();
        TextureManager textureManager = mc.getTextureManager();
        try {
            Path texturePath = resolvePackTexturePath();
            if (texturePath != null && Files.isRegularFile(texturePath)) {
                long mtime = Files.getLastModifiedTime(texturePath).toMillis();
                if (mtime != ywzj_rvp$loadedTextureMtime) {
                    try (InputStream inputStream = Files.newInputStream(texturePath)) {
                        NativeImage image = NativeImage.read(inputStream);
                        // 先量出真实尺寸与内容包围盒，再交给 DynamicTexture 上传（绘制只采样有像素的区域）
                        ywzj_rvp$applyImageMetrics(image);
                        if (ywzj_rvp$dynamicTexture != null) {
                            ywzj_rvp$dynamicTexture.close();
                        }
                        ywzj_rvp$dynamicTexture = new DynamicTexture(image);
                        textureManager.register(REGISTERED_TEXTURE, ywzj_rvp$dynamicTexture);
                        ywzj_rvp$loadedTextureMtime = mtime;
                    }
                }
                ywzj_rvp$activeTexture = REGISTERED_TEXTURE;
                ywzj_rvp$hasTexture = true;
                ywzj_rvp$debugTextureState("external:" + texturePath + " -> " + REGISTERED_TEXTURE);
                return true;
            }
        } catch (IOException ignored) {
            // Fall through to the standard resource lookup.
        }

        ywzj_rvp$hasTexture = mc.getResourceManager().getResource(FALLBACK_TEXTURE).isPresent();
        ywzj_rvp$activeTexture = FALLBACK_TEXTURE;
        // 回退到资源包贴图时同样量一次尺寸/包围盒，避免沿用上一次（或默认 128）导致采样错位
        if (ywzj_rvp$hasTexture) {
            try (InputStream inputStream = mc.getResourceManager().getResource(FALLBACK_TEXTURE)
                    .orElseThrow().open()) {
                NativeImage image = NativeImage.read(inputStream);
                ywzj_rvp$applyImageMetrics(image);
                image.close();
            } catch (IOException ignored) {
                // 读不出来就保持上一次度量值，至少不会崩
            }
        }
        ywzj_rvp$debugTextureState("fallback:" + FALLBACK_TEXTURE + ", present=" + ywzj_rvp$hasTexture);
        if (!ywzj_rvp$hasTexture) {
            ywzj_rvp$loadedTextureMtime = Long.MIN_VALUE;
        }
        return ywzj_rvp$hasTexture;
    }

    private static void ywzj_rvp$debugTextureState(String source) {
        String state = source + ", active=" + ywzj_rvp$activeTexture + ", hasTexture=" + ywzj_rvp$hasTexture;
        if (!state.equals(ywzj_rvp$lastTextureDebugState)) {
            ywzj_rvp$lastTextureDebugState = state;
            // 火箭 CCIP 纹理状态探针（开关：/rvpdebug flags ccip）
            if (RVP_DebugFlags.CCIP.isEnabled()) {
                LOGGER.info("[RVP][RocketCCIP] {}", state);
            }
        }
    }

    private static Path resolvePackTexturePath() {
        List<Path> candidates = new ArrayList<>();
        Path gameDir = FMLPaths.GAMEDIR.get();
        addCandidate(candidates, gameDir);
        Path parent = gameDir;
        for (int i = 0; i < 4 && parent != null; i++) {
            parent = parent.getParent();
            addCandidate(candidates, parent);
        }
        candidates.add(Paths.get("F:\\载具包临时\\ywzj\\.minecraft\\versions\\Optimized fps\\limitless_vehicle\\rvp\\assets\\rvp\\textures\\ui\\ccip.png"));
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void addCandidate(List<Path> candidates, Path base) {
        if (base == null) {
            return;
        }
        candidates.add(base.resolve("limitless_vehicle").resolve("rvp").resolve("assets").resolve("rvp").resolve("textures").resolve("ui").resolve("ccip.png"));
        candidates.add(base.resolve("versions").resolve("Optimized fps").resolve("limitless_vehicle").resolve("rvp").resolve("assets").resolve("rvp").resolve("textures").resolve("ui").resolve("ccip.png"));
    }
}
