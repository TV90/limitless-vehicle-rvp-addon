package org.ywzj.rvp.client.firesupport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapHost;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapTool;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionState;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupport;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupportCeaseFire;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;
import org.ywzj.rvp.network.firesupport.S2CFireSupportRequestResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 炮火终端战术地图工具：动态选择、参数控件、落区预览、提交和权威状态展示。 */
public final class RVP_FireSupportMapTool implements RVP_TacticalMapTool {
    /** 工具稳定 ID。 */ private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "fire_support");
    /** 侧栏强调色。 */ private static final int ACCENT = 0xFFE7B85C;
    /** 每个交互行高度，单位 GUI 像素。 */ private static final int ROW_HEIGHT = 18;
    /** 方向手柄命中半径，单位 GUI 像素。 */ private static final double HANDLE_RADIUS = 9.0;
    /** 上次解析的服务端 profile revision。 */ private long loadedRevision = Long.MIN_VALUE;
    /** 当前解析成功的客户端 profile 列表。 */ private List<RVP_ClientFireSupportProfile> profiles = List.of();
    /** profile 解析失败时的可见诊断。 */ private String profileError = "";
    /** 当前 profile 索引。 */ private int profileIndex;
    /** 当前弹种索引。 */ private int munitionIndex;
    /** 当前射击模式索引。 */ private int modeIndex;
    /** 当前几何预设索引。 */ private int patternIndex;
    /** 当前动态参数值。 */ private final Map<String, Double> parameterValues = new LinkedHashMap<>();
    /** 当前目标锚点 X。 */ private double anchorX;
    /** 当前目标锚点 Z。 */ private double anchorZ;
    /** 是否已经由玩家指定锚点。 */ private boolean hasAnchor;
    /** 长轴或徐进方向，单位度。 */ private double headingDegrees;
    /** 当前是否正在拖动方向手柄。 */ private boolean draggingDirection;
    /** 最近发送的新呼叫 nonce。 */ private UUID pendingCallNonce;
    /** 最近发送的停火 nonce。 */ private UUID pendingCeaseNonce;
    /** 当前面板跟踪的任务 UUID。 */ private UUID trackedMissionId;
    /** 当前任务绑定终端实例 UUID 的客户端副本。 */ private UUID boundTerminalInstance;
    /** 接受任务时的权威呼叫截止 Tick。 */ private long callDeadlineTick;
    /** 最近一次即时结果生成的提示。 */ private String resultMessage = "";

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void initialize(RVP_TacticalMapHost host) {
        refreshProfiles();
        if (!hasAnchor) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                anchorX = minecraft.player.getX();
                anchorZ = minecraft.player.getZ();
            }
        }
    }

    @Override
    public void tick(RVP_TacticalMapHost host) {
        refreshProfiles();
        acceptLatestResult();
    }

    @Override
    public boolean mouseClicked(RVP_TacticalMapHost host, double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (insideSidebar(host, mouseX, mouseY)) {
            handleSidebarClick(host, mouseX, mouseY);
            return true;
        }
        if (!insideMap(host, mouseX, mouseY) || mouseY < host.mapTop() + 28) return false;
        if (hasDirectionalPattern() && isOverDirectionHandle(host, mouseX, mouseY)) {
            draggingDirection = true;
            return true;
        }
        Vec3 point = host.pickMapPoint(mouseX, mouseY);
        if (point != null) {
            anchorX = point.x;
            anchorZ = point.z;
            hasAnchor = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(RVP_TacticalMapHost host, double mouseX, double mouseY,
                                int button, double dragX, double dragY) {
        if (!draggingDirection || button != 0 || !hasAnchor) return false;
        updateDirectionFromPointer(host, mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(RVP_TacticalMapHost host, double mouseX, double mouseY, int button) {
        if (button == 0 && draggingDirection) {
            updateDirectionFromPointer(host, mouseX, mouseY);
            draggingDirection = false;
            return true;
        }
        return false;
    }

    @Override
    public void renderOverlay(RVP_TacticalMapHost host, GuiGraphics graphics,
                              int mouseX, int mouseY, float partialTick) {
        renderPreview(host, graphics);
        renderSidebar(host, graphics, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        draggingDirection = false;
    }

    private void refreshProfiles() {
        RVP_ClientFireSupportState state = RVP_ClientFireSupportState.INSTANCE;
        if (loadedRevision == state.revision()) return;
        List<RVP_ClientFireSupportProfile> parsed = new ArrayList<>();
        String error = "";
        try {
            state.profiles().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> parsed.add(RVP_ClientFireSupportProfile.parse(entry.getKey(), entry.getValue())));
        } catch (RuntimeException exception) {
            parsed.clear();
            error = "PROFILE PARSE: " + exception.getMessage();
        }
        profiles = List.copyOf(parsed);
        profileError = error;
        loadedRevision = state.revision();
        profileIndex = clampIndex(profileIndex, profiles.size());
        resetDependentSelections();
    }

    private void resetDependentSelections() {
        RVP_ClientFireSupportProfile profile = profile();
        munitionIndex = clampIndex(munitionIndex, profile == null ? 0 : profile.munitions().size());
        modeIndex = clampIndex(modeIndex, profile == null ? 0 : profile.fireModes().size());
        patternIndex = clampIndex(patternIndex, profile == null ? 0 : profile.patterns().size());
        syncParameterDefaults();
    }

    private void syncParameterDefaults() {
        RVP_ClientFireSupportProfile.Pattern pattern = pattern();
        parameterValues.clear();
        if (pattern != null) pattern.parameters().values().forEach(spec -> parameterValues.put(spec.key(), spec.defaultValue()));
    }

    private void acceptLatestResult() {
        S2CFireSupportRequestResult result = RVP_ClientFireSupportState.INSTANCE.lastResult();
        if (result == null) return;
        if (pendingCallNonce != null && pendingCallNonce.equals(result.nonce()) && !result.ceaseFire()) {
            pendingCallNonce = null;
            resultMessage = result.accepted() ? "呼叫已接受" : "呼叫拒绝: " + result.reason();
            if (result.accepted()) {
                trackedMissionId = result.missionId();
                callDeadlineTick = result.callDeadlineTick();
                parameterValues.putAll(result.parameters());
                if (boundTerminalInstance == null) boundTerminalInstance = readAnyHeldTerminalInstance();
            }
        } else if (pendingCeaseNonce != null && pendingCeaseNonce.equals(result.nonce()) && result.ceaseFire()) {
            pendingCeaseNonce = null;
            resultMessage = result.accepted() ? "停火命令传达中" : "停火拒绝: " + result.reason();
        }
    }

    private void renderPreview(RVP_TacticalMapHost host, GuiGraphics graphics) {
        RVP_ClientFireSupportProfile.Pattern pattern = pattern();
        RVP_ClientFireSupportProfile.FireMode mode = mode();
        if (!hasAnchor || pattern == null || mode == null) return;
        RVP_FireSupportPreviewTypes.Preview preview = RVP_FireSupportPreviewTypes.get(pattern.type());
        if (preview == null) return;
        Map<String, Double> scaled = new LinkedHashMap<>();
        parameterValues.forEach((key, value) -> scaled.put(key, value * mode.dispersionMultiplier()));
        // 调用本项目客户端类型化预览：按同步类型和动态参数绘制非权威落区。
        preview.render(host, graphics, new RVP_FireSupportPreviewTypes.Draft(anchorX, anchorZ, headingDegrees, scaled));
    }

    private void renderSidebar(RVP_TacticalMapHost host, GuiGraphics graphics, int mouseX, int mouseY) {
        int left = host.sideLeft() + 7;
        int right = host.sideRight() - 7;
        int y = host.mapTop() + 7;
        graphics.fill(left, y, right, y + 18, 0xDD111820);
        graphics.fill(left, y, right, y + 1, ACCENT);
        graphics.drawString(host.font(), "RVP 炮火支援终端", left + 5, y + 5, 0xFFFFFFFF, false);
        y += 24;
        if (!profileError.isEmpty()) {
            drawTrimmed(host, graphics, profileError, left, y, right - left, 0xFFFF7777);
            return;
        }
        if (profiles.isEmpty()) {
            drawTrimmed(host, graphics, "等待服务端配置 / 阶段 E profile", left, y, right - left, 0xFFFFCC77);
            return;
        }
        y = drawChoice(host, graphics, left, right, y, "配置", profile() == null ? "-" : label(profile().translationKey()), mouseX, mouseY);
        y = drawChoice(host, graphics, left, right, y, "弹种", munition() == null ? "-" : label(munition().translationKey()), mouseX, mouseY);
        y = drawChoice(host, graphics, left, right, y, "射击", mode() == null ? "-" : label(mode().translationKey()), mouseX, mouseY);
        y = drawChoice(host, graphics, left, right, y, "落区", pattern() == null ? "-" : label(pattern().translationKey()), mouseX, mouseY);
        RVP_ClientFireSupportProfile.Pattern pattern = pattern();
        if (pattern != null) {
            for (RVP_ClientFireSupportProfile.Parameter spec : pattern.parameters().values()) {
                y = drawParameter(host, graphics, left, right, y, spec, mouseX, mouseY);
            }
        }
        y = drawValueRow(host, graphics, left, right, y, "方位", format(headingDegrees) + "°", mouseX, mouseY);
        int[] preview = previewPlan();
        drawTrimmed(host, graphics, "预估 " + preview[0] + "-" + preview[1] + " 发 / 呼叫 " + preview[2] / 20.0 + "s",
                left, y + 3, right - left, 0xFFB9C5D1);
        y += ROW_HEIGHT;
        boolean canCall = hasAnchor && heldTerminalHand() != null && pendingCallNonce == null;
        drawButton(graphics, left, y, right, y + 18, canCall, "确认呼叫", mouseX, mouseY);
        y += 23;
        renderMissionStatus(host, graphics, left, right, y, mouseX, mouseY);
    }

    private void renderMissionStatus(RVP_TacticalMapHost host, GuiGraphics graphics, int left, int right,
                                     int y, int mouseX, int mouseY) {
        if (!resultMessage.isEmpty()) {
            drawTrimmed(host, graphics, resultMessage, left, y, right - left, 0xFFE7B85C);
            y += 12;
        }
        S2CFireSupportMissionUpdate mission = trackedMission();
        if (mission == null) return;
        long now = Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
        String state = switch (mission.state()) {
            case CALLING -> "呼叫中 " + secondsRemaining(callDeadlineTick, now);
            case STRIKING -> "打击中";
            case CEASE_FIRE_PENDING -> "停火传达 " + secondsRemaining(mission.ceaseFireEffectiveTick(), now);
            case COMPLETED -> "任务完成";
            case CANCELLED -> "任务取消: " + mission.reason();
            case CEASED -> "已停火";
            case FAILED -> "任务失败: " + mission.reason();
        };
        drawTrimmed(host, graphics, state, left, y, right - left, 0xFFFFFFFF);
        y += 12;
        drawTrimmed(host, graphics, "已发 " + mission.deliveredRounds() + " / " + mission.totalRounds(), left, y, right - left, 0xFFB9C5D1);
        y += 15;
        if (mission.state() == RVP_FireSupportMissionState.STRIKING
                || mission.state() == RVP_FireSupportMissionState.CEASE_FIRE_PENDING) {
            boolean exactTerminalHeld = isBoundTerminalHeld();
            drawButton(graphics, left, y, right, y + 18, exactTerminalHeld && pendingCeaseNonce == null,
                    exactTerminalHeld ? "请求停火" : "需手持本任务终端", mouseX, mouseY);
        }
    }

    private void handleSidebarClick(RVP_TacticalMapHost host, double mouseX, double mouseY) {
        int y = host.mapTop() + 31;
        if (rowHit(y, mouseY)) { profileIndex = cycle(profileIndex, profiles.size(), side(host, mouseX)); resetDependentSelections(); return; }
        y += ROW_HEIGHT;
        RVP_ClientFireSupportProfile profile = profile();
        if (rowHit(y, mouseY)) { munitionIndex = cycle(munitionIndex, profile == null ? 0 : profile.munitions().size(), side(host, mouseX)); return; }
        y += ROW_HEIGHT;
        if (rowHit(y, mouseY)) { modeIndex = cycle(modeIndex, profile == null ? 0 : profile.fireModes().size(), side(host, mouseX)); return; }
        y += ROW_HEIGHT;
        if (rowHit(y, mouseY)) { patternIndex = cycle(patternIndex, profile == null ? 0 : profile.patterns().size(), side(host, mouseX)); syncParameterDefaults(); return; }
        y += ROW_HEIGHT;
        RVP_ClientFireSupportProfile.Pattern pattern = pattern();
        if (pattern != null) {
            for (RVP_ClientFireSupportProfile.Parameter spec : pattern.parameters().values()) {
                if (rowHit(y, mouseY)) { adjustParameterFromPointer(host, spec, mouseX); return; }
                y += ROW_HEIGHT;
            }
        }
        if (rowHit(y, mouseY)) { headingDegrees = normalizeHeading(headingDegrees + side(host, mouseX) * 5.0); return; }
        y += ROW_HEIGHT * 2;
        if (mouseY >= y && mouseY <= y + 18) { submitCall(); return; }
        y += 23;
        if (!resultMessage.isEmpty()) y += 12;
        S2CFireSupportMissionUpdate mission = trackedMission();
        if (mission != null) y += 27;
        if (mission != null && (mission.state() == RVP_FireSupportMissionState.STRIKING
                || mission.state() == RVP_FireSupportMissionState.CEASE_FIRE_PENDING)
                && mouseY >= y && mouseY <= y + 18) submitCeaseFire();
    }

    private void submitCall() {
        RVP_ClientFireSupportProfile.Munition munition = munition();
        RVP_ClientFireSupportProfile.FireMode mode = mode();
        RVP_ClientFireSupportProfile.Pattern pattern = pattern();
        InteractionHand hand = heldTerminalHand();
        if (!hasAnchor || munition == null || mode == null || pattern == null || hand == null) return;
        pendingCallNonce = UUID.randomUUID();
        boundTerminalInstance = readTerminalInstance(Minecraft.getInstance().player.getItemInHand(hand));
        resultMessage = "正在提交呼叫…";
        // 调用阶段 C 请求消息：只提交选择、锚点、方向和动态参数，派生值由服务端重算。
        RVP_Network.CHANNEL.sendToServer(new C2SRequestFireSupport(RVP_ClientFireSupportState.INSTANCE.revision(),
                hand, munition.id(), mode.id(), pattern.id(), anchorX, anchorZ, headingDegrees,
                Map.copyOf(parameterValues), pendingCallNonce));
    }

    private void submitCeaseFire() {
        if (trackedMissionId == null || pendingCeaseNonce != null
                || !isBoundTerminalHeld()) return;
        pendingCeaseNonce = UUID.randomUUID();
        resultMessage = "正在请求停火…";
        // 调用阶段 C 停火消息：服务端重新核验所有者、阶段与绑定终端实例。
        RVP_Network.CHANNEL.sendToServer(new C2SRequestFireSupportCeaseFire(trackedMissionId, pendingCeaseNonce));
    }

    private void updateDirectionFromPointer(RVP_TacticalMapHost host, double mouseX, double mouseY) {
        double dx = host.screenToWorldX(mouseX) - anchorX;
        double dz = host.screenToWorldZ(mouseY) - anchorZ;
        if (dx * dx + dz * dz < 1.0) return;
        headingDegrees = normalizeHeading(Math.toDegrees(Math.atan2(dx, dz)));
        RVP_ClientFireSupportProfile.Parameter length = pattern() == null ? null : pattern().parameters().get("length_m");
        if (length != null) {
            double distance = Math.hypot(dx, dz);
            if (pattern().type().equals(org.ywzj.rvp.firesupport.RVP_FireSupportPatternTypes.LINE)) distance *= 2.0;
            parameterValues.put(length.key(), snap(length, distance / Math.max(0.001, mode().dispersionMultiplier())));
        }
    }

    private boolean isOverDirectionHandle(RVP_TacticalMapHost host, double mouseX, double mouseY) {
        double length = parameterValues.getOrDefault("length_m", 0.0) * mode().dispersionMultiplier();
        double along = pattern().type().equals(org.ywzj.rvp.firesupport.RVP_FireSupportPatternTypes.LINE) ? length * 0.5 : length;
        double heading = Math.toRadians(headingDegrees);
        double hx = host.worldToScreenX(anchorX + Math.sin(heading) * along);
        double hy = host.worldToScreenY(anchorZ + Math.cos(heading) * along);
        return Math.hypot(mouseX - hx, mouseY - hy) <= HANDLE_RADIUS;
    }

    private int[] previewPlan() {
        RVP_ClientFireSupportProfile profile = profile();
        RVP_ClientFireSupportProfile.Munition munition = munition();
        RVP_ClientFireSupportProfile.FireMode mode = mode();
        if (profile == null || munition == null || mode == null) return new int[]{0, 0, 0};
        int min = 0;
        int max = 0;
        for (RVP_ClientFireSupportProfile.Phase phase : mode.phases()) {
            if (phase.fixedRounds() >= 0) min += phase.fixedRounds();
            else if (phase.baseMultiplier() > 0) min += (int) Math.ceil(munition.roundsPerUnit() * phase.baseMultiplier());
            else min += phase.randomMin();
            max += phase.randomMax() >= 0 ? phase.randomMax()
                    : phase.fixedRounds() >= 0 ? phase.fixedRounds()
                    : (int) Math.ceil(munition.roundsPerUnit() * phase.baseMultiplier());
        }
        return new int[]{min, max, (int) Math.ceil(profile.baseCallDurationTicks() * mode.callDurationMultiplier())};
    }

    private int drawChoice(RVP_TacticalMapHost host, GuiGraphics graphics, int left, int right, int y,
                           String name, String value, int mouseX, int mouseY) {
        return drawValueRow(host, graphics, left, right, y, name, value, mouseX, mouseY);
    }

    private int drawParameter(RVP_TacticalMapHost host, GuiGraphics graphics, int left, int right, int y,
                              RVP_ClientFireSupportProfile.Parameter spec, int mouseX, int mouseY) {
        boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT && mouseX >= left && mouseX <= right;
        graphics.fill(left, y, right, y + ROW_HEIGHT - 2, hover ? 0x99405261 : 0x77182029);
        double value = parameterValues.getOrDefault(spec.key(), spec.defaultValue());
        int trackLeft = left + 74;
        int trackRight = right - 18;
        int trackY = y + 12;
        graphics.fill(trackLeft, trackY, trackRight, trackY + 2, 0xFF4B5663);
        double ratio = (value - spec.min()) / Math.max(1.0E-9, spec.max() - spec.min());
        int handleX = Mth.clamp((int) Math.round(Mth.lerp(ratio, trackLeft, trackRight)), trackLeft, trackRight);
        graphics.fill(trackLeft, trackY, handleX, trackY + 2, ACCENT);
        graphics.fill(handleX - 1, trackY - 2, handleX + 2, trackY + 4, 0xFFF6D68F);
        graphics.drawString(host.font(), "−", left + 4, y + 4, ACCENT, false);
        graphics.drawString(host.font(), "+", right - 10, y + 4, ACCENT, false);
        drawTrimmed(host, graphics, spec.key() + " " + format(value) + spec.unit(), left + 15, y + 3, 58, 0xFFE8EEF5);
        return y + ROW_HEIGHT;
    }

    private int drawValueRow(RVP_TacticalMapHost host, GuiGraphics graphics, int left, int right, int y,
                             String name, String value, int mouseX, int mouseY) {
        boolean hover = mouseY >= y && mouseY < y + ROW_HEIGHT && mouseX >= left && mouseX <= right;
        graphics.fill(left, y, right, y + ROW_HEIGHT - 2, hover ? 0x99405261 : 0x77182029);
        graphics.drawString(host.font(), "‹", left + 4, y + 4, ACCENT, false);
        graphics.drawString(host.font(), "›", right - 9, y + 4, ACCENT, false);
        drawTrimmed(host, graphics, name + "  " + value, left + 15, y + 4, right - left - 30, 0xFFE8EEF5);
        return y + ROW_HEIGHT;
    }

    private void drawButton(GuiGraphics graphics, int left, int top, int right, int bottom, boolean active,
                            String label, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        graphics.fill(left, top, right, bottom, active ? (hover ? 0xFF8A6D36 : 0xFF5F4D2E) : 0xFF30343A);
        graphics.fill(left, top, right, top + 1, active ? ACCENT : 0xFF666A70);
        Minecraft minecraft = Minecraft.getInstance();
        int x = left + Math.max(4, (right - left - minecraft.font.width(label)) / 2);
        graphics.drawString(minecraft.font, label, x, top + 5, active ? 0xFFFFFFFF : 0xFF90959B, false);
    }

    private void drawTrimmed(RVP_TacticalMapHost host, GuiGraphics graphics, String text,
                             int x, int y, int width, int color) {
        graphics.drawString(host.font(), host.font().plainSubstrByWidth(text, Math.max(8, width)), x, y, color, false);
    }

    private void adjustParameter(RVP_ClientFireSupportProfile.Parameter spec, int direction) {
        double current = parameterValues.getOrDefault(spec.key(), spec.defaultValue());
        parameterValues.put(spec.key(), snap(spec, current + direction * spec.step()));
    }

    /** 用行两端执行单步调整，用中部轨道执行按 JSON min/max/step 对齐的滑条调整。 */
    private void adjustParameterFromPointer(RVP_TacticalMapHost host,
                                            RVP_ClientFireSupportProfile.Parameter spec, double mouseX) {
        int left = host.sideLeft() + 7;
        int right = host.sideRight() - 7;
        if (mouseX <= left + 14) {
            adjustParameter(spec, -1);
            return;
        }
        if (mouseX >= right - 14) {
            adjustParameter(spec, 1);
            return;
        }
        int trackLeft = left + 74;
        int trackRight = right - 18;
        double ratio = Mth.clamp((mouseX - trackLeft) / Math.max(1.0, trackRight - trackLeft), 0.0, 1.0);
        parameterValues.put(spec.key(), snap(spec, Mth.lerp(ratio, spec.min(), spec.max())));
    }

    private double snap(RVP_ClientFireSupportProfile.Parameter spec, double value) {
        double steps = Math.round((Mth.clamp(value, spec.min(), spec.max()) - spec.min()) / spec.step());
        return Mth.clamp(spec.min() + steps * spec.step(), spec.min(), spec.max());
    }

    private S2CFireSupportMissionUpdate trackedMission() {
        return trackedMissionId == null ? null : RVP_ClientFireSupportState.INSTANCE.missions().get(trackedMissionId);
    }

    private InteractionHand heldTerminalHand() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return null;
        RVP_ClientFireSupportProfile profile = profile();
        if (profile != null && profile.allowedHands().contains("main")
                && minecraft.player.getMainHandItem().is(RVP_Items.FIRE_SUPPORT_TERMINAL.get())) return InteractionHand.MAIN_HAND;
        if (profile != null && profile.allowedHands().contains("off")
                && minecraft.player.getOffhandItem().is(RVP_Items.FIRE_SUPPORT_TERMINAL.get())) return InteractionHand.OFF_HAND;
        return null;
    }

    private UUID readAnyHeldTerminalInstance() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return null;
        UUID main = readTerminalInstance(minecraft.player.getMainHandItem());
        return main != null ? main : readTerminalInstance(minecraft.player.getOffhandItem());
    }

    /** @return 主手或副手是否持有本任务绑定的同一终端实例。 */
    private boolean isBoundTerminalHeld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || boundTerminalInstance == null) return false;
        return boundTerminalInstance.equals(readTerminalInstance(minecraft.player.getMainHandItem()))
                || boundTerminalInstance.equals(readTerminalInstance(minecraft.player.getOffhandItem()));
    }

    private UUID readTerminalInstance(ItemStack stack) {
        if (!stack.is(RVP_Items.FIRE_SUPPORT_TERMINAL.get())) return null;
        var root = stack.getTagElement("rvp_fire_support");
        return root != null && root.hasUUID("terminal_instance") ? root.getUUID("terminal_instance") : null;
    }

    private RVP_ClientFireSupportProfile profile() { return at(profiles, profileIndex); }
    private RVP_ClientFireSupportProfile.Munition munition() { return profile() == null ? null : at(profile().munitions(), munitionIndex); }
    private RVP_ClientFireSupportProfile.FireMode mode() { return profile() == null ? null : at(profile().fireModes(), modeIndex); }
    private RVP_ClientFireSupportProfile.Pattern pattern() { return profile() == null ? null : at(profile().patterns(), patternIndex); }
    private boolean hasDirectionalPattern() { return pattern() != null && parameterValues.containsKey("length_m"); }
    private boolean insideMap(RVP_TacticalMapHost host, double x, double y) { return x >= host.mapLeft() && x <= host.mapRight() && y >= host.mapTop() && y <= host.mapBottom(); }
    private boolean insideSidebar(RVP_TacticalMapHost host, double x, double y) { return x >= host.sideLeft() && x <= host.sideRight() && y >= host.mapTop() && y <= host.mapBottom(); }
    private boolean rowHit(int y, double mouseY) { return mouseY >= y && mouseY < y + ROW_HEIGHT; }
    private int side(RVP_TacticalMapHost host, double mouseX) { return mouseX < (host.sideLeft() + host.sideRight()) * 0.5 ? -1 : 1; }
    private int cycle(int value, int size, int direction) { return size <= 0 ? 0 : Math.floorMod(value + direction, size); }
    private int clampIndex(int value, int size) { return size <= 0 ? 0 : Mth.clamp(value, 0, size - 1); }
    private double normalizeHeading(double value) { double out = value % 360.0; return out < 0 ? out + 360.0 : out; }
    private String format(double value) { return Math.abs(value - Math.rint(value)) < 1.0E-6 ? Long.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.1f", value); }
    private String label(String translationKey) { return Component.translatable(translationKey).getString(); }
    private String secondsRemaining(long deadline, long now) { return String.format(java.util.Locale.ROOT, "%.1fs", Math.max(0L, deadline - now) / 20.0); }
    private static <T> T at(List<T> values, int index) { return index >= 0 && index < values.size() ? values.get(index) : null; }
}
