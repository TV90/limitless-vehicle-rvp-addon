package org.ywzj.rvp.client.state;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.resource.RVP_OpticalVehicleNames;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_OpticalTargeting;
import org.ywzj.rvp.guidance.RVP_OpticalConfidenceRuntime;
import org.ywzj.rvp.network.C2SOpticalRequest;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2COpticalFeedback;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData.RecognitionHudStyle;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.NoneVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT)
public final class RVP_ClientOpticalState {
    /** 锁定截图的方形纹理边长。 */
    private static final int SNAPSHOT_SIZE = 256;
    private static final ResourceLocation SNAPSHOT = new ResourceLocation(RVP_MOD.MOD_ID, "optical_lock_frame");
    // 两个独立的短按状态，避免新键歧义及二次触发旧退出键。
    private static final RVP_ViewKeyTap VIEW_TAP = new RVP_ViewKeyTap();
    private static final RVP_ViewKeyTap LEGACY_EXIT_TAP = new RVP_ViewKeyTap();
    private static List<RVP_OpticalTargeting.Contact> contacts = List.of();
    private static WeaponUnit tvUnit;
    private static RVP_WeaponData tvWeapon;
    private static LocalVehiclePlayer.ViewType previousView;
    private static long nextScan;
    private static int cameraSource = -1;
    /** 切换热成像时立即刷新候选，避免重用上一个滤镜的识别结果。 */
    private static boolean lastScanThermal;
    /** 最近一次扫描所用的世界投影倍率。 */
    private static float lastScanProjectionScale;
    /** 只记录当前主画面中实际提交绘制的实体编号。 */
    private static final IntOpenHashSet RENDERED = new IntOpenHashSet();
    /** 采集期结束前由原生与远距渲染器写入本帧绘制名单。 */
    private static boolean collectingFrame;
    /** 与 HUD 绘制配对的世界位置与投影。 */
    private static RenderProjection frameProjection;
    /** 投影所属世界，避免切换维度后沿用旧帧。 */
    private static ClientLevel frameLevel;
    private static int lockSequence;
    /** 本帧准星下的识别对象，只有按键时才提交。 */
    private static Detection aimedDetection;
    private static S2COpticalFeedback lock;
    /** 保存抓拍时的裁切范围和锁点，用于在缩略图上复现同一位置。 */
    private static LockFrame snapshotFrame;
    private static boolean snapshotPending;
    private static S2COpticalFeedback lastLaunch;
    private static long launchNoticeTick;
    private static int observerId = -1;

    private RVP_ClientOpticalState() {}

    public static boolean isOpticalWeapon(WeaponUnit unit) {
        var weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        return weapon != null && RVP_OpticalTargeting.isOptical(weapon.getData().getGuidanceData().getGuidanceType());
    }

    public static boolean isOpticalSelected() {
        var local = LocalVehiclePlayer.instance;
        return local != null && local.onVehicle() && isOpticalWeapon(local.getWeaponUnit());
    }

    public static boolean isViewing(int missileId) {
        return missileId >= 0 && observerId == missileId
                && RVP_ClientHitlState.getControlMode() == org.ywzj.rvp.guidance.RVP_EnumHitlControlMode.VIEW;
    }

    public static boolean canOpenView(Minecraft mc) {
        return !RVP_ClientHitlState.isActive() && lastLaunch != null && mc.level != null && mc.player != null
                && mc.player.getVehicle() instanceof AbstractVehicle vehicle && vehicle.getId() == lastLaunch.vehicleId()
                && mc.level.getEntity(lastLaunch.missileId()) instanceof RVP_MissileEntity missile && missile.isAlive();
    }

    /** TV 炮手观瞄态才允许用重新指定键确认目标。 */
    public static boolean canDesignate() {
        var local = LocalVehiclePlayer.instance;
        return tvWeapon != null && local != null && local.onVehicle() && local.getWeaponUnit() == tvUnit
                && local.viewType == LocalVehiclePlayer.ViewType.SCOPE && !RVP_ClientHitlState.isActive();
    }

    public static boolean hasLock() {
        return lock != null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouse(InputEvent.MouseButton.Pre event) {
        if (handleInput(InputConstants.Type.MOUSE.getOrCreate(event.getButton()), event.getAction())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        handleInput(InputConstants.getKey(event.getKey(), event.getScanCode()), event.getAction());
    }

    private static boolean handleInput(InputConstants.Key key, int action) {
        Minecraft mc = Minecraft.getInstance();
        boolean inVehicle = mc.screen == null && mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle;
        int active = RVP_ClientHitlState.getActiveMissileId();
        boolean opticalView = isViewing(active);
        if (RVP_Keys.OPTICAL_MISSILE_VIEW.getKey().equals(key)) {
            int context = inVehicle && opticalView ? active
                    : inVehicle && canOpenView(mc) ? lastLaunch.missileId() : RVP_ViewKeyTap.NONE;
            if (action == GLFW.GLFW_PRESS && context != RVP_ViewKeyTap.NONE
                    && RVP_Keys.OPTICAL_MISSILE_VIEW.isActiveAndMatches(key)) {
                VIEW_TAP.press(context, System.nanoTime());
            }
            boolean consumed = VIEW_TAP.isPressed();
            if (action == GLFW.GLFW_RELEASE && VIEW_TAP.release(context, System.nanoTime())) {
                if (opticalView) {
                    RVP_ClientHitlState.exitView(mc);
                } else {
                    observerId = context;
                    RVP_Network.CHANNEL.sendToServer(new C2SOpticalRequest(lastLaunch.vehicleId(), lastLaunch.weapon(),
                            observerId, 0, -1, null, false, false));
                }
            }
            if (consumed) return true;
        }

        if (RVP_Keys.HITL_EXIT.getKey().equals(key)) {
            var missile = RVP_ClientHitlState.resolveActiveMissile(mc);
            // 支持右键引爆的旧弹继续走原操作逻辑。
            int context = inVehicle && !opticalView && active >= 0
                    && (missile == null || !missile.rvp$isHitlRightClickDetonate()) ? active : RVP_ViewKeyTap.NONE;
            if (action == GLFW.GLFW_PRESS && context != RVP_ViewKeyTap.NONE
                    && RVP_Keys.HITL_EXIT.isActiveAndMatches(key)) {
                LEGACY_EXIT_TAP.press(context, System.nanoTime());
            }
            boolean consumed = LEGACY_EXIT_TAP.isPressed();
            if (action == GLFW.GLFW_RELEASE && LEGACY_EXIT_TAP.release(context, System.nanoTime())) {
                RVP_ClientHitlState.exitView(mc);
            }
            if (consumed) return true;
        }

        if (inVehicle && action == GLFW.GLFW_PRESS && RVP_Keys.HITL_REDESIGNATE.isActiveAndMatches(key)
                && canDesignate() && !RVP_ClientHitlState.isNoImage(mc.level)) {
            var aimed = aimedDetection;
            if (aimed == null || !aimed.contact().entity().isAlive()) {
                // 查询当前武器站指向的方块；没有可用目标时仍清除上一次锁定。
                Vec3 ground = LocalVehiclePlayer.instance.scopeAimPos();
                if (ground == null) {
                    mc.player.displayClientMessage(Component.translatable("ui.rvp.optical.lock_failed"), true);
                }
                requestLock(-1, ground);
            } else {
                requestLock(aimed.contact().entity().getId(), null);
            }
            return true;
        }
        return false;
    }

    private static void requestLock(int target, Vec3 ground) {
        lock = null;
        clearSnapshot();
        // 服务端返回确认结果后才绘制方框和截图。
        RVP_Network.CHANNEL.sendToServer(new C2SOpticalRequest(tvUnit.getVehicle().getId(), tvWeapon.getWeaponId(),
                -1, ++lockSequence, target, ground, scopeZoomed(), scopeThermal()));
    }

    /** 查询本体武器站当前选择的倍率档。 */
    private static boolean scopeZoomed() {
        return tvUnit.getZoom() > tvUnit.getData().getZoomMin() + 0.01f;
    }

    /** 使用本体武器站实际启用的热成像状态。 */
    private static boolean scopeThermal() {
        return tvUnit.withThermalImager() && LocalVehiclePlayer.instance.thermalImaging;
    }

    public static void receive(S2COpticalFeedback msg) {
        var local = LocalVehiclePlayer.instance;
        if (local == null || local.vehicle == null || local.vehicle.getId() != msg.vehicleId()) return;
        if (msg.missileId() >= 0) {
            lastLaunch = msg;
            launchNoticeTick = local.vehicle.level().getGameTime();
            return;
        }
        if (msg.sequence() != lockSequence || tvWeapon == null || !msg.weapon().equals(tvWeapon.getWeaponId())) return;
        if (msg.point() == null) {
            lock = null;
            clearSnapshot();
        } else {
            snapshotPending = true;
            lock = msg;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        var local = LocalVehiclePlayer.instance;
        var unit = local != null && mc.player != null && local.onVehicle() ? local.getWeaponUnit() : null;
        var selected = unit == null ? null : RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        RVP_WeaponData data = selected == null ? null : selected.getData();
        boolean tv = data != null && data.getGuidanceData().getGuidanceType() == RVP_EnumGuidanceType.TV;
        if (!RVP_ClientHitlState.isActive() && (tvWeapon != (tv ? data : null) || tv && tvUnit != unit)) {
            if (tvUnit != null && tvUnit == unit && local.viewType == LocalVehiclePlayer.ViewType.SCOPE
                    && previousView != null) local.switchViewType(previousView);
            tvUnit = tv ? unit : null;
            tvWeapon = tv ? data : null;
            lock = null;
            aimedDetection = null;
            lockSequence++;
            contacts = List.of();
            cameraSource = -1;
            clearSnapshot();
            if (tv) {
                previousView = local.viewType;
                local.switchViewType(LocalVehiclePlayer.ViewType.SCOPE);
                // 重新选择弹种时清除该武器站上一次使用留下的确认点。
                requestLock(-1, null);
            }
        }
        if (unit == null || lastLaunch != null && (lastLaunch.vehicleId() != unit.getVehicle().getId()
                || mc.level != null && mc.level.getGameTime() - launchNoticeTick > 20
                && mc.level.getEntity(lastLaunch.missileId()) == null)) lastLaunch = null;
        if (unit == null || mc.screen != null) {
            VIEW_TAP.cancel();
            LEGACY_EXIT_TAP.cancel();
            aimedDetection = null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void captureWorldFrame(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        Minecraft mc = Minecraft.getInstance();
        RENDERED.clear();
        frameLevel = mc.level;
        // 查询当前 TV/ATV 视角，并取得已应用 FOV 修改的世界投影及观察矩阵。
        collectingFrame = mc.level != null && mc.screen == null && !mc.options.hideGui
                && mc.options.getCameraType().isFirstPerson()
                && (canDesignate() || isViewing(RVP_ClientHitlState.getActiveMissileId()));
        frameProjection = collectingFrame
                ? new RenderProjection(new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose()),
                        event.getCamera().getPosition(), event.getProjectionMatrix().m11())
                : null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void finishWorldFrame(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) collectingFrame = false;
    }

    @SubscribeEvent
    public static void recordLivingRender(RenderLivingEvent.Post<?, ?> event) {
        recordRenderedEntity(event.getEntity());
    }

    /** 本体模型资源完整、实体渲染器已提交载具后才写入本帧名单。 */
    public static void recordNativeVehicle(AbstractVehicle vehicle) {
        if (!collectingFrame) return;
        // 获取本体实体渲染器所用的模型与贴图，排除本体提前返回的情况。
        var display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display != null && display.getTexture() != null && display.getModel() != null
                && display.getModel().hasBakedModel()) recordRenderedEntity(vehicle);
    }

    /** 远距渲染器也使用真实已加载实体；未加载的绘制代理不会参与候选扫描。 */
    public static void recordRenderedEntity(Entity entity) {
        if (collectingFrame && entity != null && entity.level() == frameLevel && !entity.isInvisible()) {
            RENDERED.add(entity.getId());
        }
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null
                || !mc.options.getCameraType().isFirstPerson() || RVP_ClientHitlState.isHitlLinkBlocked()
                || RVP_ClientHitlState.isNoImage(mc.level) || RVP_ClientHitlState.getSnowOpacity(mc.level) > 0
                || RVP_ClientHitlState.isDircmJammed()) {
            aimedDetection = null;
            return;
        }
        var observed = RVP_ClientHitlState.resolveActiveMissile(mc);
        boolean observing = observed != null && observed.isOpticalMissile();
        boolean scope = canDesignate();
        RenderProjection projection = frameProjection;
        if ((!scope && !observing) || projection == null || frameLevel != mc.level) {
            aimedDetection = null;
            return;
        }

        var data = (observing ? observed.getResolvedWeaponConfig() : tvWeapon).getGuidanceData();
        var camera = mc.gameRenderer.getMainCamera();
        var look = camera.getLookVector();
        var view = new RVP_OpticalTargeting.View(camera.getPosition(), new Vec3(look.x(), look.y(), look.z()));
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int source = observing ? observed.getId() : tvUnit.getVehicle().getId();
        long tick = mc.level.getGameTime();
        boolean thermal = observing ? RVP_ClientHitlState.getVideoMode() == RVP_EnumVideoMode.THERMAL : scopeThermal();
        if (source != cameraSource || tick >= nextScan || thermal != lastScanThermal
                || Math.abs(projection.scale() - lastScanProjectionScale) > 0.01f) {
            cameraSource = source;
            lastScanThermal = thermal;
            lastScanProjectionScale = projection.scale();
            nextScan = tick + 3;
            contacts = RVP_OpticalTargeting.scanDisplayed(observing ? observed : mc.player,
                    observing ? observed.getShooterVehicle() : tvUnit.getVehicle(), view,
                    observing ? data.getGuidanceTargetDistanceRange() : RVP_OpticalTargeting.lockRange(data),
                    observing ? data.getMaxGuidanceAngle() * 0.5 : data.getMaxLockHalfAngle(), thermal,
                    entity -> {
                        // 调用已有投影边界，按实际屏幕中的识别框中心距准星排序。
                        ProjectedBox projected = screenBox(entity, projection, width, height);
                        if (projected == null) return Double.POSITIVE_INFINITY;
                        ScreenBox box = projected.area();
                        double dx = (box.left() + box.right() - width) * 0.5;
                        double dy = (box.top() + box.bottom() - height) * 0.5;
                        return dx * dx + dy * dy;
                    });
        }
        boolean zoomed = observing ? RVP_ClientHitlState.getHitlZoom() > 1.01f : scopeZoomed();
        var detections = contacts.stream().filter(contact -> contact.entity().isAlive())
                .map(contact -> {
                    ProjectedBox projected = screenBox(contact.entity(), projection, width, height);
                    if (projected == null) return null;
                    float confidence = displayedConfidence(contact.confidence(), contact.lighting(),
                            projected.width(), projected.height(), Math.min(width, height));
                    return new Detection(contact, projected.area(), confidence);
                }).filter(Objects::nonNull).toList();
        var boxes = detections.stream().filter(detection -> RVP_OpticalTargeting.canDisplay(detection.confidence())).toList();
        // 即使分数不足以显示框，准星直指候选时也让服务器确认实体锁定是否成立。
        aimedDetection = scope ? detections.stream()
                .filter(detection -> detection.box().contains(width / 2.0, height / 2.0))
                .min(Comparator.comparingDouble(detection -> detection.contact().point().distanceToSqr(view.origin())))
                .orElse(null) : null;
        int tracked = observing ? RVP_ClientHitlState.getClientDesignatedEntityId() : lock == null ? -1 : lock.targetId();
        boolean atvView = observing && data.getGuidanceType() == RVP_EnumGuidanceType.ATV;
        var style = data.getRecognitionHudStyle();
        LockFrame frame = null;
        if (scope && lock != null) {
            frame = lockFrame(lock.point(), lock.targetId(), boxes, projection, width, height);
        } else if (observing && (atvView || style == RecognitionHudStyle.WHITE)) {
            // 使用服务端确认的实体或坐标，普通识别框和失锁后的记忆点不触发白框。
            frame = lockFrame(RVP_ClientHitlState.getConfirmedGroundLock(), tracked, boxes, projection, width, height);
        }
        if (scope && snapshotPending && frame != null) {
            // 截取方框所占的同一范围，在绘制白线和识别文字之前读取底图。
            clearSnapshot();
            mc.getTextureManager().register(SNAPSHOT, new DynamicTexture(captureScope(frame.area(), width, height)));
            snapshotFrame = frame;
        }
        if (style == RecognitionHudStyle.YOLO) {
            List<Caption> captions = new ArrayList<>(boxes.size());
            for (var detection : boxes) {
                Caption caption = drawBox(graphics, mc, detection, tick,
                        detection.contact().entity().getId() == tracked);
                if (caption != null) captions.add(caption);
            }
            // 高分标签占据原位。低分标签只绘制未被盖住的部分，透明底板下也不会透出重叠文字。
            captions.sort(Comparator.comparingDouble(Caption::confidence).reversed());
            for (int i = 0; i < captions.size(); i++) {
                Caption caption = captions.get(i);
                ScreenBox original = caption.area();
                for (ScreenBox area : exposedCaptionAreas(original, captions.subList(0, i))) {
                    graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
                    graphics.fill(area.left(), area.top(), area.right(), area.bottom(), caption.color());
                    var pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(original.left() + 2, original.top() + 1, 0);
                    pose.scale(0.75f, 0.75f, 1f);
                    graphics.drawString(mc.font, caption.text(), 0, 0, 0xffffffff, true);
                    pose.popPose();
                    graphics.disableScissor();
                }
            }
        }
        // 锁定时给小十字留出空间，避免被屏幕中央的大十字吞没。
        drawReticle(graphics, width, height, zoomed || frame != null);
        if (frame != null) {
            if (style != RecognitionHudStyle.NONE) {
                ScreenBox area = frame.area();
                graphics.renderOutline(area.left(), area.top(), area.right() - area.left(),
                        area.bottom() - area.top(), 0xffedf4f7);
            }
            drawLockPoint(graphics, frame.x(), frame.y(), 6);
        }
        if (scope && snapshotFrame != null) {
            int size = Math.max(40, Math.min(104, Math.min(width / 5, height / 3)));
            int x = width - size - 11;
            int y = height / 4;
            graphics.blit(SNAPSHOT, x, y, size, size, 0, 0,
                    SNAPSHOT_SIZE, SNAPSHOT_SIZE, SNAPSHOT_SIZE, SNAPSHOT_SIZE);
            ScreenBox area = snapshotFrame.area();
            int markerX = x + (snapshotFrame.x() - area.left()) * size / (area.right() - area.left());
            int markerY = y + (snapshotFrame.y() - area.top()) * size / (area.bottom() - area.top());
            graphics.enableScissor(x, y, x + size, y + size);
            drawLockPoint(graphics, markerX, markerY, 5);
            graphics.disableScissor();
            Component caption = Component.translatable("ui.rvp.optical.snapshot_target");
            graphics.fill(x, y, x + mc.font.width(caption) + 10, y + 13, 0xb2171c22);
            graphics.drawString(mc.font, caption, x + 5, y + 3, 0xffeff3f5, false);
        }
    }

    private static void drawReticle(GuiGraphics graphics, int width, int height, boolean gapAtCenter) {
        int x = width / 2;
        int y = height / 2;
        int armX = Math.min(width / 3, height / 2);
        int armY = height / 4;
        int gap = gapAtCenter ? Math.max(6, height / 25) : 0;
        graphics.hLine(x - armX, x - gap, y, 0xeef4f7f8);
        graphics.hLine(x + gap, x + armX, y, 0xeef4f7f8);
        graphics.vLine(x, y - armY, y - gap + 1, 0xeef4f7f8);
        graphics.vLine(x, y + gap - 1, y + armY, 0xeef4f7f8);
    }

    /** 小十字以暗描边维持浅色画面上的辨识度。 */
    private static void drawLockPoint(GuiGraphics graphics, int x, int y, int halfSize) {
        graphics.fill(x - halfSize - 1, y - 1, x + halfSize + 2, y + 2, 0xb0121820);
        graphics.fill(x - 1, y - halfSize - 1, x + 2, y + halfSize + 2, 0xb0121820);
        graphics.fill(x - halfSize, y, x + halfSize + 1, y + 1, 0xffedf4f7);
        graphics.fill(x, y - halfSize, x + 1, y + halfSize + 1, 0xffedf4f7);
    }

    private static LockFrame lockFrame(Vec3 point, int entityId, List<Detection> boxes,
                                       RenderProjection projection, int width, int height) {
        if (entityId >= 0) {
            var visible = boxes.stream().filter(box -> box.contact().entity().getId() == entityId)
                    .findFirst().orElse(null);
            if (visible == null) return null;
            point = visible.contact().point();
        }
        if (point == null) return null;
        Vec3 screen = projection.project(point.x, point.y, point.z, width, height);
        if (screen == null || screen.x < 0 || screen.x >= width || screen.y < 0 || screen.y >= height) return null;
        int size = Math.max(1, Math.min(width, height) / 3);
        int x = Math.min(width - 1, (int) Math.round(screen.x));
        int y = Math.min(height - 1, (int) Math.round(screen.y));
        int left = Math.max(0, Math.min(width - size, x - size / 2));
        int top = Math.max(0, Math.min(height - size, y - size / 2));
        return new LockFrame(new ScreenBox(left, top, left + size, top + size), x, y);
    }

    private static NativeImage captureScope(ScreenBox area, int width, int height) {
        try (NativeImage frame = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            int left = area.left() * frame.getWidth() / width;
            int top = area.top() * frame.getHeight() / height;
            int right = area.right() * frame.getWidth() / width;
            int bottom = area.bottom() * frame.getHeight() / height;
            NativeImage crop = new NativeImage(SNAPSHOT_SIZE, SNAPSHOT_SIZE, false);
            frame.resizeSubRectTo(left, top, right - left, bottom - top, crop);
            return crop;
        }
    }

    private record LockFrame(ScreenBox area, int x, int y) {}
    /** 通过最小画面尺寸与可见度筛选的目标及显示分数。 */
    private record Detection(RVP_OpticalTargeting.Contact contact, ScreenBox box, float confidence) {}
    /** 标签候选的完整底板范围、文本、底色和实际显示置信度。 */
    record Caption(ScreenBox area, String text, int color, float confidence) {}

    record ScreenBox(int left, int top, int right, int bottom) {
        boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        boolean overlaps(ScreenBox other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }

    /** 裁出低分标签露出的区域，保留原有文字坐标。 */
    static List<ScreenBox> exposedCaptionAreas(ScreenBox original, List<Caption> higher) {
        List<ScreenBox> exposed = List.of(original);
        for (Caption caption : higher) {
            ScreenBox cover = caption.area();
            if (!original.overlaps(cover)) continue;
            List<ScreenBox> remaining = new ArrayList<>(exposed.size());
            for (ScreenBox area : exposed) {
                if (!area.overlaps(cover)) {
                    remaining.add(area);
                    continue;
                }
                int top = Math.max(area.top(), cover.top());
                int bottom = Math.min(area.bottom(), cover.bottom());
                if (area.top() < top)
                    remaining.add(new ScreenBox(area.left(), area.top(), area.right(), top));
                if (area.left() < cover.left())
                    remaining.add(new ScreenBox(area.left(), top, cover.left(), bottom));
                if (cover.right() < area.right())
                    remaining.add(new ScreenBox(cover.right(), top, area.right(), bottom));
                if (bottom < area.bottom())
                    remaining.add(new ScreenBox(area.left(), bottom, area.right(), area.bottom()));
            }
            exposed = remaining;
            if (exposed.isEmpty()) break;
        }
        return exposed;
    }

    /** 直接投影到当前 GUI，第三方 FOV 修改与武器站缩放共用这组世界渲染矩阵。 */
    record RenderProjection(Matrix4fc matrix, Vec3 camera, float scale) {
        Vec3 project(double x, double y, double z, int width, int height) {
            var clip = matrix.transform(new Vector4f((float) (x - camera.x), (float) (y - camera.y),
                    (float) (z - camera.z), 1f));
            if (!clip.isFinite() || clip.w <= 0.00001f) return null;
            return new Vec3((0.5 + 0.5 * clip.x / clip.w) * width,
                    (0.5 - 0.5 * clip.y / clip.w) * height, clip.w);
        }
    }

    /** 外框使用整数像素绘制，尺寸打分使用取整前的投影。 */
    private record ProjectedBox(ScreenBox area, double width, double height) {}

    private static ProjectedBox screenBox(Entity entity, RenderProjection projection, int width, int height) {
        if (!RENDERED.contains(entity.getId())) return null;
        AABB box = entity.getBoundingBox();
        double left = width, top = height, right = 0, bottom = 0;
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    Vec3 screen = projection.project(x, y, z, width, height);
                    if (screen == null) return null;
                    left = Math.min(left, screen.x);
                    top = Math.min(top, screen.y);
                    right = Math.max(right, screen.x);
                    bottom = Math.max(bottom, screen.y);
                }
            }
        }
        left = Math.max(0, left);
        right = Math.min(width, right);
        top = Math.max(0, top);
        bottom = Math.min(height, bottom);
        if (visibleSize(right - left, bottom - top, Math.min(width, height)) == 0f) return null;
        return new ProjectedBox(new ScreenBox((int) Math.floor(left), (int) Math.floor(top),
                (int) Math.ceil(right), (int) Math.ceil(bottom)), right - left, bottom - top);
    }

    private static Caption drawBox(GuiGraphics graphics, Minecraft mc, Detection detection, long tick, boolean locked) {
        var b = detection.box();
        var contact = detection.contact();
        float score = detection.confidence();
        var label = classify(contact.entity());
        int boxWidth = b.right() - b.left();
        int boxHeight = b.bottom() - b.top();
        int rgb = label.color() & 0x00ffffff;
        int outline = Math.round(Mth.lerp(score, 72f, 255f)) << 24 | rgb;
        int caption = Math.round(Mth.lerp(score, 124f, 255f)) << 24 | rgb;
        graphics.renderOutline(b.left(), b.top(), boxWidth, boxHeight, outline);
        if (locked && boxWidth > 4 && boxHeight > 4) {
            graphics.renderOutline(b.left() + 1, b.top() + 1, boxWidth - 2, boxHeight - 2, outline);
        }
        // 低置信度只显示大类，达标后查询型号；两种标签均显示模拟分数。
        String name = label.lowConfidence();
        if (score >= 0.5f) {
            name = contact.entity() instanceof AbstractVehicle vehicle
                    ? RVP_OpticalVehicleNames.name(vehicle)
                    : Component.translatable("ui.rvp.optical.class." + label.key()).getString();
        }
        if (name.isEmpty()) return null;
        String text = name + " " + String.format(Locale.ROOT, "%.2f",
                animatedConfidence(score, contact.entity().getId(), tick));
        int size = (int) Math.ceil(mc.font.width(text) * 0.75f) + 4;
        int labelLeft = Math.max(0, Math.min(b.left(), graphics.guiWidth() - size));
        int labelTop = Math.max(0, b.top() - 9);
        return new Caption(new ScreenBox(labelLeft, labelTop, labelLeft + size, labelTop + 9), text, caption, score);
    }

    /** 极细的投影必须覆盖最少面积，尺寸评分仍以最长边为准。 */
    private static float visibleSize(double boxWidth, double boxHeight, int screenShortSide) {
        double minimumEdge = screenShortSide * RVP_OpticalTargeting.MIN_APPARENT_SIZE;
        if (boxWidth <= 0 || boxHeight <= 0 || screenShortSide <= 0
                || boxWidth * boxHeight < minimumEdge * minimumEdge) return 0f;
        // 调用客户端与服务端共用的最长边尺寸曲线。
        return RVP_OpticalTargeting.sizeFactor(Math.max(boxWidth, boxHeight) / screenShortSide);
    }

    /** 用实际屏幕投影尺寸代入共用识别公式。 */
    static float displayedConfidence(float visibility, float light, double boxWidth, double boxHeight, int screenShortSide) {
        // 以服务端同步的当前公式计算显示分；实际尺寸来自客户端投影。
        return RVP_OpticalConfidenceRuntime.client().score(visibility,
                visibleSize(boxWidth, boxHeight, screenShortSide), light);
    }

    /** 每隔五刻为每个实体换一个显示分数，不跨类别门槛，也不参与实际锁定。 */
    static float animatedConfidence(float score, int entityId, long tick) {
        int minimum = score < 0.5f ? 41 : 50;
        int maximum = score < 0.5f ? 49 : 99;
        int high = Mth.clamp(Math.round(score * 100) + 1, minimum, maximum);
        int low = Math.max(minimum, high - 2);
        long sample = (tick / 5 ^ ((long) entityId << 32)) * 0x9E3779B97F4A7C15L;
        return (low + Math.floorMod((int) (sample >>> 32), high - low + 1)) / 100f;
    }

    /** 识别标签含翻译键、低置信度类别缩写与框颜色。 */
    private record Label(String key, String lowConfidence, int color) {}

    private static Label classify(Entity entity) {
        if (entity instanceof Player) return new Label("person", "", 0xffac2abc);
        if (entity instanceof RotaryWingVehicle) return new Label("helicopter", "HELI", 0xffcd5721);
        if (entity instanceof FixedWingVehicle) return new Label("aircraft", "JET", 0xff674fc1);
        if (entity instanceof TrackedVehicle) return new Label("tracked", "TANK", 0xff007eae);
        if (entity instanceof WheeledVehicle) return new Label("wheeled", "CAR", 0xff168794);
        if (entity instanceof NoneVehicle) return new Label("vehicle", "STAT", 0xff526caa);
        if (entity instanceof AbstractVehicle) return new Label("vehicle", "", 0xff526caa);
        return new Label("living", "", 0xffac2abc);
    }

    private static void clearSnapshot() {
        snapshotPending = false;
        if (snapshotFrame != null) {
            Minecraft.getInstance().getTextureManager().release(SNAPSHOT);
            snapshotFrame = null;
        }
    }
}
