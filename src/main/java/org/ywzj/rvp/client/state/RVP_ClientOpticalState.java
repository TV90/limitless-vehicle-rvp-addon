package org.ywzj.rvp.client.state;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.resource.RVP_OpticalVehicleNames;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_OpticalTargeting;
import org.ywzj.rvp.network.C2SOpticalRequest;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2COpticalFeedback;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
                            observerId, 0, -1, null, false));
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
            if (aimed == null) {
                requestLock(-1, LocalVehiclePlayer.instance.scopeAimPos());
            } else {
                var contact = aimed.contact();
                int target = contact.entity().isAlive() ? contact.entity().getId() : -1;
                requestLock(target, null);
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
                -1, ++lockSequence, target, ground, scopeZoomed()));
    }

    /** 查询本体武器站当前选择的倍率档。 */
    private static boolean scopeZoomed() {
        return tvUnit.getZoom() > tvUnit.getData().getZoomMin() + 0.01f;
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
        if (!scope && !observing) return;

        var data = (observing ? observed.getResolvedWeaponConfig() : tvWeapon).getGuidanceData();
        var camera = mc.gameRenderer.getMainCamera();
        var look = camera.getLookVector();
        var view = new RVP_OpticalTargeting.View(camera.getPosition(), new Vec3(look.x(), look.y(), look.z()));
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int source = observing ? observed.getId() : tvUnit.getVehicle().getId();
        long tick = mc.level.getGameTime();
        if (source != cameraSource || tick >= nextScan) {
            cameraSource = source;
            nextScan = tick + 3;
            contacts = RVP_OpticalTargeting.scanDisplayed(observing ? observed : mc.player,
                    observing ? observed.getShooterVehicle() : tvUnit.getVehicle(), view,
                    observing ? data.getGuidanceTargetDistanceRange() : RVP_OpticalTargeting.lockRange(data),
                    observing ? data.getMaxGuidanceAngle() * 0.5 : data.getMaxLockHalfAngle(),
                    entity -> {
                        // 调用已有投影边界，按实际屏幕中的识别框中心距准星排序。
                        ScreenBox box = screenBox(entity, width, height);
                        if (box == null) return Double.POSITIVE_INFINITY;
                        double dx = (box.left() + box.right() - width) * 0.5;
                        double dy = (box.top() + box.bottom() - height) * 0.5;
                        return dx * dx + dy * dy;
                    });
        }
        var boxes = contacts.stream().filter(contact -> contact.entity().isAlive())
                .map(contact -> new Detection(contact, screenBox(contact.entity(), width, height)))
                .filter(detection -> detection.box() != null).toList();
        aimedDetection = scope ? boxes.stream()
                .filter(detection -> detection.box().contains(width / 2.0, height / 2.0))
                .min(Comparator.comparingDouble(detection -> detection.contact().point().distanceToSqr(view.origin())))
                .orElse(null) : null;
        int tracked = observing ? RVP_ClientHitlState.getClientDesignatedEntityId() : lock == null ? -1 : lock.targetId();
        boolean atvView = observing && data.getGuidanceType() == RVP_EnumGuidanceType.ATV;
        LockFrame frame = null;
        if (scope && lock != null) {
            frame = lockFrame(lock.point(), lock.targetId(), boxes, width, height);
        } else if (atvView && tracked >= 0) {
            // ATV 使用服务端确认的实体编号，普通识别框不触发锁定白框。
            frame = lockFrame(null, tracked, boxes, width, height);
        }
        if (scope && snapshotPending && frame != null) {
            // 截取方框所占的同一范围，在绘制白线和识别文字之前读取底图。
            clearSnapshot();
            mc.getTextureManager().register(SNAPSHOT, new DynamicTexture(captureScope(frame.area(), width, height)));
            snapshotFrame = frame;
        }
        boolean zoomed = observing ? RVP_ClientHitlState.getHitlZoom() > 1.01f : scopeZoomed();
        float scopeMagnification = observing ? 1f : RVP_OpticalTargeting.scopeMagnification(tvUnit, zoomed);
        for (var detection : boxes) {
            var contact = detection.contact();
            var box = detection.box();
            // TV 发射前的 HUD 与服务端锁定使用相同的参考视场；弹头观察跟随实际投影。
            float confidence = observing ? displayedConfidence(contact.confidence(),
                    box.right() - box.left(), box.bottom() - box.top(), Math.min(width, height))
                    : RVP_OpticalTargeting.scopeConfidence(contact, view, scopeMagnification);
            drawBox(graphics, mc, detection, confidence, contact.entity().getId() == tracked);
        }
        // 锁定时给小十字留出空间，避免被屏幕中央的大十字吞没。
        drawReticle(graphics, width, height, zoomed || frame != null);
        if (frame != null) {
            ScreenBox area = frame.area();
            graphics.renderOutline(area.left(), area.top(), area.right() - area.left(),
                    area.bottom() - area.top(), 0xffedf4f7);
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

    private static LockFrame lockFrame(Vec3 point, int entityId, List<Detection> boxes, int width, int height) {
        if (entityId >= 0) {
            var visible = boxes.stream().filter(box -> box.contact().entity().getId() == entityId)
                    .findFirst().orElse(null);
            if (visible == null) return null;
            point = visible.contact().point();
        }
        if (point == null) return null;
        Vec3 screen = VectorUtil.worldToScreen(point);
        if (screen.z <= 0 || screen.x < 0 || screen.x >= width || screen.y < 0 || screen.y >= height) return null;
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
    private record Detection(RVP_OpticalTargeting.Contact contact, ScreenBox box) {}
    private record ScreenBox(int left, int top, int right, int bottom) {
        boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static ScreenBox screenBox(Entity entity, int width, int height) {
        AABB box = entity.getBoundingBox();
        int left = width, top = height, right = 0, bottom = 0;
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    Vec3 screen = VectorUtil.worldToScreen(new Vec3(x, y, z));
                    if (screen.z <= 0) return null;
                    left = Math.min(left, (int) Math.floor(screen.x));
                    top = Math.min(top, (int) Math.floor(screen.y));
                    right = Math.max(right, (int) Math.ceil(screen.x));
                    bottom = Math.max(bottom, (int) Math.ceil(screen.y));
                }
            }
        }
        left = Math.max(0, left);
        right = Math.min(width, right);
        top = Math.max(0, top);
        bottom = Math.min(height, bottom);
        return right - left >= 2 && bottom - top >= 2 ? new ScreenBox(left, top, right, bottom) : null;
    }

    private static void drawBox(GuiGraphics graphics, Minecraft mc, Detection detection, float score, boolean locked) {
        var b = detection.box();
        var contact = detection.contact();
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
        String name = contact.entity() instanceof AbstractVehicle vehicle
                ? RVP_OpticalVehicleNames.name(vehicle)
                : Component.translatable("ui.rvp.optical.class." + label.key()).getString();
        String text = name + " " + String.format(Locale.ROOT, "%.2f", score);
        float scale = 0.75f;
        int size = (int) Math.ceil(mc.font.width(text) * scale) + 4;
        int labelLeft = Math.max(0, Math.min(b.left(), graphics.guiWidth() - size));
        int labelTop = Math.max(0, b.top() - 9);
        graphics.fill(labelLeft, labelTop, labelLeft + size, labelTop + 9, caption);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(labelLeft + 2, labelTop + 1, 0);
        pose.scale(scale, scale, 1f);
        graphics.drawString(mc.font, text, 0, 0, 0xffffffff, true);
        pose.popPose();
    }

    /** 距离和光学倍率共同反映在投影尺寸中；按识别框短边占屏幕短边的比例计算 HUD 清晰度。 */
    static float displayedConfidence(float visibility, int boxWidth, int boxHeight, int screenShortSide) {
        float apparentSize = (float) Math.min(boxWidth, boxHeight) / screenShortSide;
        return RVP_OpticalTargeting.sizeWeightedConfidence(visibility, apparentSize);
    }

    private record Label(String key, int color) {}

    private static Label classify(Entity entity) {
        if (entity instanceof Player) return new Label("person", 0xffac2abc);
        if (entity instanceof RotaryWingVehicle) return new Label("helicopter", 0xffcd5721);
        if (entity instanceof FixedWingVehicle) return new Label("aircraft", 0xff674fc1);
        if (entity instanceof TrackedVehicle) return new Label("tracked", 0xff007eae);
        if (entity instanceof WheeledVehicle) return new Label("wheeled", 0xff168794);
        if (entity instanceof AbstractVehicle) return new Label("vehicle", 0xff526caa);
        return new Label("living", 0xffac2abc);
    }

    private static void clearSnapshot() {
        snapshotPending = false;
        if (snapshotFrame != null) {
            Minecraft.getInstance().getTextureManager().release(SNAPSHOT);
            snapshotFrame = null;
        }
    }
}
