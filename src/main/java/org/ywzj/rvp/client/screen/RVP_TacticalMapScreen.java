package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.gui.RadarEnabledTickHelper;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.network.S2CRemoteAmmoSnapshot;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.custom.vehicle.FixedWingVehicleData;
import org.ywzj.vehicle.custom.vehicle.RotaryWingVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientRadarAction;
import org.ywzj.vehicle.network.message.ClientVehicleSwitchWeapon;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.lang.reflect.Method;

public class RVP_TacticalMapScreen extends Screen {

    private static final int MAP_MIN_SIZE = 120;
    private static final String ICON_TEXTURE_ROOT = "textures/gui/map_icon/";
    private static final int OWN_ICON_COLOR = 0xFF3AA7FF;
    private static final int FRIEND_ICON_COLOR = 0xFF64E08B;
    private static final int HOSTILE_ICON_COLOR = 0xFFFF5B5B;
    private static final int NEUTRAL_ICON_COLOR = 0xFFF5F7FA;
    private static final int UNMANNED_VEHICLE_ICON_COLOR = 0xFFF5F7FA;
    private static final int GPS_ICON_COLOR = 0xFFFFF08A;
    private static final ResourceLocation PLAYER_ICON = mapIcon("player.png");
    private static final ResourceLocation HELI_ICON = mapIcon("atkheli.png");
    private static final ResourceLocation JET_ICON = mapIcon("jet.png");
    private static final ResourceLocation GROUND_ICON = mapIcon("mbt.png");
    private static final ResourceLocation MISSILE_ICON = mapIcon("msl.png");
    private static final ResourceLocation BOMB_ICON = mapIcon("jdam.png");
    private static final ResourceLocation GPS_ICON = mapIcon("gps.png");
    private static final float TOOLBAR_BUTTON_TEXT_SCALE = 0.78f;
    private static final long DOUBLE_CLICK_WINDOW_MS = 350L;
    private static final int RADAR_LOCK_ASSIST_TICKS = 80;
    private static final int QUICK_FIRE_TIMEOUT_TICKS = 160;
    private static final int QUICK_FIRE_LOCK_STABLE_TICKS = 2;
    private static final int QUICK_FIRE_RETRY_INTERVAL_TICKS = 2;
    private static final int IMPACT_CROSS_LIFE_TICKS = 20;

    private enum SidebarMode {
        NONE,
        GPS,
        RADAR
    }

    private enum MarkerKind {
        PLAYER,
        OWN_VEHICLE,
        REMOTE_VEHICLE,
        MISSILE,
        CONTACT
    }

    private static class MarkerHit {
        private final int entityId;
        @Nullable
        private final Entity entity;
        private final MarkerKind kind;
        private final int screenX;
        private final int screenY;
        private final int radius;
        private final Vec3 focusPos;
        private final Vec3 targetPos;
        private final Component displayName;
        private final double speedKmh;
        private final boolean gpsAmmo;
        private final int accentColor;

        private MarkerHit(int entityId, @Nullable Entity entity, MarkerKind kind, int screenX, int screenY, int radius,
                          Vec3 focusPos, Vec3 targetPos, Component displayName, double speedKmh, boolean gpsAmmo, int accentColor) {
            this.entityId = entityId;
            this.entity = entity;
            this.kind = kind;
            this.screenX = screenX;
            this.screenY = screenY;
            this.radius = radius;
            this.focusPos = focusPos;
            this.targetPos = targetPos;
            this.displayName = displayName;
            this.speedKmh = speedKmh;
            this.gpsAmmo = gpsAmmo;
            this.accentColor = accentColor;
        }
    }

    private static final class ImpactCross {
        private final Vec3 pos;
        private int lifeTicks;

        private ImpactCross(Vec3 pos, int lifeTicks) {
            this.pos = pos;
            this.lifeTicks = lifeTicks;
        }
    }

    private static final class RadarContactRow {
        private final int entityId;
        private final Entity entity;
        private final RadarUnit sourceRadar;
        private final Vec3 detectedPos;
        private final double distanceKm;
        private final double altitudeKm;
        private final double speedMs;
        private final String nctrLabel;
        private final String relationLabel;
        private final int relationColor;
        private final boolean locked;

        private RadarContactRow(int entityId, Entity entity, RadarUnit sourceRadar, Vec3 detectedPos,
                                double distanceKm, double altitudeKm, double speedMs,
                                String nctrLabel, String relationLabel, int relationColor, boolean locked) {
            this.entityId = entityId;
            this.entity = entity;
            this.sourceRadar = sourceRadar;
            this.detectedPos = detectedPos;
            this.distanceKm = distanceKm;
            this.altitudeKm = altitudeKm;
            this.speedMs = speedMs;
            this.nctrLabel = nctrLabel;
            this.relationLabel = relationLabel;
            this.relationColor = relationColor;
            this.locked = locked;
        }
    }

    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private Button bindButton;
    private Button clearButton;
    private TerminalButton centerButton;
    private TerminalButton followButton;
    private TerminalButton weaponDropdownButton;
    private TerminalButton radarPanelButton;
    private TerminalButton gpsPanelButton;
    private TerminalButton gpsModeButton;
    private TerminalButton gpsClearAllButton;
    private TerminalButton gpsQuickMarkButton;
    private TerminalButton radarHideFriendlyButton;
    private boolean gpsQuickMarkMode;
    private boolean hideFriendlyRadarRows;

    private int mapLeft;
    private int mapTop;
    private int mapRight;
    private int mapBottom;
    private int sideLeft;
    private int sideRight;

    private double viewWorldX;
    private double viewWorldZ;
    private double blocksPerPixel = 2.5;
    private boolean followPlayer = true;
    private boolean draggingMap;
    private boolean sidebarVisible;
    private SidebarMode sidebarMode = SidebarMode.NONE;
    private boolean mapContextMenuVisible;
    private int mapContextMenuX;
    private int mapContextMenuY;
    private Vec3 mapContextTarget;
    private final List<MarkerHit> markerHits = new ArrayList<>();
    private boolean entityContextMenuVisible;
    private int entityContextMenuX;
    private int entityContextMenuY;
    private MarkerHit entityContextTarget;
    private Integer selectedMarkerId;
    private MarkerHit selectedMarkerHit;
    private final List<RadarUnit> tacticalRadarUnits = new ArrayList<>();
    private final Set<Integer> tacticalRadarVisibleIds = new HashSet<>();
    private final List<RadarContactRow> radarContactRows = new ArrayList<>();
    private final Map<Integer, Vec3> trackedAmmoPositions = new HashMap<>();
    private final Set<Integer> visibleTrackedAmmoIds = new HashSet<>();
    private final List<ImpactCross> recentImpactCrosses = new ArrayList<>();
    private int radarRowScroll;
    private Integer selectedRadarEntityId;
    private long lastMapLeftClickAt;
    private int lastMapLeftClickEntityId = Integer.MIN_VALUE;
    private long lastMapRightClickAt;
    private int lastMapRightClickEntityId = Integer.MIN_VALUE;
    private long lastRadarRowLeftClickAt;
    private int lastRadarRowLeftClickEntityId = Integer.MIN_VALUE;
    private long lastRadarRowRightClickAt;
    private int lastRadarRowRightClickEntityId = Integer.MIN_VALUE;
    private boolean weaponDropdownOpen;
    private int assistedRadarLockEntityId = Integer.MIN_VALUE;
    private int assistedRadarLockTicks;
    private int pendingQuickFireEntityId = Integer.MIN_VALUE;
    private int pendingQuickFireTicks;
    private int pendingQuickFireLockStableTicks;
    private int pendingQuickFireRetryTicks;
    private boolean pendingQuickFireEverRadarLocked;
    private boolean pendingQuickFireEverWeaponLocked;
    private boolean pendingQuickFireEverSeekerReady;

    private static ResourceLocation mapIcon(String fileName) {
        return ResourceLocation.fromNamespaceAndPath("ywzj_rvp", ICON_TEXTURE_ROOT + fileName);
    }

    public RVP_TacticalMapScreen() {
        super(Component.translatable("gui.ywzj_rvp.tactical_map.title"));
    }

    @Override
    protected void init() {
        syncViewToPlayer(true);
        int boxWidth = 68;
        int boxHeight = 16;
        int compactToolbarHeight = 14;

        xBox = new EditBox(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.x"));
        yBox = new EditBox(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.y"));
        zBox = new EditBox(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.z"));
        xBox.setMaxLength(32);
        yBox.setMaxLength(32);
        zBox.setMaxLength(32);
        syncGpsFields();

        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);

        int buttonWidth = 72;
        bindButton = addRenderableWidget(Button.builder(Component.translatable("gui.ywzj_rvp.gps.bind"), b -> bindGps())
                .bounds(0, 0, buttonWidth, 16)
                .build());
        clearButton = addRenderableWidget(Button.builder(Component.translatable("gui.ywzj_rvp.gps.clear"), b -> clearGps())
                .bounds(0, 0, buttonWidth, 16)
                .build());
        centerButton = addRenderableWidget(new TerminalButton(
                0, 0, 44, compactToolbarHeight,
                Component.translatable("gui.ywzj_rvp.tactical_map.btn.center"),
                () -> syncViewToPlayer(true),
                () -> false,
                () -> 0xFFBAC7D5
        ));
        followButton = addRenderableWidget(new TerminalButton(
                0, 0, 56, compactToolbarHeight,
                followLabel(),
                () -> {
                    followPlayer = !followPlayer;
                    if (followPlayer) {
                        syncViewToPlayer(true);
                    }
                },
                () -> followPlayer,
                () -> OWN_ICON_COLOR
        ));
        weaponDropdownButton = addRenderableWidget(new TerminalButton(
                0, 0, 92, compactToolbarHeight,
                weaponDropdownLabel(),
                () -> weaponDropdownOpen = !weaponDropdownOpen,
                () -> weaponDropdownOpen,
                () -> 0xFFBAC7D5
        ));
        radarPanelButton = addRenderableWidget(new TerminalButton(
                0, 0, 54, compactToolbarHeight,
                radarPanelLabel(),
                () -> toggleSidebar(SidebarMode.RADAR),
                () -> sidebarVisible && sidebarMode == SidebarMode.RADAR,
                () -> 0xFF7BE0A5
        ));
        gpsPanelButton = addRenderableWidget(new TerminalButton(
                0, 0, 50, compactToolbarHeight,
                gpsPanelLabel(),
                () -> toggleSidebar(SidebarMode.GPS),
                () -> sidebarVisible && sidebarMode == SidebarMode.GPS,
                () -> GPS_ICON_COLOR
        ));
        gpsModeButton = addRenderableWidget(new TerminalButton(
                0, 0, 50, compactToolbarHeight,
                gpsModeLabel(),
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        RVP_ClientGPSUtil.toggleGpsMode(mc.player);
                    }
                },
                () -> RVP_ClientGPSState.isMultiMode(),
                () -> GPS_ICON_COLOR
        ));
        gpsClearAllButton = addRenderableWidget(new TerminalButton(
                0, 0, 44, compactToolbarHeight,
                Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_clear"),
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        RVP_ClientGPSUtil.clearGpsTarget(mc.player);
                    }
                },
                () -> false,
                () -> GPS_ICON_COLOR
        ));
        gpsQuickMarkButton = addRenderableWidget(new TerminalButton(
                0, 0, 64, compactToolbarHeight,
                gpsQuickMarkLabel(),
                () -> gpsQuickMarkMode = !gpsQuickMarkMode,
                () -> gpsQuickMarkMode,
                () -> GPS_ICON_COLOR
        ));
        radarHideFriendlyButton = addRenderableWidget(new TerminalButton(
                0, 0, 72, compactToolbarHeight,
                radarHideFriendlyLabel(),
                () -> hideFriendlyRadarRows = !hideFriendlyRadarRows,
                () -> hideFriendlyRadarRows,
                () -> 0xFF7BE0A5
        ));

        refreshLayout();
        refreshSidebarWidgets();
    }

    private Component followLabel() {
        return followPlayer
                ? Component.translatable("gui.ywzj_rvp.tactical_map.follow_on")
                : Component.translatable("gui.ywzj_rvp.tactical_map.follow_off");
    }

    private Component gpsPanelLabel() {
        return sidebarVisible && sidebarMode == SidebarMode.GPS
                ? Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_panel_hide")
                : Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_panel_show");
    }

    private Component radarPanelLabel() {
        return sidebarVisible && sidebarMode == SidebarMode.RADAR
                ? Component.translatable("gui.ywzj_rvp.tactical_map.btn.radar_panel_hide")
                : Component.translatable("gui.ywzj_rvp.tactical_map.btn.radar_panel_show");
    }

    private Component gpsModeLabel() {
        return RVP_ClientGPSState.isMultiMode()
                ? Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_mode_multi")
                : Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_mode_single");
    }

    private Component gpsQuickMarkLabel() {
        return gpsQuickMarkMode
                ? Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_quick_mark_on")
                : Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_quick_mark_off");
    }

    private Component radarHideFriendlyLabel() {
        return hideFriendlyRadarRows
                ? Component.translatable("gui.ywzj_rvp.tactical_map.btn.radar_hide_friendly_on")
                : Component.translatable("gui.ywzj_rvp.tactical_map.btn.radar_hide_friendly_off");
    }

    private Component weaponDropdownLabel() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.btn.weapon");
        }
        Optional<AbstractVehicleWeapon<?>> currentWeapon = weaponUnit.getCurrentWeapon();
        if (currentWeapon.isEmpty()) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.btn.weapon");
        }
        String name = currentWeapon.get().getDisplayName().getString();
        return Component.literal(StringUtils.abbreviate(name, 12));
    }

    private void toggleSidebar(SidebarMode mode) {
        if (sidebarVisible && sidebarMode == mode) {
            sidebarVisible = false;
            sidebarMode = SidebarMode.NONE;
        } else {
            sidebarVisible = true;
            sidebarMode = mode;
        }
        refreshLayout();
        refreshSidebarWidgets();
    }

    private int panelMargin() {
        return Mth.clamp(Math.min(this.width, this.height) / 56, 8, 14);
    }

    private int sidebarWidth() {
        return Mth.clamp((int) (this.width * 0.21), 196, 280);
    }

    private int toolbarButtonHeight() {
        return Mth.clamp(Math.min(this.width, this.height) / 52, 14, 16);
    }

    private void refreshLayout() {
        int margin = panelMargin();
        this.mapLeft = margin;
        this.mapTop = margin;
        this.mapBottom = this.height - margin;

        if (sidebarVisible) {
            this.sideRight = this.width - margin;
            this.sideLeft = Math.max(this.sideRight - sidebarWidth(), this.mapLeft + MAP_MIN_SIZE);
            this.mapRight = Math.max(this.sideLeft - 8, this.mapLeft + MAP_MIN_SIZE);
        } else {
            this.sideRight = this.width - margin;
            this.sideLeft = this.sideRight;
            this.mapRight = this.width - margin;
        }

        int buttonHeight = toolbarButtonHeight();
        int buttonGap = 3;
        int buttonY = mapTop + 6;
        int buttonX = mapRight - gpsPanelButton.getWidth();
        gpsPanelButton.setPosition(buttonX, buttonY);
        radarPanelButton.setPosition(buttonX - buttonGap - radarPanelButton.getWidth(), buttonY);
        followButton.setPosition(radarPanelButton.getX() - buttonGap - followButton.getWidth(), buttonY);
        centerButton.setPosition(followButton.getX() - buttonGap - centerButton.getWidth(), buttonY);
        gpsModeButton.setPosition(centerButton.getX() - buttonGap - gpsModeButton.getWidth(), buttonY);
        gpsQuickMarkButton.setPosition(gpsModeButton.getX() - buttonGap - gpsQuickMarkButton.getWidth(), buttonY);
        gpsClearAllButton.setPosition(gpsQuickMarkButton.getX() - buttonGap - gpsClearAllButton.getWidth(), buttonY);
        int weaponButtonY = mapTop + (gpsQuickMarkMode ? 44 : 32);
        weaponDropdownButton.setPosition(mapLeft + 8, weaponButtonY);

        if (xBox == null) {
            return;
        }

        int contentX = sideLeft + 10;
        int columnsWidth = sideRight - contentX - 10;
        int gap = 4;
        int rowY = mapTop + 74;
        int fieldWidth = Math.max(56, Math.min(68, (columnsWidth - gap) / 2));
        int rightColumnX = contentX + fieldWidth + gap;
        xBox.setPosition(contentX, rowY);
        xBox.setWidth(fieldWidth);
        yBox.setPosition(rightColumnX, rowY);
        yBox.setWidth(fieldWidth);
        zBox.setPosition(contentX, rowY + xBox.getHeight() + gap);
        zBox.setWidth(fieldWidth);

        int actionY = zBox.getY() + zBox.getHeight() + 5;
        int buttonWidth = Math.max(52, fieldWidth - 6);
        bindButton.setPosition(contentX + (fieldWidth - buttonWidth) / 2, actionY);
        bindButton.setWidth(buttonWidth);
        clearButton.setPosition(rightColumnX + (fieldWidth - buttonWidth) / 2, actionY);
        clearButton.setWidth(buttonWidth);
        radarHideFriendlyButton.setPosition(contentX, mapTop + 26);
        radarHideFriendlyButton.setWidth(Math.min(94, Math.max(72, columnsWidth)));
    }

    private void refreshSidebarWidgets() {
        boolean showGpsSidebar = sidebarVisible && sidebarMode == SidebarMode.GPS;
        boolean showRadarSidebar = sidebarVisible && sidebarMode == SidebarMode.RADAR;
        xBox.visible = showGpsSidebar;
        yBox.visible = showGpsSidebar;
        zBox.visible = showGpsSidebar;
        xBox.setEditable(showGpsSidebar);
        yBox.setEditable(showGpsSidebar);
        zBox.setEditable(showGpsSidebar);
        bindButton.visible = showGpsSidebar;
        bindButton.active = showGpsSidebar;
        clearButton.visible = showGpsSidebar;
        clearButton.active = showGpsSidebar;
        gpsPanelButton.setMessage(gpsPanelLabel());
        radarPanelButton.setMessage(radarPanelLabel());
        followButton.setMessage(followLabel());
        weaponDropdownButton.setMessage(weaponDropdownLabel());
        gpsModeButton.setMessage(gpsModeLabel());
        gpsQuickMarkButton.setMessage(gpsQuickMarkLabel());
        radarHideFriendlyButton.setMessage(radarHideFriendlyLabel());
        gpsModeButton.visible = true;
        gpsModeButton.active = true;
        gpsClearAllButton.visible = true;
        gpsClearAllButton.active = true;
        gpsQuickMarkButton.visible = true;
        gpsQuickMarkButton.active = true;
        radarHideFriendlyButton.visible = showRadarSidebar;
        radarHideFriendlyButton.active = showRadarSidebar;
    }

    private void openSidebar(SidebarMode mode) {
        sidebarVisible = true;
        sidebarMode = mode;
        refreshLayout();
        refreshSidebarWidgets();
    }

    private void closeMapContextMenu() {
        mapContextMenuVisible = false;
        mapContextTarget = null;
    }

    private void closeEntityContextMenu() {
        entityContextMenuVisible = false;
        entityContextTarget = null;
    }

    private void closeTransientMenus() {
        closeMapContextMenu();
        closeEntityContextMenu();
    }

    private void syncViewToPlayer(boolean force) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (force || followPlayer) {
            viewWorldX = mc.player.getX();
            viewWorldZ = mc.player.getZ();
        }
    }

    private void syncGpsFields() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 pos = null;
        if (RVP_ClientGPSState.isActive()) {
            pos = RVP_ClientGPSState.getPos();
        } else if (mc.player != null) {
            pos = mc.player.position();
        }
        if (pos != null) {
            setGpsFields(pos);
        }
    }

    private void setGpsFields(Vec3 pos) {
        if (xBox != null) {
            xBox.setValue(String.format("%.2f", pos.x));
            yBox.setValue(String.format("%.2f", pos.y));
            zBox.setValue(String.format("%.2f", pos.z));
        }
    }

    private void bindGps() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            onClose();
            return;
        }
        try {
            double x = Double.parseDouble(xBox.getValue().trim());
            double y = Double.parseDouble(yBox.getValue().trim());
            double z = Double.parseDouble(zBox.getValue().trim());
            ResourceLocation dim = mc.player.level().dimension().location();
            Vec3 pos = new Vec3(x, y, z);
            RVP_ClientGPSUtil.setGpsTarget(mc.player, dim, pos);
        } catch (NumberFormatException e) {
            mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.gps.invalid_coords"), true);
        }
    }

    private void clearGps() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        RVP_ClientGPSUtil.clearGpsTarget(mc.player);
        setGpsFields(mc.player.position());
    }

    @Override
    public void tick() {
        super.tick();
        if (followPlayer && !draggingMap) {
            syncViewToPlayer(false);
        }
        tickPendingRadarActions();
        tickImpactCrosses();
        if (followButton != null) followButton.setMessage(followLabel());
        if (weaponDropdownButton != null) weaponDropdownButton.setMessage(weaponDropdownLabel());
        if (gpsPanelButton != null) gpsPanelButton.setMessage(gpsPanelLabel());
        if (radarPanelButton != null) radarPanelButton.setMessage(radarPanelLabel());
        if (gpsModeButton != null) gpsModeButton.setMessage(gpsModeLabel());
        if (gpsQuickMarkButton != null) gpsQuickMarkButton.setMessage(gpsQuickMarkLabel());
        if (radarHideFriendlyButton != null) radarHideFriendlyButton.setMessage(radarHideFriendlyLabel());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isOverToolbarButton(mouseX, mouseY)) {
            closeTransientMenus();
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (handleWeaponDropdownClick(mouseX, mouseY, button)) {
            closeTransientMenus();
            return true;
        }
        if (handleRadarPanelClick(mouseX, mouseY, button)) {
            closeTransientMenus();
            return true;
        }
        if (button == 1 && isOverMap(mouseX, mouseY)) {
            MarkerHit doubleRightMarker = hitTestMarker(mouseX, mouseY);
            if (isMarkerDoubleClick(doubleRightMarker, false)) {
                closeTransientMenus();
                if (doubleRightMarker != null) {
                    selectMarker(doubleRightMarker);
                }
                return doubleRightMarker != null && tryQuickFireOnTarget(doubleRightMarker.entity);
            }
        }
        if (entityContextMenuVisible) {
            if (handleEntityContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
            closeEntityContextMenu();
            if (button != 1 || !isOverMap(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        if (mapContextMenuVisible) {
            if (handleMapContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
            closeMapContextMenu();
            if (button != 1 || !isOverMap(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        if (isOverMap(mouseX, mouseY)) {
            if (button == 0) {
                closeTransientMenus();
                MarkerHit markerHit = hitTestMarker(mouseX, mouseY);
                if (markerHit != null) {
                    selectMarker(markerHit);
                    if (isMarkerDoubleClick(markerHit, true) && markerHit.entity != null) {
                        return tryLockRadarTarget(markerHit.entity);
                    }
                    return true;
                }
                rememberMarkerClick(markerHit, true);
                draggingMap = true;
                followPlayer = false;
            } else if (button == 1) {
                if (gpsQuickMarkMode) {
                    closeTransientMenus();
                    MarkerHit markerHit = hitTestMarker(mouseX, mouseY);
                    Vec3 picked = markerHit != null ? markerHit.focusPos : pickMapPoint(mouseX, mouseY);
                    if (picked != null) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            ResourceLocation dim = mc.player.level().dimension().location();
                            setGpsFields(picked);
                            RVP_ClientGPSUtil.setGpsTarget(mc.player, dim, picked);
                        }
                    }
                    return true;
                }
                MarkerHit markerHit = hitTestMarker(mouseX, mouseY);
                if (markerHit != null) {
                    selectMarker(markerHit);
                    rememberMarkerClick(markerHit, false);
                    if (markerHit.entity == null) {
                        return true;
                    }
                    openEntityContextMenu(mouseX, mouseY, markerHit);
                    return true;
                }
                Vec3 picked = pickMapPoint(mouseX, mouseY);
                if (picked != null) {
                    openMapContextMenu(mouseX, mouseY, picked);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            draggingMap = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingMap && button == 0 && isOverMap(mouseX, mouseY)) {
            closeTransientMenus();
            viewWorldX -= dragX * blocksPerPixel;
            viewWorldZ -= dragY * blocksPerPixel;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (sidebarVisible && sidebarMode == SidebarMode.RADAR && isOverRadarTable(mouseX, mouseY)) {
            int maxScroll = Math.max(0, radarVisibleRowCount() < 1 ? 0 : radarContactRows.size() - radarVisibleRowCount());
            if (delta > 0) {
                radarRowScroll = Math.max(0, radarRowScroll - 1);
            } else if (delta < 0) {
                radarRowScroll = Math.min(maxScroll, radarRowScroll + 1);
            }
            return true;
        }
        if (!isOverMap(mouseX, mouseY)) {
            weaponDropdownOpen = false;
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        closeTransientMenus();
        weaponDropdownOpen = false;
        double worldXBefore = screenToWorldX(mouseX);
        double worldZBefore = screenToWorldZ(mouseY);
        if (delta > 0) {
            blocksPerPixel = Math.max(0.15, blocksPerPixel / 1.2);
        } else if (delta < 0) {
            blocksPerPixel = Math.min(32.0, blocksPerPixel * 1.2);
        }
        viewWorldX += worldXBefore - screenToWorldX(mouseX);
        viewWorldZ += worldZBefore - screenToWorldZ(mouseY);
        followPlayer = false;
        return true;
    }

    private boolean isOverMap(double mouseX, double mouseY) {
        return mouseX >= mapLeft && mouseX <= mapRight && mouseY >= mapTop && mouseY <= mapBottom;
    }

    private double screenToWorldX(double screenX) {
        return viewWorldX + (screenX - (mapLeft + mapRight) * 0.5) * blocksPerPixel;
    }

    private double screenToWorldZ(double screenY) {
        return viewWorldZ + (screenY - (mapTop + mapBottom) * 0.5) * blocksPerPixel;
    }

    private double worldToScreenX(double worldX) {
        return (mapLeft + mapRight) * 0.5 + (worldX - viewWorldX) / blocksPerPixel;
    }

    private double worldToScreenY(double worldZ) {
        return (mapTop + mapBottom) * 0.5 + (worldZ - viewWorldZ) / blocksPerPixel;
    }

    private Vec3 pickMapPoint(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        int x = Mth.floor(screenToWorldX(mouseX));
        int z = Mth.floor(screenToWorldZ(mouseY));
        int y = resolveMapHeight(x, z);
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private int resolveMapHeight(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return -64;
        }
        int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y > mc.level.getMinBuildHeight()) {
            return y;
        }
        Integer cachedHeight = RVP_TacticalMapCache.getCachedHeight(x, z);
        return cachedHeight != null ? cachedHeight : y;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        refreshLayout();
        renderBackground(guiGraphics);
        drawFrame(guiGraphics);
        renderMap(guiGraphics, mouseX, mouseY, partialTick);
        if (sidebarVisible) {
            renderSidePanel(guiGraphics);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (weaponDropdownOpen) {
            renderWeaponDropdown(guiGraphics, mouseX, mouseY);
        }
        if (entityContextMenuVisible && entityContextTarget != null) {
            if (entityContextTarget.entity != null && entityContextTarget.entity.isRemoved()) {
                closeEntityContextMenu();
            } else {
                renderEntityContextMenu(guiGraphics, mouseX, mouseY);
            }
        }
        if (mapContextMenuVisible && mapContextTarget != null) {
            renderMapContextMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderWeaponDropdown(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null || weaponUnit.weapons.isEmpty()) {
            return;
        }
        int x = weaponDropdownX();
        int y = weaponDropdownY();
        int width = weaponDropdownWidth();
        int itemHeight = weaponDropdownItemHeight();
        int height = weaponUnit.weapons.size() * itemHeight + 6;
        guiGraphics.fill(x, y, x + width, y + height, 0xEE151B23);
        guiGraphics.fill(x, y, x + width, y + 1, 0xFF6A7E95);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0xFF6A7E95);
        guiGraphics.fill(x, y, x + 1, y + height, 0xFF6A7E95);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0xFF6A7E95);
        for (int i = 0; i < weaponUnit.weapons.size(); i++) {
            int rowY = y + 3 + i * itemHeight;
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + itemHeight;
            boolean selected = i == weaponUnit.getCurrentWeaponIndex();
            if (selected) {
                guiGraphics.fill(x + 1, rowY, x + width - 1, rowY + itemHeight, 0x55374C61);
            } else if (hovered) {
                guiGraphics.fill(x + 1, rowY, x + width - 1, rowY + itemHeight, 0x66405A7A);
            }
            String label = (i + 1) + ". " + weaponUnit.weapons.get(i).getDisplayName().getString();
            guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(label, width - 8),
                    x + 4, rowY + 3, selected ? 0xFFFFFFFF : 0xFFE7EEF7, false);
        }
    }

    private void drawFrame(GuiGraphics guiGraphics) {
        guiGraphics.fill(mapLeft, mapTop, mapRight, mapBottom, 0xCC10151C);
        guiGraphics.fill(mapLeft - 1, mapTop - 1, mapRight + 1, mapTop, 0xFF4E5E73);
        guiGraphics.fill(mapLeft - 1, mapBottom, mapRight + 1, mapBottom + 1, 0xFF4E5E73);
        guiGraphics.fill(mapLeft - 1, mapTop, mapLeft, mapBottom, 0xFF4E5E73);
        guiGraphics.fill(mapRight, mapTop, mapRight + 1, mapBottom, 0xFF4E5E73);

        if (sidebarVisible) {
            guiGraphics.fill(sideLeft, mapTop, sideRight, mapBottom, 0xD9181D24);
            guiGraphics.fill(sideLeft - 1, mapTop - 1, sideRight + 1, mapTop, 0xFF4E5E73);
            guiGraphics.fill(sideLeft - 1, mapBottom, sideRight + 1, mapBottom + 1, 0xFF4E5E73);
            guiGraphics.fill(sideLeft - 1, mapTop, sideLeft, mapBottom, 0xFF4E5E73);
            guiGraphics.fill(sideRight, mapTop, sideRight + 1, mapBottom, 0xFF4E5E73);
        }
    }

    private void renderMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderMap(guiGraphics, mouseX, mouseY, 0.0f);
    }

    private void renderMap(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        markerHits.clear();
        beginTrackedAmmoFrame();
        refreshTacticalRadarContext();
        renderTerrainTiles(guiGraphics);
        renderGrid(guiGraphics);
        renderRadarCoverage(guiGraphics, partialTick);
        renderGpsAmmoLinks(guiGraphics);
        renderArhAmmoLinks(guiGraphics);
        renderLocalProjectiles(guiGraphics);
        renderRemoteAmmoCache(guiGraphics);
        finishTrackedAmmoFrame();
        renderLocalVehicles(guiGraphics);
        renderRemoteEntities(guiGraphics);
        renderVehicleAndPlayer(guiGraphics);
        renderGpsMarker(guiGraphics);
        renderRecentImpactCrosses(guiGraphics);
        syncSelectedMarkerHit();
        renderSelectedMarkerHighlight(guiGraphics);
        renderMapHud(guiGraphics, mouseX, mouseY);
    }

    private void renderLocalProjectiles(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        AbstractVehicle playerVehicle = mc.player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (!shouldRenderProjectileMarker(bullet)) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(mc.player, playerVehicle, bullet)) {
                continue;
            }
            trackAmmoPosition(bullet.getId(), bullet.position(), resolveProjectileKind(bullet));
            drawMissileMarker(guiGraphics, bullet, relationColorForBullet(mc.player, playerVehicle, bullet));
            registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 8, getMarkerTargetPos(entity));
        }
    }

    private void renderGpsAmmoLinks(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        AbstractVehicle playerVehicle = mc.player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        Set<Integer> renderedIds = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (!renderGpsAmmoLink(guiGraphics, mc.player, playerVehicle, bullet)) {
                continue;
            }
            renderedIds.add(entity.getId());
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (!(serverEntity.entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (renderedIds.contains(bullet.getId())) {
                continue;
            }
            if (!renderGpsAmmoLink(guiGraphics, mc.player, playerVehicle, bullet)) {
                continue;
            }
            renderedIds.add(bullet.getId());
        }
        ResourceLocation dimension = mc.level.dimension().location();
        for (S2CRemoteAmmoSnapshot.Entry entry : RVP_ClientRemoteAmmoState.getEntries(dimension)) {
            if (renderedIds.contains(entry.entityId())) {
                continue;
            }
            if (!renderRemoteGpsAmmoLink(guiGraphics, entry)) {
                continue;
            }
            renderedIds.add(entry.entityId());
        }
    }

    private boolean renderGpsAmmoLink(GuiGraphics guiGraphics, Entity player, AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
        if (!isOwnAmmo(player, playerVehicle, bullet)) {
            return false;
        }
        if (!isGpsAmmo(bullet)) {
            return false;
        }
        Vec3 target = bullet.getTargetPos();
        if (target == null) {
            target = bullet.getLastGuidancePos();
        }
        if (target == null) {
            return false;
        }
        int lineColor = withAlpha(relationColorForBullet(player instanceof LocalPlayer localPlayer ? localPlayer : null, playerVehicle, bullet), 0xC0);
        drawDashedWorldLine(guiGraphics,
                bullet.getX(), bullet.getZ(),
                target.x, target.z,
                4, 4,
                lineColor);
        return true;
    }

    private boolean renderRemoteGpsAmmoLink(GuiGraphics guiGraphics, S2CRemoteAmmoSnapshot.Entry entry) {
        if (entry.affiliation() != S2CRemoteAmmoSnapshot.Affiliation.OWN || !entry.gpsCapable() || entry.guidancePos() == null) {
            return false;
        }
        int lineColor = withAlpha(relationColorForAffiliation(entry.affiliation()), 0xC0);
        drawDashedWorldLine(guiGraphics,
                entry.x(), entry.z(),
                entry.guidancePos().x, entry.guidancePos().z,
                4, 4,
                lineColor);
        return true;
    }

    private void renderArhAmmoLinks(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        AbstractVehicle playerVehicle = mc.player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        Set<Integer> renderedIds = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (!renderArhAmmoLink(guiGraphics, mc.player, playerVehicle, bullet)) {
                continue;
            }
            renderedIds.add(entity.getId());
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (!(serverEntity.entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (renderedIds.contains(bullet.getId())) {
                continue;
            }
            if (!renderArhAmmoLink(guiGraphics, mc.player, playerVehicle, bullet)) {
                continue;
            }
            renderedIds.add(bullet.getId());
        }
    }

    private boolean renderArhAmmoLink(GuiGraphics guiGraphics, Entity player, @Nullable AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
        if (!isOwnAmmo(player, playerVehicle, bullet) || !isArhAmmo(bullet)) {
            return false;
        }
        Vec3 target = resolveArhAmmoTargetPos(bullet);
        if (target == null) {
            return false;
        }
        int baseColor = relationColorForBullet(player instanceof LocalPlayer localPlayer ? localPlayer : null, playerVehicle, bullet);
        if (bullet.isActiveRadarCatch()) {
            drawWorldLine(guiGraphics,
                    bullet.getX(), bullet.getZ(),
                    target.x, target.z,
                    withAlpha(baseColor, 0xD8));
        } else {
            drawDashedWorldLine(guiGraphics,
                    bullet.getX(), bullet.getZ(),
                    target.x, target.z,
                    3, 3,
                    withAlpha(baseColor, 0xA8));
        }
        return true;
    }

    private boolean isOwnAmmo(Entity player, AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
        if (bullet.getOwner() == player) {
            return true;
        }
        return playerVehicle != null && bullet.getShooterVehicle() == playerVehicle;
    }

    private boolean isGpsAmmo(RVP_BaseBullet bullet) {
        ResourceLocation weaponId = bullet.getWeaponId();
        if (weaponId == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof RVP_WeaponData weaponData
                        && weaponData.usesGuidanceType(RVP_EnumGuidanceType.GPS))
                .orElse(false);
    }

    private boolean isArhAmmo(RVP_BaseBullet bullet) {
        ResourceLocation weaponId = bullet.getWeaponId();
        if (weaponId == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof RVP_WeaponData weaponData
                        && weaponData.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                        && weaponData.isActiveRadar())
                .orElse(false);
    }

    @Nullable
    private Vec3 resolveArhAmmoTargetPos(RVP_BaseBullet bullet) {
        Entity targetEntity = bullet.getTargetEntity();
        if (targetEntity != null && targetEntity.isAlive()) {
            return targetEntity.getBoundingBox().getCenter();
        }
        Vec3 targetPos = bullet.getTargetPos();
        if (targetPos != null) {
            return targetPos;
        }
        return bullet.getLastGuidancePos();
    }

    private void renderTerrainTiles(GuiGraphics guiGraphics) {
        double minX = screenToWorldX(mapLeft);
        double maxX = screenToWorldX(mapRight);
        double minZ = screenToWorldZ(mapTop);
        double maxZ = screenToWorldZ(mapBottom);

        guiGraphics.enableScissor(mapLeft, mapTop, mapRight, mapBottom);
        double mapCenterX = (mapLeft + mapRight) * 0.5;
        double mapCenterY = (mapTop + mapBottom) * 0.5;
        float pixelsPerBlock = (float) (1.0 / blocksPerPixel);
        float originScreenX = (float) (mapCenterX - viewWorldX / blocksPerPixel);
        float originScreenY = (float) (mapCenterY - viewWorldZ / blocksPerPixel);

        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(originScreenX, originScreenY, 0.0f);
        pose.scale(pixelsPerBlock, pixelsPerBlock, 1.0f);

        for (RVP_TacticalMapCache.TilePos tilePos : RVP_TacticalMapCache.getVisibleTiles(minX, minZ, maxX, maxZ)) {
            ResourceLocation texture = RVP_TacticalMapCache.getTileTexture(tilePos);
            if (texture == null) {
                continue;
            }

            int worldX = tilePos.rx() * RVP_TacticalMapCache.TILE_SIZE;
            int worldZ = tilePos.rz() * RVP_TacticalMapCache.TILE_SIZE;
            int worldX2 = worldX + RVP_TacticalMapCache.TILE_SIZE;
            int worldZ2 = worldZ + RVP_TacticalMapCache.TILE_SIZE;
            if (worldX2 < minX || worldX > maxX || worldZ2 < minZ || worldZ > maxZ) {
                continue;
            }

            guiGraphics.blit(texture,
                    worldX, worldZ,
                    RVP_TacticalMapCache.TILE_SIZE, RVP_TacticalMapCache.TILE_SIZE,
                    0.0f, 0.0f,
                    RVP_TacticalMapCache.TILE_SIZE, RVP_TacticalMapCache.TILE_SIZE,
                    RVP_TacticalMapCache.TILE_SIZE, RVP_TacticalMapCache.TILE_SIZE);
        }
        pose.popPose();
        guiGraphics.disableScissor();
    }

    private void renderGrid(GuiGraphics guiGraphics) {
        double[] steps = new double[] {25, 50, 100, 200, 500, 1000};
        double step = 100;
        for (double candidate : steps) {
            if (candidate / blocksPerPixel >= 28) {
                step = candidate;
                break;
            }
        }
        double minX = screenToWorldX(mapLeft);
        double maxX = screenToWorldX(mapRight);
        double minZ = screenToWorldZ(mapTop);
        double maxZ = screenToWorldZ(mapBottom);

        int gridColor = 0x553E4A59;
        int axisColor = 0x8891A7C0;

        double startX = Math.floor(minX / step) * step;
        for (double x = startX; x <= maxX; x += step) {
            int sx = Mth.floor(worldToScreenX(x));
            guiGraphics.vLine(sx, mapTop, mapBottom, x == 0 ? axisColor : gridColor);
        }
        double startZ = Math.floor(minZ / step) * step;
        for (double z = startZ; z <= maxZ; z += step) {
            int sy = Mth.floor(worldToScreenY(z));
            guiGraphics.hLine(mapLeft, mapRight, sy, z == 0 ? axisColor : gridColor);
        }
    }

    private void renderRemoteEntities(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        AbstractVehicle playerVehicle = player != null && player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            Entity entity = serverEntity.entity;
            if (entity == null) {
                continue;
            }
            if (mc.level != null && mc.level.getEntity(entity.getId()) != null) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(player, playerVehicle, entity)) {
                continue;
            }
            if (entity instanceof AbstractVehicle) {
                if (playerVehicle != null && isDuplicateOfPlayerVehicle(playerVehicle, (AbstractVehicle) entity)) {
                    continue;
                }
                drawVehicleMarker(guiGraphics, (AbstractVehicle) entity, relationColorForVehicle(player, (AbstractVehicle) entity));
                registerMarkerHit(entity, MarkerKind.REMOTE_VEHICLE, entity.getX(), entity.getY(), entity.getZ(), 8, null);
            } else if (entity instanceof RVP_BaseBullet bullet) {
                if (!shouldRenderProjectileMarker(bullet)) {
                    continue;
                }
                drawMissileMarker(guiGraphics, bullet, relationColorForBullet(player, playerVehicle, bullet));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
            } else if (entity instanceof MissileEntity) {
                drawMissileMarker(guiGraphics, entity, relationColorForEntity(player, entity));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
            } else if (entity instanceof Player remotePlayer && !(remotePlayer.getVehicle() instanceof AbstractVehicle)) {
                drawPlayerMarker(guiGraphics, remotePlayer, relationColorForPlayer(player, remotePlayer));
                registerMarkerHit(entity, MarkerKind.PLAYER, entity.getX(), entity.getY(), entity.getZ(), 7, null);
            } else {
                drawContactMarker(guiGraphics, entity, relationColorForEntity(player, entity));
                registerMarkerHit(entity, MarkerKind.CONTACT, entity.getX(), entity.getY(), entity.getZ(), 7, null);
            }
        }
    }

    private void renderLocalVehicles(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            return;
        }
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (vehicle == playerVehicle) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(player, playerVehicle, vehicle)) {
                continue;
            }
            drawVehicleMarker(guiGraphics, vehicle, relationColorForVehicle(player, vehicle));
            registerMarkerHit(vehicle, MarkerKind.REMOTE_VEHICLE, vehicle.getX(), vehicle.getY(), vehicle.getZ(), 7, null);
        }
    }

    private void renderRemoteAmmoCache(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ResourceLocation dimension = mc.level.dimension().location();
        for (S2CRemoteAmmoSnapshot.Entry entry : RVP_ClientRemoteAmmoState.getEntries(dimension)) {
            if (hasTrackedAmmoEntity(mc, entry.entityId())) {
                continue;
            }
            if (!shouldShowRemoteAmmoOnTacticalMap(entry)) {
                continue;
            }
            trackAmmoPosition(entry.entityId(), new Vec3(entry.x(), entry.y(), entry.z()), entry.weaponKind());
            drawRemoteAmmoMarker(guiGraphics, entry);
            registerRemoteAmmoHit(entry);
        }
    }

    private void refreshTacticalRadarContext() {
        tacticalRadarUnits.clear();
        tacticalRadarVisibleIds.clear();
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (radarUnit == null || radarUnit.isUiHide() || !radarUnit.isOn()) {
                continue;
            }
            tacticalRadarUnits.add(radarUnit);
            radarUnit.getDetectedEntities().forEach((entityId, detectedObject) -> {
                tacticalRadarVisibleIds.add(entityId);
                if (detectedObject != null && detectedObject.entity != null) {
                    tacticalRadarVisibleIds.add(detectedObject.entity.getId());
                }
            });
            Entity lockedEntity = radarUnit.getLockedEntity();
            if (lockedEntity != null) {
                tacticalRadarVisibleIds.add(lockedEntity.getId());
            }
        }
    }

    private void renderRadarCoverage(GuiGraphics guiGraphics, float partialTick) {
        if (tacticalRadarUnits.isEmpty()) {
            return;
        }
        guiGraphics.enableScissor(mapLeft, mapTop, mapRight, mapBottom);
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            float radarYaw = interpolatedRadarMapYaw(radarUnit, partialTick);
            double centerWorldX = radarUnit.worldRadarPosition().x;
            double centerWorldZ = radarUnit.worldRadarPosition().z;
            float radiusPixels = (float) Math.max(1.0, radarUnit.getMaxScanDistance() / blocksPerPixel);
            float yRotMin = radarUnit.getYRotMin();
            float yRotMax = radarUnit.getYRotMax();
            if (yRotMax - yRotMin >= 360.0f) {
                yRotMin = 0.0f;
                yRotMax = 360.0f;
            }
            int sectorColor = Color.RADAR_SECTOR;
            int lineColor = Color.GREEN;
            int segments = Math.max(16, Mth.ceil(Math.abs(yRotMax - yRotMin) / 8.0f));
            drawRadarSectorOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels, radarYaw, yRotMin, yRotMax, segments, sectorColor);

            Entity lockedEntity = radarUnit.getLockedEntity();
            if (lockedEntity == null) {
                float scanAngle = computeRadarScanAngle(radarUnit, partialTick, radarUnit.yRotO, radarUnit.getYRot());
                drawRadarScanLineOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels, radarYaw, scanAngle, 1.0f, lineColor);
            } else {
                drawWorldLine(guiGraphics, centerWorldX, centerWorldZ, lockedEntity.getX(), lockedEntity.getZ(), lineColor);
            }
        }
        guiGraphics.disableScissor();
    }

    private boolean hasTrackedAmmoEntity(Minecraft mc, int entityId) {
        if (mc.level != null && mc.level.getEntity(entityId) != null) {
            return true;
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : LocalVehiclePlayer.instance.serverEntities.values()) {
            if (serverEntity.entity != null && serverEntity.entity.getId() == entityId) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateOfPlayerVehicle(AbstractVehicle playerVehicle, AbstractVehicle remoteVehicle) {
        if (playerVehicle == remoteVehicle) {
            return true;
        }
        if (playerVehicle.position().distanceToSqr(remoteVehicle.position()) > 4.0) {
            return false;
        }
        ResourceLocation playerVehicleId = playerVehicle.getVehicleId();
        ResourceLocation remoteVehicleId = remoteVehicle.getVehicleId();
        return playerVehicleId != null && playerVehicleId.equals(remoteVehicleId);
    }

    private void drawRemoteAmmoMarker(GuiGraphics guiGraphics, S2CRemoteAmmoSnapshot.Entry entry) {
        drawMarkerIcon(guiGraphics,
                resolveRemoteAmmoIcon(entry),
                entry.x(),
                entry.z(),
                entry.yaw(),
                10,
                relationColorForAffiliation(entry.affiliation()),
                true);
    }

    private void renderVehicleAndPlayer(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            drawVehicleMarker(guiGraphics, vehicle, OWN_ICON_COLOR);
            registerMarkerHit(vehicle, MarkerKind.OWN_VEHICLE, vehicle.getX(), vehicle.getY(), vehicle.getZ(), 7, null);
        }
        for (Player player : mc.level.players()) {
            if (player.isSpectator() || player.getVehicle() instanceof AbstractVehicle) {
                continue;
            }
            drawPlayerMarker(guiGraphics, player, relationColorForPlayer(mc.player, player));
            registerMarkerHit(player, MarkerKind.PLAYER, player.getX(), player.getY(), player.getZ(), 6, null);
        }
    }

    private void renderGpsMarker(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ResourceLocation dim = mc.player.level().dimension().location();
        List<RVP_ClientGPSState.Point> points = RVP_ClientGPSState.getPoints();
        if (points.isEmpty()) {
            return;
        }
        int activeGlobalIndex = Math.max(0, Math.min(RVP_ClientGPSState.getPointCount() - 1, RVP_ClientGPSState.getNextIndex()));
        for (int i = 0; i < points.size(); i++) {
            RVP_ClientGPSState.Point point = points.get(i);
            if (!dim.equals(point.dimension())) {
                continue;
            }
            Vec3 gps = point.pos();
            int sx = Mth.floor(worldToScreenX(gps.x));
            int sy = Mth.floor(worldToScreenY(gps.z));
            if (sx < mapLeft || sx > mapRight || sy < mapTop || sy > mapBottom) {
                continue;
            }
            if (i == activeGlobalIndex) {
                drawTargetMarker(guiGraphics, sx, sy, GPS_ICON_COLOR);
            } else {
                drawScreenIcon(guiGraphics, GPS_ICON, sx, sy, 12, GPS_ICON_COLOR, false, 0.0f, 1.0f);
            }
            String label = "GPS " + (i + 1);
            int w = this.font.width(label);
            int tx = sx - w / 2;
            int ty = sy + 11;
            if (ty + 8 <= mapBottom - 2) {
                guiGraphics.drawString(this.font, label, tx, ty, GPS_ICON_COLOR, false);
            }
        }
    }

    private void renderMapHud(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, mapLeft + 8, mapTop + 8, 0xFFFFFFFF, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.hint"), mapLeft + 8, mapTop + 20, 0xFF9CA9B8, false);
        if (gpsQuickMarkMode) {
            guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.gps_quick_mark_status"), mapLeft + 8, mapTop + 32, GPS_ICON_COLOR, false);
        }
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ywzj_rvp.tactical_map.scale", String.format("%.2f", blocksPerPixel)),
                mapLeft + 8, mapBottom - 22, 0xFFBAC7D5, false);

        if (isOverMap(mouseX, mouseY)) {
            Vec3 picked = pickMapPoint(mouseX, mouseY);
            if (picked != null) {
                guiGraphics.drawString(this.font,
                        Component.translatable("gui.ywzj_rvp.tactical_map.cursor",
                                Mth.floor(picked.x), Mth.floor(picked.y), Mth.floor(picked.z)),
                        mapLeft + 8, mapBottom - 10, 0xFFFFFFFF, false);
            }
        }
        renderSelectedMarkerInfo(guiGraphics);
    }

    private void renderSelectedMarkerInfo(GuiGraphics guiGraphics) {
        if (selectedMarkerHit == null) {
            return;
        }
        String tag = "[TGT]";
        String body = buildSelectionBarBody();
        String extra = buildSelectionBarExtra();
        int maxPanelWidth = Math.max(156, Math.min(mapRight - mapLeft - 8, 300));
        int bodyWidthMeasure = Math.max(this.font.width(body), this.font.width(extra));
        int panelWidth = Mth.clamp(this.font.width(tag) + bodyWidthMeasure + 14, 156, maxPanelWidth);
        boolean multiLine = extra != null && !extra.isEmpty();
        int panelHeight = multiLine ? 22 : 13;
        int preferredX = selectedMarkerHit.screenX + selectedMarkerHit.radius + 8;
        int panelX = Mth.clamp(preferredX, mapLeft + 4, Math.max(mapLeft + 4, mapRight - panelWidth - 4));
        int aboveY = selectedMarkerHit.screenY - panelHeight - 8;
        int belowY = selectedMarkerHit.screenY + selectedMarkerHit.radius + 6;
        int panelY = aboveY >= mapTop + 4 ? aboveY : belowY;
        panelY = Mth.clamp(panelY, mapTop + 4, Math.max(mapTop + 4, mapBottom - panelHeight - 4));
        int borderColor = withAlpha(selectedMarkerHit.accentColor, 0xE8);
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD8111720);
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, borderColor);
        guiGraphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x8842556E);
        guiGraphics.fill(panelX, panelY, panelX + 2, panelY + panelHeight, borderColor);

        int textX = panelX + 4;
        int textY = panelY + 3;
        guiGraphics.drawString(this.font, tag, textX, textY, borderColor, false);
        int bodyX = textX + this.font.width(tag) + 5;
        int bodyWidth = panelWidth - (bodyX - panelX) - 4;
        guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(body, bodyWidth), bodyX, textY, 0xFFEAF2FB, false);
        if (multiLine) {
            guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(extra, panelWidth - 8), textX, textY + 10, 0xFFEAF2FB, false);
        }
    }

    private String buildSelectionBarBody() {
        if (selectedMarkerHit == null) {
            return "";
        }
        String name = selectedMarkerHit.displayName.getString();
        StringBuilder builder = new StringBuilder();
        builder.append(name);
        builder.append("  |  V ");
        builder.append(Math.round(selectedMarkerHit.speedKmh));
        builder.append(" km/h");
        return builder.toString();
    }

    private String buildSelectionBarExtra() {
        if (selectedMarkerHit == null) {
            return "";
        }
        if (!selectedMarkerHit.gpsAmmo || selectedMarkerHit.targetPos == null) {
            return "";
        }
        return "GPS  " + Mth.floor(selectedMarkerHit.targetPos.x) + " " + Mth.floor(selectedMarkerHit.targetPos.y) + " " + Mth.floor(selectedMarkerHit.targetPos.z);
    }

    private static final class TerminalButton extends AbstractWidget {
        private final Runnable onPress;
        private final java.util.function.BooleanSupplier toggled;
        private final java.util.function.IntSupplier accentColor;

        private TerminalButton(int x, int y, int width, int height, Component message,
                               Runnable onPress,
                               java.util.function.BooleanSupplier toggled,
                               java.util.function.IntSupplier accentColor) {
            super(x, y, width, height, message);
            this.onPress = onPress;
            this.toggled = toggled;
            this.accentColor = accentColor;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int bg = isHoveredOrFocused() ? 0xE81A1F2A : 0xD8111720;
            int border = toggled.getAsBoolean() ? accentColor.getAsInt() : 0x8842556E;
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
            guiGraphics.fill(getX(), getY(), getX() + 2, getY() + height, border);
            int textColor = this.active ? 0xFFEAF2FB : 0xFF9CA9B8;
            Minecraft mc = Minecraft.getInstance();
            String label = getMessage().getString();
            int maxTextWidth = Math.max(8, width - 8);
            int rawWidth = Math.max(1, mc.font.width(label));
            float textScale = Math.min(TOOLBAR_BUTTON_TEXT_SCALE, maxTextWidth / (float) rawWidth);
            int scaledTextWidth = Mth.ceil(rawWidth * textScale);
            int scaledTextHeight = Mth.ceil(8 * textScale);
            float tx = getX() + (width - scaledTextWidth) * 0.5f;
            float ty = getY() + (height - scaledTextHeight) * 0.5f;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(tx, ty, 0.0f);
            guiGraphics.pose().scale(textScale, textScale, 1.0f);
            guiGraphics.drawString(mc.font, label, 0, 0, textColor, false);
            guiGraphics.pose().popPose();
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (!this.active) {
                return;
            }
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress.run();
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            super.playDownSound(soundManager);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }

    private void renderSelectedMarkerHighlight(GuiGraphics guiGraphics) {
        if (selectedMarkerHit == null) {
            return;
        }
        int half = selectedMarkerHit.radius + 2;
        int length = Math.max(2, selectedMarkerHit.radius / 2 + 1);
        int color = withAlpha(selectedMarkerHit.accentColor, 0xE8);
        drawSelectionCorner(guiGraphics, selectedMarkerHit.screenX - half, selectedMarkerHit.screenY - half, length, true, true, color);
        drawSelectionCorner(guiGraphics, selectedMarkerHit.screenX + half, selectedMarkerHit.screenY - half, length, false, true, color);
        drawSelectionCorner(guiGraphics, selectedMarkerHit.screenX - half, selectedMarkerHit.screenY + half, length, true, false, color);
        drawSelectionCorner(guiGraphics, selectedMarkerHit.screenX + half, selectedMarkerHit.screenY + half, length, false, false, color);
    }

    private void drawSelectionCorner(GuiGraphics guiGraphics, int anchorX, int anchorY, int length,
                                     boolean left, boolean top, int color) {
        int x0 = left ? anchorX : anchorX - length + 1;
        int x1 = left ? anchorX + length - 1 : anchorX;
        int y0 = top ? anchorY : anchorY - length + 1;
        int y1 = top ? anchorY + length - 1 : anchorY;
        guiGraphics.hLine(x0, x1, anchorY, color);
        guiGraphics.vLine(anchorX, y0, y1, color);
    }

    private void openMapContextMenu(double mouseX, double mouseY, Vec3 target) {
        closeEntityContextMenu();
        mapContextMenuVisible = true;
        mapContextMenuX = Mth.floor(mouseX);
        mapContextMenuY = Mth.floor(mouseY);
        mapContextTarget = target;
    }

    private void openEntityContextMenu(double mouseX, double mouseY, MarkerHit markerHit) {
        closeMapContextMenu();
        entityContextMenuVisible = true;
        entityContextMenuX = Mth.floor(mouseX);
        entityContextMenuY = Mth.floor(mouseY);
        entityContextTarget = markerHit;
    }

    private boolean handleMapContextMenuClick(double mouseX, double mouseY, int button) {
        if (!mapContextMenuVisible || mapContextTarget == null) {
            return false;
        }
        if (button != 0) {
            return false;
        }
        int menuX = contextMenuRenderX();
        int menuY = contextMenuRenderY();
        int menuWidth = contextMenuWidth();
        int itemHeight = contextMenuItemHeight();
        int headerHeight = contextMenuHeaderHeight();
        int menuHeight = contextMenuHeight();
        if (mouseX < menuX || mouseX > menuX + menuWidth || mouseY < menuY || mouseY > menuY + menuHeight) {
            return false;
        }

        int itemIndex = (Mth.floor(mouseY) - (menuY + headerHeight + 3)) / itemHeight;
        if (itemIndex < 0 || itemIndex >= contextMenuItemCount()) {
            return true;
        }

        if (itemIndex == 0) {
            setGpsFields(mapContextTarget);
            bindGps();
        } else if (itemIndex == 1) {
            setGpsFields(mapContextTarget);
            openSidebar(SidebarMode.GPS);
        } else if (itemIndex == 2 && RVP_ClientGPSState.isActive()) {
            clearGps();
        }
        closeMapContextMenu();
        return true;
    }

    private void renderMapContextMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (mapContextTarget == null) {
            return;
        }
        int menuX = contextMenuRenderX();
        int menuY = contextMenuRenderY();
        int menuWidth = contextMenuWidth();
        int itemHeight = contextMenuItemHeight();
        int headerHeight = contextMenuHeaderHeight();
        int menuHeight = contextMenuHeight();
        guiGraphics.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, 0xEE171C24);
        guiGraphics.fill(menuX, menuY, menuX + menuWidth, menuY + 1, 0xFF5A708C);
        guiGraphics.fill(menuX, menuY + menuHeight - 1, menuX + menuWidth, menuY + menuHeight, 0xFF5A708C);
        guiGraphics.fill(menuX, menuY, menuX + 1, menuY + menuHeight, 0xFF5A708C);
        guiGraphics.fill(menuX + menuWidth - 1, menuY, menuX + menuWidth, menuY + menuHeight, 0xFF5A708C);

        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.menu_title"),
                menuX + 8, menuY + 6, 0xFFFFFFFF, false);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ywzj_rvp.tactical_map.cursor",
                        Mth.floor(mapContextTarget.x), Mth.floor(mapContextTarget.y), Mth.floor(mapContextTarget.z)),
                menuX + 8, menuY + 18, 0xFFB7C7D9, false);
        guiGraphics.fill(menuX + 6, menuY + headerHeight - 2, menuX + menuWidth - 6, menuY + headerHeight - 1, 0x335A708C);

        for (int i = 0; i < contextMenuItemCount(); i++) {
            int rowY = menuY + headerHeight + 3 + i * itemHeight;
            boolean hovered = mouseX >= menuX && mouseX <= menuX + menuWidth && mouseY >= rowY && mouseY <= rowY + itemHeight;
            if (hovered) {
                guiGraphics.fill(menuX + 1, rowY, menuX + menuWidth - 1, rowY + itemHeight, 0x66405A7A);
            }
            Component label = contextMenuLabel(i);
            int color = hovered ? 0xFFFFFFFF : 0xFFE7EEF7;
            if (i == 2) {
                color = hovered ? 0xFFFFD4D4 : 0xFFFF8D8D;
            }
            guiGraphics.drawString(this.font, label, menuX + 8, rowY + 3, color, false);
        }
    }

    private boolean handleEntityContextMenuClick(double mouseX, double mouseY, int button) {
        if (!entityContextMenuVisible || entityContextTarget == null) {
            return false;
        }
        if (button != 0) {
            return false;
        }
        int menuX = entityContextMenuRenderX();
        int menuY = entityContextMenuRenderY();
        int menuWidth = entityContextMenuWidth();
        int menuHeight = entityContextMenuHeight();
        int headerHeight = entityContextMenuHeaderHeight();
        int itemHeight = entityContextMenuItemHeight();
        if (mouseX < menuX || mouseX > menuX + menuWidth || mouseY < menuY || mouseY > menuY + menuHeight) {
            return false;
        }

        int itemIndex = (Mth.floor(mouseY) - (menuY + headerHeight + 3)) / itemHeight;
        if (itemIndex < 0 || itemIndex >= entityContextMenuItemCount()) {
            return true;
        }

        Vec3 focusPos = entityContextTarget.focusPos;
        Vec3 targetPos = entityContextTarget.targetPos;
        if (itemIndex == 0) {
            centerViewOn(focusPos);
        } else if (itemIndex == 1) {
            applyGpsTarget(focusPos, false, true);
        } else if (itemIndex == 2) {
            applyGpsTarget(focusPos, true, false);
        } else if (itemIndex == 3 && targetPos != null) {
            applyGpsTarget(targetPos, false, true);
        } else if (itemIndex == 4 && targetPos != null) {
            applyGpsTarget(targetPos, true, false);
        }
        closeEntityContextMenu();
        return true;
    }

    private void renderEntityContextMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (entityContextTarget == null) {
            return;
        }
        int menuX = entityContextMenuRenderX();
        int menuY = entityContextMenuRenderY();
        int menuWidth = entityContextMenuWidth();
        int menuHeight = entityContextMenuHeight();
        int headerHeight = entityContextMenuHeaderHeight();
        int itemHeight = entityContextMenuItemHeight();
        guiGraphics.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, 0xEE151B23);
        guiGraphics.fill(menuX, menuY, menuX + menuWidth, menuY + 1, 0xFF6A7E95);
        guiGraphics.fill(menuX, menuY + menuHeight - 1, menuX + menuWidth, menuY + menuHeight, 0xFF6A7E95);
        guiGraphics.fill(menuX, menuY, menuX + 1, menuY + menuHeight, 0xFF6A7E95);
        guiGraphics.fill(menuX + menuWidth - 1, menuY, menuX + menuWidth, menuY + menuHeight, 0xFF6A7E95);

        guiGraphics.drawString(this.font, markerLabel(entityContextTarget),
                menuX + 8, menuY + 6, 0xFFFFFFFF, false);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.ywzj_rvp.tactical_map.cursor",
                        Mth.floor(entityContextTarget.focusPos.x),
                        Mth.floor(entityContextTarget.focusPos.y),
                        Mth.floor(entityContextTarget.focusPos.z)),
                menuX + 8, menuY + 18, 0xFFB7C7D9, false);
        guiGraphics.fill(menuX + 6, menuY + headerHeight - 2, menuX + menuWidth - 6, menuY + headerHeight - 1, 0x336A7E95);

        for (int i = 0; i < entityContextMenuItemCount(); i++) {
            int rowY = menuY + headerHeight + 3 + i * itemHeight;
            boolean hovered = mouseX >= menuX && mouseX <= menuX + menuWidth && mouseY >= rowY && mouseY <= rowY + itemHeight;
            if (hovered) {
                guiGraphics.fill(menuX + 1, rowY, menuX + menuWidth - 1, rowY + itemHeight, 0x66405A7A);
            }
            guiGraphics.drawString(this.font, entityContextMenuLabel(i),
                    menuX + 8, rowY + 3, hovered ? 0xFFFFFFFF : 0xFFE7EEF7, false);
        }
    }

    private void renderSidePanel(GuiGraphics guiGraphics) {
        if (sidebarMode == SidebarMode.GPS) {
            renderGpsSidePanel(guiGraphics);
        } else if (sidebarMode == SidebarMode.RADAR) {
            renderRadarSidePanel(guiGraphics);
        }
    }

    private void renderGpsSidePanel(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        int x = sideLeft + 8;
        int y = mapTop + 8;
        int textWidth = Math.max(88, sideRight - x - 8);
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.gps.title"), x, y, 0xFFFFFFFF, false);
        y += 10;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.status"), x, y, 0xFF9CA9B8, false);
        y += 9;

        String vehicleName = mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle
                ? vehicle.getType().getDescription().getString()
                : "-";
        String vehicleLine = this.font.plainSubstrByWidth(
                Component.translatable("gui.ywzj_rvp.tactical_map.vehicle", vehicleName).getString(), textWidth);
        guiGraphics.drawString(this.font, vehicleLine, x, y, 0xFFE6EDF6, false);
        y += 9;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.center_pos",
                Mth.floor(viewWorldX), Mth.floor(viewWorldZ)), x, y, 0xFFE6EDF6, false);
        y += 9;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.remote_count",
                LocalVehiclePlayer.instance.serverEntities.size()), x, y, 0xFFE6EDF6, false);
        y = Math.min(y + 12, xBox.getY() - 18);

        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.gps.title"), x, y, 0xFFFFFFFF, false);
        y += 8;
        guiGraphics.drawString(this.font, followLabel(), x, y, 0xFF9CA9B8, false);
        y = bindButton.getY() + bindButton.getHeight() + 8;
        String hint = this.font.plainSubstrByWidth(Component.translatable("gui.ywzj_rvp.gps.hint").getString(), textWidth);
        guiGraphics.drawString(this.font, hint, x, y, 0xFF9CA9B8, false);
        y += 9;

        if (RVP_ClientGPSState.isActive()) {
            Vec3 pos = RVP_ClientGPSState.getPos();
            guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.gps_current"), x, y, 0xFFFFFFFF, false);
            y += 9;
            String coords = this.font.plainSubstrByWidth(
                    String.format("X %.1f  Y %.1f  Z %.1f", pos.x, pos.y, pos.z), textWidth);
            guiGraphics.drawString(this.font, coords, x, y, 0xFFFFFF66, false);
            y += 9;
            String label = RVP_ClientGPSState.isMultiMode()
                    ? "GPS " + RVP_ClientGPSState.getArmedPointNumber()
                    : "GPS";
            String status = "MODE " + (RVP_ClientGPSState.isMultiMode() ? "MULTI" : "SINGLE")
                    + "  |  " + label
                    + "  |  CNT " + RVP_ClientGPSState.getPointCount();
            guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(status, textWidth), x, y, GPS_ICON_COLOR, false);
        } else {
            guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.gps_none"), x, y, 0xFF9CA9B8, false);
        }
    }

    private void renderRadarSidePanel(GuiGraphics guiGraphics) {
        collectRadarContacts();
        int x = sideLeft + 8;
        int y = mapTop + 8;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_title"), x, y, 0xFFFFFFFF, false);
        renderRadarContactTable(guiGraphics);
    }

    private void collectRadarContacts() {
        radarContactRows.clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || tacticalRadarUnits.isEmpty()) {
            radarRowScroll = 0;
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            for (RadarUnit.DetectedObject detectedObject : radarUnit.getDetectedEntities().values()) {
                if (detectedObject == null || detectedObject.entity == null || detectedObject.detectedPosition == null) {
                    continue;
                }
                Entity entity = detectedObject.entity;
                if (!entity.isAlive() || !seen.add(entity.getId())) {
                    continue;
                }
                int relationColor = relationColorForEntity(player, entity);
                if (hideFriendlyRadarRows && isRealtimeVisibleRelation(relationColor)) {
                    continue;
                }
                Vec3 radarPos = radarUnit.worldRadarPosition();
                Vec2 aimRot = radarUnit.aimRot(detectedObject.detectedPosition);
                double distanceMeters = horizontalDistance(radarPos, detectedObject.detectedPosition);
                boolean locked = isRadarLockedEntity(entity);
                radarContactRows.add(new RadarContactRow(
                        entity.getId(),
                        entity,
                        radarUnit,
                        detectedObject.detectedPosition,
                        distanceMeters / 1000.0,
                        detectedObject.detectedPosition.y / 1000.0,
                        entity.getDeltaMovement().length() * 20.0,
                        resolveRadarNctrLabel(radarUnit, entity),
                        relationLabelForColor(relationColor),
                        relationColor,
                        locked
                ));
            }
        }
        radarContactRows.sort(Comparator
                .comparing((RadarContactRow row) -> !row.locked)
                .thenComparingDouble(row -> row.distanceKm));
        if (selectedRadarEntityId != null) {
            radarContactRows.stream()
                    .filter(row -> row.entityId == selectedRadarEntityId)
                    .findFirst()
                    .ifPresent(row -> selectMarker(findMarkerByEntityId(row.entityId)));
        }
        radarRowScroll = Mth.clamp(radarRowScroll, 0, Math.max(0, radarContactRows.size() - radarVisibleRowCount()));
    }

    private void renderRadarContactTable(GuiGraphics guiGraphics) {
        int x = radarTableX();
        int y = radarTableY();
        int width = radarTableWidth();
        int height = radarTableHeight();
        int headerHeight = radarTableHeaderHeight();
        int rowHeight = radarTableRowHeight();

        guiGraphics.fill(x, y, x + width, y + height, 0xA0091118);
        guiGraphics.fill(x, y, x + width, y + 1, 0xFF516579);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0xFF516579);
        guiGraphics.fill(x, y, x + 1, y + height, 0xFF516579);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0xFF516579);
        guiGraphics.fill(x + 1, y + headerHeight, x + width - 1, y + headerHeight + 1, 0x6642556E);

        drawRadarTableHeader(guiGraphics, x, y, width);

        int visibleRows = radarVisibleRowCount();
        int rowStart = radarRowScroll;
        int rowEnd = Math.min(radarContactRows.size(), rowStart + visibleRows);
        guiGraphics.enableScissor(x + 1, y + headerHeight + 1, x + width - 7, y + height - 1);
        for (int index = rowStart; index < rowEnd; index++) {
            RadarContactRow row = radarContactRows.get(index);
            int rowY = y + headerHeight + 2 + (index - rowStart) * rowHeight;
            boolean selected = selectedRadarEntityId != null && selectedRadarEntityId == row.entityId;
            if (selected) {
                guiGraphics.fill(x + 1, rowY, x + width - 7, rowY + rowHeight, 0x55374C61);
            } else if ((index - rowStart) % 2 == 0) {
                guiGraphics.fill(x + 1, rowY, x + width - 7, rowY + rowHeight, 0x22131B24);
            }
            drawRadarRow(guiGraphics, row, x + 4, rowY + 2, width - 12);
        }
        guiGraphics.disableScissor();
        drawRadarTableScrollbar(guiGraphics, x, y, width, height, visibleRows);
    }

    private void drawRadarTableHeader(GuiGraphics guiGraphics, int x, int y, int width) {
        int tx = x + 4;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_dist"), tx, y + 4, 0xFFBFD0E2, false);
        tx += 32;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_alt"), tx, y + 4, 0xFFBFD0E2, false);
        tx += 30;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_speed"), tx, y + 4, 0xFFBFD0E2, false);
        tx += 34;
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_nctr"), tx, y + 4, 0xFFBFD0E2, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_iff"), x + width - 50, y + 4, 0xFFBFD0E2, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.ywzj_rvp.tactical_map.radar_col_lock"), x + width - 24, y + 4, 0xFFBFD0E2, false);
    }

    private void drawRadarRow(GuiGraphics guiGraphics, RadarContactRow row, int x, int y, int width) {
        int tx = x;
        guiGraphics.drawString(this.font, formatRadarNumber(row.distanceKm), tx, y, 0xFFE6EDF6, false);
        tx += 32;
        guiGraphics.drawString(this.font, formatRadarNumber(row.altitudeKm), tx, y, 0xFFE6EDF6, false);
        tx += 30;
        guiGraphics.drawString(this.font, formatRadarNumber(row.speedMs), tx, y, 0xFFE6EDF6, false);
        tx += 34;
        String nctr = StringUtils.abbreviate(row.nctrLabel == null || row.nctrLabel.isBlank() ? "?" : row.nctrLabel, 10);
        guiGraphics.drawString(this.font, nctr, tx, y, 0xFFF0F6FF, false);
        guiGraphics.drawString(this.font, row.relationLabel, x + width - 50, y, row.relationColor, false);
        guiGraphics.drawString(this.font, row.locked ? "LCK" : "-", x + width - 24, y, row.locked ? 0xFF8FE3B8 : 0xFF8E9AA8, false);
    }

    private void drawRadarTableScrollbar(GuiGraphics guiGraphics, int x, int y, int width, int height, int visibleRows) {
        int barX = x + width - 5;
        int barTop = y + radarTableHeaderHeight() + 2;
        int barBottom = y + height - 3;
        guiGraphics.fill(barX, barTop, barX + 2, barBottom, 0x3342556E);
        if (radarContactRows.isEmpty() || radarContactRows.size() <= visibleRows) {
            guiGraphics.fill(barX, barTop, barX + 2, barBottom, 0x884C6378);
            return;
        }
        int barHeight = Math.max(12, (barBottom - barTop) * visibleRows / radarContactRows.size());
        int maxScroll = Math.max(1, radarContactRows.size() - visibleRows);
        int travel = Math.max(1, (barBottom - barTop) - barHeight);
        int thumbTop = barTop + travel * radarRowScroll / maxScroll;
        guiGraphics.fill(barX, thumbTop, barX + 2, thumbTop + barHeight, 0xFF8FB3D5);
    }

    private boolean handleRadarPanelClick(double mouseX, double mouseY, int button) {
        if (!sidebarVisible || sidebarMode != SidebarMode.RADAR) {
            return false;
        }
        if (!isOverRadarTable(mouseX, mouseY)) {
            return false;
        }
        RadarContactRow row = hitTestRadarRow(mouseX, mouseY);
        if (row == null) {
            return true;
        }
        selectedRadarEntityId = row.entityId;
        selectMarker(findMarkerByEntityId(row.entityId));
        if (button == 0) {
            if (isRadarRowDoubleClick(row, true)) {
                return tryLockRadarTarget(row.entity);
            }
            return true;
        }
        if (button == 1) {
            if (isRadarRowDoubleClick(row, false)) {
                return tryQuickFireOnTarget(row.entity);
            }
            return true;
        }
        return false;
    }

    private RadarContactRow hitTestRadarRow(double mouseX, double mouseY) {
        if (!isOverRadarTable(mouseX, mouseY)) {
            return null;
        }
        int relY = Mth.floor(mouseY) - (radarTableY() + radarTableHeaderHeight() + 2);
        if (relY < 0) {
            return null;
        }
        int rowIndex = relY / radarTableRowHeight();
        int actualIndex = radarRowScroll + rowIndex;
        if (actualIndex < 0 || actualIndex >= radarContactRows.size()) {
            return null;
        }
        return radarContactRows.get(actualIndex);
    }

    private boolean isMarkerDoubleClick(@Nullable MarkerHit markerHit, boolean leftButton) {
        long now = System.currentTimeMillis();
        int entityId = markerHit != null ? markerHit.entityId : Integer.MIN_VALUE;
        long lastAt = leftButton ? lastMapLeftClickAt : lastMapRightClickAt;
        int lastId = leftButton ? lastMapLeftClickEntityId : lastMapRightClickEntityId;
        boolean matched = markerHit != null && entityId == lastId && now - lastAt <= DOUBLE_CLICK_WINDOW_MS;
        if (leftButton) {
            lastMapLeftClickAt = now;
            lastMapLeftClickEntityId = entityId;
        } else {
            lastMapRightClickAt = now;
            lastMapRightClickEntityId = entityId;
        }
        return matched;
    }

    private void rememberMarkerClick(@Nullable MarkerHit markerHit, boolean leftButton) {
        if (leftButton) {
            lastMapLeftClickAt = System.currentTimeMillis();
            lastMapLeftClickEntityId = markerHit != null ? markerHit.entityId : Integer.MIN_VALUE;
        } else {
            lastMapRightClickAt = System.currentTimeMillis();
            lastMapRightClickEntityId = markerHit != null ? markerHit.entityId : Integer.MIN_VALUE;
        }
    }

    private boolean isRadarRowDoubleClick(RadarContactRow row, boolean leftButton) {
        long now = System.currentTimeMillis();
        long lastAt = leftButton ? lastRadarRowLeftClickAt : lastRadarRowRightClickAt;
        int lastId = leftButton ? lastRadarRowLeftClickEntityId : lastRadarRowRightClickEntityId;
        boolean matched = row.entityId == lastId && now - lastAt <= DOUBLE_CLICK_WINDOW_MS;
        if (leftButton) {
            lastRadarRowLeftClickAt = now;
            lastRadarRowLeftClickEntityId = row.entityId;
        } else {
            lastRadarRowRightClickAt = now;
            lastRadarRowRightClickEntityId = row.entityId;
        }
        return matched;
    }

    private MarkerHit findMarkerByEntityId(int entityId) {
        for (int i = markerHits.size() - 1; i >= 0; i--) {
            if (markerHits.get(i).entityId == entityId) {
                return markerHits.get(i);
            }
        }
        return null;
    }

    private boolean tryLockRadarTarget(@Nullable Entity target) {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        LocalPlayer player = Minecraft.getInstance().player;
        if (weaponUnit == null || player == null) {
            return false;
        }
        RadarUnit mainRadar = weaponUnit.getMainRadarUnit();
        if (mainRadar == null) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_no_unit"), true);
            return false;
        }
        if (target == null || !tacticalRadarVisibleIds.contains(target.getId())) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_target_unavailable"), true);
            return false;
        }
        applyRadarLockState(weaponUnit, mainRadar, target);
        beginRadarLockAssist(target.getId(), RADAR_LOCK_ASSIST_TICKS);
        selectedRadarEntityId = target.getId();
        selectMarker(findMarkerByEntityId(target.getId()));
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_lock_target", radarTargetDisplayName(target)), true);
        return true;
    }

    private boolean tryQuickFireOnTarget(@Nullable Entity target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (player == null || weaponUnit == null || target == null) {
            return false;
        }
        Optional<AbstractVehicleWeapon<?>> weaponOptional = weaponUnit.getCurrentWeapon();
        if (weaponOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_no_weapon"), true);
            return false;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponOptional.get();
        if (!(currentWeapon.getData() instanceof RVP_WeaponData data)
                || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE
                || !data.isActiveRadar()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_need_arh"), true);
            return false;
        }
        if (!tryLockRadarTarget(target)) {
            return false;
        }
        beginPendingQuickFire(target.getId());
        return true;
    }

    private void tickPendingRadarActions() {
        tickRadarLockAssist();
        tickPendingQuickFire();
    }

    private void tickRadarLockAssist() {
        if (assistedRadarLockTicks <= 0 || assistedRadarLockEntityId == Integer.MIN_VALUE) {
            clearRadarLockAssist();
            return;
        }
        assistedRadarLockTicks--;
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        RadarUnit mainRadar = weaponUnit != null ? weaponUnit.getMainRadarUnit() : null;
        Entity target = findTrackedEntityById(assistedRadarLockEntityId);
        if (weaponUnit != null && mainRadar != null && target != null && target.isAlive()) {
            applyRadarLockState(weaponUnit, mainRadar, target);
            selectedRadarEntityId = target.getId();
            selectMarker(findMarkerByEntityId(target.getId()));
        }
        if (assistedRadarLockTicks <= 0) {
            clearRadarLockAssist();
        }
    }

    private void tickPendingQuickFire() {
        if (pendingQuickFireTicks <= 0 || pendingQuickFireEntityId == Integer.MIN_VALUE) {
            clearPendingQuickFire();
            return;
        }
        pendingQuickFireTicks--;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (player == null || weaponUnit == null) {
            clearPendingQuickFire();
            return;
        }
        Optional<AbstractVehicleWeapon<?>> weaponOptional = weaponUnit.getCurrentWeapon();
        if (weaponOptional.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_no_weapon"), true);
            clearPendingQuickFire();
            return;
        }
        AbstractVehicleWeapon<?> currentWeapon = weaponOptional.get();
        if (!(currentWeapon instanceof org.ywzj.rvp.weapon.core.RVP_WeaponBase rvpWeapon)
                || !(currentWeapon.getData() instanceof RVP_WeaponData data)
                || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE
                || !data.isActiveRadar()) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_need_arh"), true);
            clearPendingQuickFire();
            return;
        }
        Entity target = findTrackedEntityById(pendingQuickFireEntityId);
        RadarUnit mainRadar = weaponUnit.getMainRadarUnit();
        if (target != null && target.isAlive() && mainRadar != null) {
            if (!weaponUnit.isSeekerOn()) {
                weaponUnit.toggleSeeker(true);
            }
            refreshTrackedRadarDetection(mainRadar, target);
            applyRadarLockState(weaponUnit, mainRadar, target);
            boolean radarLocked = entityMatches(mainRadar.getLockedEntity(), pendingQuickFireEntityId);
            boolean weaponLocked = entityMatches(weaponUnit.getLockedEntity(), pendingQuickFireEntityId);
            boolean seekerReady = isTargetInQuickFireSeekerEnvelope(weaponUnit, data, target);
            pendingQuickFireEverRadarLocked |= radarLocked;
            pendingQuickFireEverWeaponLocked |= weaponLocked;
            pendingQuickFireEverSeekerReady |= seekerReady;
            if (radarLocked && weaponLocked && seekerReady) {
                pendingQuickFireLockStableTicks++;
            } else {
                pendingQuickFireLockStableTicks = 0;
            }
            if (pendingQuickFireLockStableTicks >= QUICK_FIRE_LOCK_STABLE_TICKS) {
                if (pendingQuickFireRetryTicks > 0) {
                    pendingQuickFireRetryTicks--;
                } else {
                    rvpWeapon.queueProgrammaticShot();
                    if (currentWeapon.doClientShoot()) {
                        player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_fired", radarTargetDisplayName(target)), true);
                        beginRadarLockAssist(target.getId(), QUICK_FIRE_TIMEOUT_TICKS);
                        clearPendingQuickFire();
                        return;
                    }
                    pendingQuickFireRetryTicks = QUICK_FIRE_RETRY_INTERVAL_TICKS;
                }
            }
        }
        if (pendingQuickFireTicks <= 0) {
            player.displayClientMessage(resolveQuickFireFailureMessage(), true);
            clearPendingQuickFire();
        }
    }

    private void beginPendingQuickFire(int entityId) {
        pendingQuickFireEntityId = entityId;
        pendingQuickFireTicks = QUICK_FIRE_TIMEOUT_TICKS;
        pendingQuickFireLockStableTicks = 0;
        pendingQuickFireRetryTicks = 0;
        pendingQuickFireEverRadarLocked = false;
        pendingQuickFireEverWeaponLocked = false;
        pendingQuickFireEverSeekerReady = false;
        beginRadarLockAssist(entityId, QUICK_FIRE_TIMEOUT_TICKS);
    }

    private void clearPendingQuickFire() {
        pendingQuickFireEntityId = Integer.MIN_VALUE;
        pendingQuickFireTicks = 0;
        pendingQuickFireLockStableTicks = 0;
        pendingQuickFireRetryTicks = 0;
        pendingQuickFireEverRadarLocked = false;
        pendingQuickFireEverWeaponLocked = false;
        pendingQuickFireEverSeekerReady = false;
    }

    private void beginRadarLockAssist(int entityId, int ticks) {
        if (entityId == Integer.MIN_VALUE || ticks <= 0) {
            return;
        }
        if (assistedRadarLockEntityId != entityId) {
            assistedRadarLockEntityId = entityId;
        }
        assistedRadarLockTicks = Math.max(assistedRadarLockTicks, ticks);
    }

    private void clearRadarLockAssist() {
        assistedRadarLockEntityId = Integer.MIN_VALUE;
        assistedRadarLockTicks = 0;
    }

    private void applyRadarLockState(WeaponUnit weaponUnit, RadarUnit mainRadar, Entity target) {
        if (!entityMatches(mainRadar.getLockedEntity(), target.getId())) {
            mainRadar.setLockedEntity(target);
        }
        if (!entityMatches(weaponUnit.getLockedEntity(), target.getId())) {
            weaponUnit.setLockedEntity(target);
        }
    }

    private void refreshTrackedRadarDetection(RadarUnit mainRadar, Entity target) {
        mainRadar.detect(target);
        tacticalRadarVisibleIds.add(target.getId());
    }

    private Component resolveQuickFireFailureMessage() {
        if (!pendingQuickFireEverRadarLocked) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_lock");
        }
        if (!pendingQuickFireEverWeaponLocked) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_turret");
        }
        if (!pendingQuickFireEverSeekerReady) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_seeker");
        }
        return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_launch");
    }

    private boolean isTargetInQuickFireSeekerEnvelope(WeaponUnit weaponUnit, RVP_WeaponData data, Entity target) {
        WeaponUnit rootWeaponUnit = weaponUnit.getRootParentWeaponUnit();
        Vec3 origin = rootWeaponUnit.aimContext().from;
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double maxRange = Math.max(1.0, data.getMaxLockOnRange());
        if (origin.distanceToSqr(targetCenter) > maxRange * maxRange) {
            return false;
        }
        Vec3 look = VectorUtil.rotToVec(rootWeaponUnit.aimContext().direction.x, rootWeaponUnit.aimContext().direction.y);
        Vec3 toTarget = targetCenter.subtract(origin);
        if (look.lengthSqr() <= 1.0E-6 || toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double angle = Math.toDegrees(VectorUtil.angleBetween(look.normalize(), toTarget.normalize()));
        double seekerAngle = Math.max(1.0, data.getMaxGuideHeadAngle());
        return angle <= seekerAngle;
    }

    @Nullable
    private Entity findTrackedEntityById(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity levelEntity = mc.level.getEntity(entityId);
            if (levelEntity != null) {
                return levelEntity;
            }
        }
        for (RadarContactRow row : radarContactRows) {
            if (row.entityId == entityId && row.entity != null) {
                return row.entity;
            }
        }
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            RadarUnit.DetectedObject detectedObject = radarUnit.getDetectedEntities().get(entityId);
            if (detectedObject != null && detectedObject.entity != null) {
                return detectedObject.entity;
            }
        }
        MarkerHit markerHit = findMarkerByEntityId(entityId);
        return markerHit != null ? markerHit.entity : null;
    }

    private boolean entityMatches(@Nullable Entity entity, int entityId) {
        return entity != null && entity.getId() == entityId;
    }

    private int radarTableX() {
        return sideLeft + 8;
    }

    private int radarTableY() {
        if (radarHideFriendlyButton != null && radarHideFriendlyButton.visible) {
            return radarHideFriendlyButton.getY() + radarHideFriendlyButton.getHeight() + 6;
        }
        return mapTop + 46;
    }

    private int radarTableWidth() {
        return Math.max(140, sideRight - sideLeft - 16);
    }

    private int radarTableHeight() {
        return Math.max(72, mapBottom - radarTableY() - 8);
    }

    private int radarTableHeaderHeight() {
        return 14;
    }

    private int radarTableRowHeight() {
        return 12;
    }

    private int radarVisibleRowCount() {
        return Math.max(1, (radarTableHeight() - radarTableHeaderHeight() - 4) / radarTableRowHeight());
    }

    private boolean isOverRadarTable(double mouseX, double mouseY) {
        int x = radarTableX();
        int y = radarTableY();
        return mouseX >= x && mouseX <= x + radarTableWidth()
                && mouseY >= y && mouseY <= y + radarTableHeight();
    }

    private RadarUnit getMainRadarUnit() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        return weaponUnit != null ? weaponUnit.getMainRadarUnit() : null;
    }

    private Entity getMainRadarLockedEntity() {
        RadarUnit mainRadar = getMainRadarUnit();
        return mainRadar != null ? mainRadar.getLockedEntity() : null;
    }

    private boolean isRadarLockedEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            Entity locked = radarUnit.getLockedEntity();
            if (locked != null && locked.getId() == entity.getId()) {
                return true;
            }
        }
        return false;
    }

    private String resolveRadarNctrLabel(RadarUnit radarUnit, Entity entity) {
        RadarUnitData data = radarUnit.getData();
        if (!(data instanceof RadarUnitDataExt ext)) {
            return "";
        }
        String mode = ext.ywzj_rvp$getNctrMode();
        if ("NONE".equalsIgnoreCase(mode)) {
            return "";
        }
        if ("EARLY".equalsIgnoreCase(mode)) {
            return resolveEarlyNctrLabel(entity);
        }
        return resolveModernNctrLabel(entity);
    }

    private String resolveEarlyNctrLabel(Entity entity) {
        if (entity instanceof FixedWingVehicle) {
            return "JET";
        }
        if (entity instanceof RotaryWingVehicle) {
            return "HELI";
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            if (bullet.getWeaponKind() == RVP_EnumWeaponKind.MISSILE) {
                return "MSL";
            }
            if (bullet.getWeaponKind() == RVP_EnumWeaponKind.BOMB) {
                return "BOMB";
            }
        }
        return "?";
    }

    private String resolveModernNctrLabel(Entity entity) {
        if (entity instanceof AbstractVehicle vehicle) {
            return VehicleUIPresetCache.getNctrName(vehicle.getVehicleId());
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            ResourceLocation weaponId = bullet.getWeaponId();
            if (weaponId != null) {
                return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                        .map(index -> index.data().getName())
                        .filter(name -> name != null && !name.isBlank())
                        .orElse(weaponId.getPath().toUpperCase(Locale.ROOT));
            }
        }
        return resolveEarlyNctrLabel(entity);
    }

    private String radarTargetDisplayName(Entity entity) {
        String nctr = resolveModernNctrLabel(entity);
        if (nctr != null && !nctr.isBlank() && !"?".equals(nctr)) {
            return nctr;
        }
        return entity.getType().getDescription().getString();
    }

    private String relationLabelForColor(int color) {
        int rgb = color & 0x00FFFFFF;
        if (rgb == (OWN_ICON_COLOR & 0x00FFFFFF)) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.radar_relation_own").getString();
        }
        if (rgb == (FRIEND_ICON_COLOR & 0x00FFFFFF)) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.radar_relation_friend").getString();
        }
        if (rgb == (HOSTILE_ICON_COLOR & 0x00FFFFFF)) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.radar_relation_hostile").getString();
        }
        return Component.translatable("gui.ywzj_rvp.tactical_map.radar_relation_neutral").getString();
    }

    private double normalizeAzimuth(double azimuthDeg) {
        double normalized = azimuthDeg % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String formatRadarNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void drawContactMarker(GuiGraphics guiGraphics, Entity entity, int color) {
        drawMarkerIcon(guiGraphics, resolveFallbackEntityIcon(entity), entity.getX(), entity.getZ(), entity.getYRot(), 10, color, true);
    }

    private void drawVehicleMarker(GuiGraphics guiGraphics, AbstractVehicle vehicle, int color) {
        drawMarkerIcon(guiGraphics, resolveVehicleIcon(vehicle), vehicle.getX(), vehicle.getZ(), vehicle.getYRot(), 12, color, true);
    }

    private void drawPlayerMarker(GuiGraphics guiGraphics, Player player, int color) {
        drawMarkerIcon(guiGraphics, PLAYER_ICON, player.getX(), player.getZ(), player.getYRot(), 11, color, true);
    }

    private void drawMissileMarker(GuiGraphics guiGraphics, Entity entity, int color) {
        drawMarkerIcon(guiGraphics, resolveMissileIcon(entity), entity.getX(), entity.getZ(), entity.getYRot(), 10, color, true);
    }

    private void drawTargetMarker(GuiGraphics guiGraphics, int sx, int sy, int color) {
        long millis = System.currentTimeMillis();
        float pulse = 1.0f + ((millis % 900L) / 900.0f) * 0.16f;
        drawScreenIcon(guiGraphics, GPS_ICON, sx, sy, 12, color, false, 0.0f, pulse);
    }

    private void drawMarkerIcon(GuiGraphics guiGraphics, ResourceLocation icon, double worldX, double worldZ, float yaw, int size, int color, boolean rotate) {
        int sx = Mth.floor(worldToScreenX(worldX));
        int sy = Mth.floor(worldToScreenY(worldZ));
        if (!isMarkerVisible(sx, sy, size / 2 + 2)) {
            return;
        }
        float iconYaw = mapIconYaw(yaw);
        drawScreenIcon(guiGraphics, icon, sx, sy, size, color, rotate, iconYaw, 1.0f);
        if (rotate) {
            drawHeadingTick(guiGraphics, sx, sy, iconYaw, size, color);
        }
    }

    private void drawScreenIcon(GuiGraphics guiGraphics, ResourceLocation icon, int centerX, int centerY, int size, int color,
                                boolean rotate, float yaw, float scale) {
        float actualSize = size * scale;
        drawIconLayer(guiGraphics, icon, centerX, centerY, actualSize, color, rotate, yaw);
    }

    private void drawIconLayer(GuiGraphics guiGraphics, ResourceLocation icon, int centerX, int centerY, float actualSize,
                               int color, boolean rotate, float yaw) {
        float half = actualSize * 0.5f;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(red(color), green(color), blue(color), alpha(color));
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0.0f);
        if (rotate) {
            pose.mulPose(Axis.ZP.rotationDegrees(yaw));
        }
        pose.scale(actualSize / 32.0f, actualSize / 32.0f, 1.0f);
        guiGraphics.blit(icon, Mth.floor(-half * 32.0f / actualSize), Mth.floor(-half * 32.0f / actualSize),
                0, 0, 32, 32, 32, 32);
        pose.popPose();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawHeadingTick(GuiGraphics guiGraphics, int centerX, int centerY, float yaw, int size, int color) {
        double rad = Math.toRadians(yaw);
        int start = Math.max(3, size / 3);
        int end = Math.max(start + 2, size / 2 + 2);
        int x0 = centerX + Mth.floor((float) Math.sin(rad) * start);
        int y0 = centerY - Mth.floor((float) Math.cos(rad) * start);
        int x1 = centerX + Mth.floor((float) Math.sin(rad) * end);
        int y1 = centerY - Mth.floor((float) Math.cos(rad) * end);
        drawLine(guiGraphics, x0, y0, x1, y1, withAlpha(color, 0xEE));
        plot(guiGraphics, x1, y1, 0xFFFFFFFF);
    }

    private int relationColorForVehicle(@Nullable LocalPlayer player, AbstractVehicle vehicle) {
        if (player == null) {
            return FRIEND_ICON_COLOR;
        }
        if (player.getVehicle() == vehicle) {
            return OWN_ICON_COLOR;
        }
        LivingEntity driver = vehicle.getDriver();
        if (driver != null) {
            return relationColorForEntity(player, driver);
        }
        return UNMANNED_VEHICLE_ICON_COLOR;
    }

    private int relationColorForPlayer(@Nullable LocalPlayer player, Player target) {
        if (player == null) {
            return FRIEND_ICON_COLOR;
        }
        if (target == player) {
            return OWN_ICON_COLOR;
        }
        return isAlliedTeam(player, target.getTeam()) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
    }

    private int relationColorForBullet(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
        if (player == null) {
            return FRIEND_ICON_COLOR;
        }
        if (isOwnAmmo(player, playerVehicle, bullet)) {
            return OWN_ICON_COLOR;
        }
        Entity owner = bullet.getOwner();
        if (owner != null) {
            return relationColorForEntity(player, owner);
        }
        return bullet.getTeam() != null && isAlliedTeam(player, bullet.getTeam()) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
    }

    private boolean shouldShowEntityOnTacticalMap(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle, Entity entity) {
        if (player == null || entity == null) {
            return true;
        }
        int relationColor = entity instanceof RVP_BaseBullet bullet
                ? relationColorForBullet(player, playerVehicle, bullet)
                : relationColorForEntity(player, entity);
        if (isRealtimeVisibleRelation(relationColor)) {
            return true;
        }
        return tacticalRadarVisibleIds.contains(entity.getId());
    }

    private boolean shouldShowRemoteAmmoOnTacticalMap(S2CRemoteAmmoSnapshot.Entry entry) {
        if (entry.affiliation() == S2CRemoteAmmoSnapshot.Affiliation.OWN
                || entry.affiliation() == S2CRemoteAmmoSnapshot.Affiliation.FRIEND) {
            return true;
        }
        return tacticalRadarVisibleIds.contains(entry.entityId());
    }

    private boolean isRealtimeVisibleRelation(int relationColor) {
        int rgb = relationColor & 0x00FFFFFF;
        return rgb == (OWN_ICON_COLOR & 0x00FFFFFF) || rgb == (FRIEND_ICON_COLOR & 0x00FFFFFF);
    }

    private int relationColorForEntity(@Nullable LocalPlayer player, @Nullable Entity entity) {
        if (player == null || entity == null) {
            return FRIEND_ICON_COLOR;
        }
        if (entity == player) {
            return OWN_ICON_COLOR;
        }
        if (entity instanceof Player targetPlayer) {
            return relationColorForPlayer(player, targetPlayer);
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return relationColorForVehicle(player, vehicle);
        }
        if (entity instanceof GunnerEntity gunner) {
            if (gunner.getProfileFaction() == RVP_EnumGunnerFaction.ENEMY) {
                return HOSTILE_ICON_COLOR;
            }
            if (gunner.isOwnedBy(player)) {
                return FRIEND_ICON_COLOR;
            }
            if (isAlliedTeam(player, gunner.getTeam()) || gunner.getProfileFaction() == RVP_EnumGunnerFaction.FRIENDLY) {
                return FRIEND_ICON_COLOR;
            }
            return HOSTILE_ICON_COLOR;
        }
        Team team = entity.getTeam();
        if (team == null) {
            return NEUTRAL_ICON_COLOR;
        }
        return isAlliedTeam(player, team) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
    }

    private boolean isAlliedTeam(LocalPlayer player, @Nullable Team team) {
        Team playerTeam = player.getTeam();
        return playerTeam != null && team != null && team.isAlliedTo(playerTeam);
    }

    private ResourceLocation resolveVehicleIcon(AbstractVehicle vehicle) {
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId()).orElse(null);
        if (data != null) {
            ResourceLocation configured = resolveConfiguredIcon(readVehicleIconOverride(data));
            if (configured != null) {
                return configured;
            }
            if (data instanceof RotaryWingVehicleData) {
                return HELI_ICON;
            }
            if (data instanceof FixedWingVehicleData) {
                return JET_ICON;
            }
        }
        return GROUND_ICON;
    }

    private ResourceLocation resolveMissileIcon(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet) {
            ResourceLocation weaponId = bullet.getWeaponId();
            if (weaponId != null) {
                ResourceLocation weaponIcon = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                        .map(index -> index.data())
                        .filter(RVP_WeaponData.class::isInstance)
                        .map(RVP_WeaponData.class::cast)
                        .map(this::resolveWeaponIcon)
                        .orElse(null);
                if (weaponIcon != null) {
                    return weaponIcon;
                }
            }
            if (resolveProjectileKind(bullet) == RVP_EnumWeaponKind.BOMB) {
                return BOMB_ICON;
            }
        }
        return MISSILE_ICON;
    }

    private ResourceLocation resolveRemoteAmmoIcon(S2CRemoteAmmoSnapshot.Entry entry) {
        if (entry.weaponId() != null) {
            ResourceLocation weaponIcon = CommonAssetsManager.vehicleWeaponManager().getIndex(entry.weaponId())
                    .map(index -> index.data())
                    .filter(RVP_WeaponData.class::isInstance)
                    .map(RVP_WeaponData.class::cast)
                    .map(this::resolveWeaponIcon)
                    .orElse(null);
            if (weaponIcon != null) {
                return weaponIcon;
            }
        }
        return entry.weaponKind() == RVP_EnumWeaponKind.BOMB ? BOMB_ICON : MISSILE_ICON;
    }

    private ResourceLocation resolveWeaponIcon(RVP_WeaponData weaponData) {
        ResourceLocation configured = resolveConfiguredIcon(weaponData.getTacticalMapIcon());
        if (configured != null) {
            return configured;
        }
        if (weaponData.getWeaponKind() == RVP_EnumWeaponKind.BOMB) {
            return BOMB_ICON;
        }
        if (weaponData.getWeaponKind() == RVP_EnumWeaponKind.MISSILE) {
            return MISSILE_ICON;
        }
        return MISSILE_ICON;
    }

    private boolean shouldRenderProjectileMarker(RVP_BaseBullet bullet) {
        RVP_EnumWeaponKind kind = resolveProjectileKind(bullet);
        return kind == RVP_EnumWeaponKind.MISSILE || kind == RVP_EnumWeaponKind.BOMB;
    }

    private void beginTrackedAmmoFrame() {
        visibleTrackedAmmoIds.clear();
    }

    private void finishTrackedAmmoFrame() {
        if (trackedAmmoPositions.isEmpty()) {
            return;
        }
        List<Integer> disappearedIds = new ArrayList<>();
        for (Map.Entry<Integer, Vec3> entry : trackedAmmoPositions.entrySet()) {
            if (!visibleTrackedAmmoIds.contains(entry.getKey())) {
                recentImpactCrosses.add(new ImpactCross(entry.getValue(), IMPACT_CROSS_LIFE_TICKS));
                disappearedIds.add(entry.getKey());
            }
        }
        for (Integer entityId : disappearedIds) {
            trackedAmmoPositions.remove(entityId);
        }
    }

    private void trackAmmoPosition(int entityId, Vec3 pos, @Nullable RVP_EnumWeaponKind weaponKind) {
        if (weaponKind != RVP_EnumWeaponKind.MISSILE && weaponKind != RVP_EnumWeaponKind.BOMB) {
            return;
        }
        visibleTrackedAmmoIds.add(entityId);
        trackedAmmoPositions.put(entityId, pos);
    }

    private void tickImpactCrosses() {
        recentImpactCrosses.removeIf(cross -> --cross.lifeTicks <= 0);
    }

    private void renderRecentImpactCrosses(GuiGraphics guiGraphics) {
        for (ImpactCross cross : recentImpactCrosses) {
            int sx = Mth.floor(worldToScreenX(cross.pos.x));
            int sy = Mth.floor(worldToScreenY(cross.pos.z));
            if (!isMarkerVisible(sx, sy, 6)) {
                continue;
            }
            int alpha = Mth.clamp((int) (255.0f * (cross.lifeTicks / (float) IMPACT_CROSS_LIFE_TICKS)), 48, 255);
            drawImpactCross(guiGraphics, sx, sy, withAlpha(HOSTILE_ICON_COLOR, alpha));
        }
    }

    private void drawImpactCross(GuiGraphics guiGraphics, int sx, int sy, int color) {
        drawLine(guiGraphics, sx - 3, sy - 3, sx + 3, sy + 3, color);
        drawLine(guiGraphics, sx - 3, sy + 3, sx + 3, sy - 3, color);
    }

    private RVP_EnumWeaponKind resolveProjectileKind(RVP_BaseBullet bullet) {
        ResourceLocation weaponId = bullet.getWeaponId();
        if (weaponId != null) {
            RVP_EnumWeaponKind resolved = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                    .map(index -> index.data())
                    .filter(RVP_WeaponData.class::isInstance)
                    .map(RVP_WeaponData.class::cast)
                    .map(RVP_WeaponData::getWeaponKind)
                    .orElse(null);
            if (resolved != null) {
                return resolved;
            }
        }
        return bullet.getWeaponKind();
    }

    private int relationColorForAffiliation(S2CRemoteAmmoSnapshot.Affiliation affiliation) {
        return switch (affiliation) {
            case OWN -> OWN_ICON_COLOR;
            case FRIEND -> FRIEND_ICON_COLOR;
            case HOSTILE -> HOSTILE_ICON_COLOR;
        };
    }

    private float mapIconYaw(float yaw) {
        return yaw + 180.0f;
    }

    private ResourceLocation resolveFallbackEntityIcon(Entity entity) {
        if (entity instanceof Player) {
            return PLAYER_ICON;
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return resolveVehicleIcon(vehicle);
        }
        return GROUND_ICON;
    }

    @Nullable
    private ResourceLocation resolveConfiguredIcon(String iconValue) {
        if (iconValue == null || iconValue.isBlank()) {
            return null;
        }
        String raw = iconValue.trim();
        if (raw.contains(":")) {
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed == null) {
                return null;
            }
            String path = parsed.getPath();
            if (!path.endsWith(".png")) {
                path = path + ".png";
            }
            if (!path.startsWith("textures/")) {
                path = ICON_TEXTURE_ROOT + path;
            }
            return ResourceLocation.fromNamespaceAndPath(parsed.getNamespace(), path);
        }
        String file = raw.endsWith(".png") ? raw : raw + ".png";
        return mapIcon(file);
    }

    @Nullable
    private String readVehicleIconOverride(BaseVehicleData<?> data) {
        try {
            Method method = data.getClass().getMethod("getTacticalMapIcon");
            Object value = method.invoke(data);
            if (value instanceof String icon && !icon.isBlank()) {
                return icon;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private float alpha(int color) {
        return ((color >>> 24) & 0xFF) / 255.0f;
    }

    private float red(int color) {
        return ((color >>> 16) & 0xFF) / 255.0f;
    }

    private float green(int color) {
        return ((color >>> 8) & 0xFF) / 255.0f;
    }

    private float blue(int color) {
        return (color & 0xFF) / 255.0f;
    }

    private boolean isMarkerVisible(int sx, int sy, int radius) {
        return sx >= mapLeft - radius && sx <= mapRight + radius && sy >= mapTop - radius && sy <= mapBottom + radius;
    }

    private void registerMarkerHit(Entity entity, MarkerKind kind, double worldX, double worldY, double worldZ, int radius, Vec3 targetPos) {
        int sx = Mth.floor(worldToScreenX(worldX));
        int sy = Mth.floor(worldToScreenY(worldZ));
        if (!isMarkerVisible(sx, sy, radius)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        AbstractVehicle playerVehicle = player != null && player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        markerHits.add(new MarkerHit(entity.getId(), entity, kind, sx, sy, radius,
                new Vec3(worldX, worldY, worldZ), targetPos,
                resolveMarkerDisplayName(entity),
                resolveEntitySpeedKmh(entity),
                isGpsMarker(entity),
                resolveMarkerAccentColor(player, playerVehicle, entity, kind)));
    }

    private void registerRemoteAmmoHit(S2CRemoteAmmoSnapshot.Entry entry) {
        int radius = 7;
        int sx = Mth.floor(worldToScreenX(entry.x()));
        int sy = Mth.floor(worldToScreenY(entry.z()));
        if (!isMarkerVisible(sx, sy, radius)) {
            return;
        }
        markerHits.add(new MarkerHit(entry.entityId(), null, MarkerKind.MISSILE, sx, sy, radius,
                new Vec3(entry.x(), entry.y(), entry.z()), entry.guidancePos(),
                Component.literal(entry.displayName()),
                entry.speedKmh(),
                entry.gpsCapable(),
                relationColorForAffiliation(entry.affiliation())));
    }

    private MarkerHit hitTestMarker(double mouseX, double mouseY) {
        for (int i = markerHits.size() - 1; i >= 0; i--) {
            MarkerHit hit = markerHits.get(i);
            double dx = mouseX - hit.screenX;
            double dy = mouseY - hit.screenY;
            int radius = hit.radius + 3;
            if (dx * dx + dy * dy <= radius * radius) {
                return hit;
            }
        }
        return null;
    }

    private Vec3 getMarkerTargetPos(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet) {
            Vec3 target = bullet.getTargetPos();
            return target != null ? target : bullet.getLastGuidancePos();
        }
        if (entity instanceof MissileEntity missile) {
            return missile.targetPos;
        }
        return null;
    }

    private void syncSelectedMarkerHit() {
        if (selectedMarkerId == null) {
            selectedMarkerHit = null;
            return;
        }
        for (int i = markerHits.size() - 1; i >= 0; i--) {
            MarkerHit hit = markerHits.get(i);
            if (hit.entityId == selectedMarkerId) {
                selectedMarkerHit = hit;
                return;
            }
        }
        selectedMarkerHit = null;
    }

    private void selectMarker(@Nullable MarkerHit markerHit) {
        selectedMarkerId = markerHit != null ? markerHit.entityId : null;
        selectedMarkerHit = markerHit;
    }

    private boolean supportsSelection(@Nullable MarkerHit markerHit) {
        if (markerHit == null) {
            return false;
        }
        if (markerHit.kind == MarkerKind.MISSILE) {
            return true;
        }
        if ((markerHit.kind == MarkerKind.OWN_VEHICLE || markerHit.kind == MarkerKind.REMOTE_VEHICLE)
                && markerHit.entity instanceof AbstractVehicle vehicle) {
            return isAircraft(vehicle);
        }
        return false;
    }

    private boolean isAircraft(AbstractVehicle vehicle) {
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId()).orElse(null);
        return data instanceof RotaryWingVehicleData || data instanceof FixedWingVehicleData;
    }

    private Component resolveMarkerDisplayName(Entity entity) {
        if (entity instanceof AbstractVehicle vehicle) {
            return vehicle.getDisplayName();
        }
        if (entity instanceof AmmoEntity ammo) {
            return Component.literal(resolveWeaponDisplayName(ammo.getWeaponId()));
        }
        return entity.getDisplayName();
    }

    private String resolveWeaponDisplayName(@Nullable ResourceLocation weaponId) {
        if (weaponId == null) {
            return "Unknown";
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data().getName())
                .filter(name -> name != null && !name.isBlank())
                .orElse(weaponId.toString());
    }

    private double resolveEntitySpeedKmh(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet) {
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation dimension = mc.level != null ? mc.level.dimension().location() : null;
            if (!entity.level().isClientSide()) {
                S2CRemoteAmmoSnapshot.Entry remoteEntry = RVP_ClientRemoteAmmoState.getEntry(dimension, entity.getId());
                if (remoteEntry != null) {
                    return remoteEntry.speedKmh();
                }
            }
            return bullet.getCurrentSpeed() * 72.0;
        }
        return entity.getDeltaMovement().length() * 72.0;
    }

    private boolean isGpsMarker(Entity entity) {
        return entity instanceof RVP_BaseBullet bullet && isGpsAmmo(bullet);
    }

    private int resolveMarkerAccentColor(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle, Entity entity, MarkerKind kind) {
        if (entity instanceof AbstractVehicle vehicle) {
            return relationColorForVehicle(player, vehicle);
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            return relationColorForBullet(player, playerVehicle, bullet);
        }
        return relationColorForEntity(player, entity);
    }

    private void centerViewOn(Vec3 pos) {
        followPlayer = false;
        viewWorldX = pos.x;
        viewWorldZ = pos.z;
    }

    private void applyGpsTarget(Vec3 pos, boolean bindNow, boolean openPanel) {
        setGpsFields(pos);
        if (openPanel) {
            openSidebar(SidebarMode.GPS);
        }
        if (bindNow) {
            bindGps();
        }
    }

    private int contextMenuItemCount() {
        return RVP_ClientGPSState.isActive() ? 3 : 2;
    }

    private int contextMenuItemHeight() {
        return 14;
    }

    private int contextMenuHeaderHeight() {
        return 28;
    }

    private int contextMenuWidth() {
        int width = 0;
        for (int i = 0; i < contextMenuItemCount(); i++) {
            width = Math.max(width, this.font.width(contextMenuLabel(i)));
        }
        if (mapContextTarget != null) {
            width = Math.max(width, this.font.width(Component.translatable("gui.ywzj_rvp.tactical_map.cursor",
                    Mth.floor(mapContextTarget.x), Mth.floor(mapContextTarget.y), Mth.floor(mapContextTarget.z))));
        }
        width = Math.max(width, this.font.width(Component.translatable("gui.ywzj_rvp.tactical_map.menu_title")));
        return width + 16;
    }

    private int contextMenuHeight() {
        return contextMenuHeaderHeight() + 6 + contextMenuItemCount() * contextMenuItemHeight();
    }

    private int contextMenuRenderX() {
        int menuWidth = contextMenuWidth();
        int x = mapContextMenuX + 8;
        return Mth.clamp(x, mapLeft + 4, Math.max(mapLeft + 4, mapRight - menuWidth - 4));
    }

    private int contextMenuRenderY() {
        int menuHeight = contextMenuHeight();
        return Mth.clamp(mapContextMenuY, mapTop + 4, Math.max(mapTop + 4, mapBottom - menuHeight - 4));
    }

    private Component contextMenuLabel(int itemIndex) {
        if (itemIndex == 0) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_bind_here");
        }
        if (itemIndex == 1) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_open_gps_panel");
        }
        return Component.translatable("gui.ywzj_rvp.tactical_map.menu_clear_gps");
    }

    private int entityContextMenuItemCount() {
        return entityContextTarget != null && entityContextTarget.targetPos != null ? 5 : 3;
    }

    private int entityContextMenuItemHeight() {
        return 14;
    }

    private int entityContextMenuHeaderHeight() {
        return 28;
    }

    private int entityContextMenuWidth() {
        int width = this.font.width(markerLabel(entityContextTarget));
        width = Math.max(width, this.font.width(Component.translatable("gui.ywzj_rvp.tactical_map.cursor",
                Mth.floor(entityContextTarget.focusPos.x),
                Mth.floor(entityContextTarget.focusPos.y),
                Mth.floor(entityContextTarget.focusPos.z))));
        for (int i = 0; i < entityContextMenuItemCount(); i++) {
            width = Math.max(width, this.font.width(entityContextMenuLabel(i)));
        }
        return width + 16;
    }

    private int entityContextMenuHeight() {
        return entityContextMenuHeaderHeight() + 6 + entityContextMenuItemCount() * entityContextMenuItemHeight();
    }

    private int entityContextMenuRenderX() {
        int menuWidth = entityContextMenuWidth();
        int x = entityContextMenuX + 8;
        return Mth.clamp(x, mapLeft + 4, Math.max(mapLeft + 4, mapRight - menuWidth - 4));
    }

    private int entityContextMenuRenderY() {
        int menuHeight = entityContextMenuHeight();
        return Mth.clamp(entityContextMenuY, mapTop + 4, Math.max(mapTop + 4, mapBottom - menuHeight - 4));
    }

    private Component markerLabel(MarkerHit markerHit) {
        if (markerHit == null) {
            return Component.empty();
        }
        if (markerHit.kind == MarkerKind.PLAYER) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_player");
        }
        return markerHit.entity.getType().getDescription();
    }

    private Component entityContextMenuLabel(int itemIndex) {
        if (itemIndex == 0) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_center_target");
        }
        if (itemIndex == 1) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_open_entity_gps_panel");
        }
        if (itemIndex == 2) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_bind_entity_pos");
        }
        if (itemIndex == 3) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_open_missile_target_panel");
        }
        return Component.translatable("gui.ywzj_rvp.tactical_map.menu_bind_missile_target");
    }

    private void drawDiamond(GuiGraphics guiGraphics, int cx, int cy, int radius, int color, boolean filled) {
        for (int dy = -radius; dy <= radius; dy++) {
            int width = radius - Math.abs(dy);
            if (filled) {
                guiGraphics.fill(cx - width, cy + dy, cx + width + 1, cy + dy + 1, color);
            } else {
                plot(guiGraphics, cx - width, cy + dy, color);
                plot(guiGraphics, cx + width, cy + dy, color);
            }
        }
    }

    private void drawDirectionTick(GuiGraphics guiGraphics, int cx, int cy, float yaw, int inner, int outer, int color) {
        double rad = Math.toRadians(yaw);
        int x1 = cx + Mth.floor((float) (Math.sin(rad) * inner));
        int y1 = cy - Mth.floor((float) (Math.cos(rad) * inner));
        int x2 = cx + Mth.floor((float) (Math.sin(rad) * outer));
        int y2 = cy - Mth.floor((float) (Math.cos(rad) * outer));
        drawLine(guiGraphics, x1, y1, x2, y2, color);
    }

    private void drawLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (true) {
            plot(guiGraphics, x, y, color);
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = err << 1;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void drawDashedWorldLine(GuiGraphics guiGraphics, double worldX0, double worldZ0, double worldX1, double worldZ1,
                                     int dashLength, int gapLength, int color) {
        int x0 = Mth.floor(worldToScreenX(worldX0));
        int y0 = Mth.floor(worldToScreenY(worldZ0));
        int x1 = Mth.floor(worldToScreenX(worldX1));
        int y1 = Mth.floor(worldToScreenY(worldZ1));
        drawDashedLine(guiGraphics, x0, y0, x1, y1, dashLength, gapLength, color);
    }

    private void drawDashedLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int dashLength, int gapLength, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        int pattern = Math.max(1, dashLength + gapLength);
        int step = 0;
        while (true) {
            if (step % pattern < dashLength) {
                plot(guiGraphics, x, y, color);
            }
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = err << 1;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
            step++;
        }
    }

    private void plot(GuiGraphics guiGraphics, int x, int y, int color) {
        if (x < mapLeft || x > mapRight || y < mapTop || y > mapBottom) {
            return;
        }
        guiGraphics.fill(x, y, x + 1, y + 1, color);
    }

    private boolean isOverToolbarButton(double mouseX, double mouseY) {
        return centerButton != null && centerButton.isMouseOver(mouseX, mouseY)
                || followButton != null && followButton.isMouseOver(mouseX, mouseY)
                || weaponDropdownButton != null && weaponDropdownButton.isMouseOver(mouseX, mouseY)
                || radarPanelButton != null && radarPanelButton.isMouseOver(mouseX, mouseY)
                || gpsPanelButton != null && gpsPanelButton.isMouseOver(mouseX, mouseY)
                || gpsModeButton != null && gpsModeButton.isMouseOver(mouseX, mouseY)
                || gpsClearAllButton != null && gpsClearAllButton.isMouseOver(mouseX, mouseY)
                || gpsQuickMarkButton != null && gpsQuickMarkButton.isMouseOver(mouseX, mouseY);
    }

    private boolean handleWeaponDropdownClick(double mouseX, double mouseY, int button) {
        if (!weaponDropdownOpen) {
            return false;
        }
        if (button != 0) {
            if (!isOverWeaponDropdown(mouseX, mouseY)) {
                weaponDropdownOpen = false;
            }
            return false;
        }
        if (!isOverWeaponDropdown(mouseX, mouseY)) {
            weaponDropdownOpen = false;
            return false;
        }
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            weaponDropdownOpen = false;
            return true;
        }
        int index = (Mth.floor(mouseY) - (weaponDropdownY() + 3)) / weaponDropdownItemHeight();
        if (index >= 0 && index < weaponUnit.weapons.size()) {
            selectWeaponByIndex(weaponUnit, index);
        }
        weaponDropdownOpen = false;
        return true;
    }

    private boolean isOverWeaponDropdown(double mouseX, double mouseY) {
        if (!weaponDropdownOpen) {
            return false;
        }
        int x = weaponDropdownX();
        int y = weaponDropdownY();
        int width = weaponDropdownWidth();
        int height = weaponDropdownHeight();
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int weaponDropdownX() {
        return weaponDropdownButton.getX();
    }

    private int weaponDropdownY() {
        return weaponDropdownButton.getY() + weaponDropdownButton.getHeight() + 2;
    }

    private int weaponDropdownWidth() {
        return Math.max(100, weaponDropdownButton.getWidth() + 16);
    }

    private float interpolatedRadarMapYaw(RadarUnit radarUnit, float partialTick) {
        float xRot = Mth.lerp(partialTick, radarUnit.xRotO, radarUnit.getXRot());
        float yRot = Mth.lerp(partialTick, radarUnit.yRotO, radarUnit.getYRot());
        return radarUnit.worldRot(xRot, yRot).y;
    }

    private int weaponDropdownItemHeight() {
        return 14;
    }

    private int weaponDropdownHeight() {
        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        int count = weaponUnit == null ? 0 : weaponUnit.weapons.size();
        return count * weaponDropdownItemHeight() + 6;
    }

    private void selectWeaponByIndex(WeaponUnit weaponUnit, int targetIndex) {
        int size = weaponUnit.weapons.size();
        if (targetIndex < 0 || targetIndex >= size) {
            return;
        }
        int currentIndex = weaponUnit.getCurrentWeaponIndex();
        if (currentIndex == targetIndex) {
            return;
        }
        int forward = (targetIndex - currentIndex + size) % size;
        int backward = (currentIndex - targetIndex + size) % size;
        boolean next = forward <= backward;
        int steps = Math.min(forward, backward);
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        for (int i = 0; i < steps; i++) {
            Channel.CHANNEL.sendToServer(new ClientVehicleSwitchWeapon(
                    vehicle.getId(),
                    ClientVehicleSwitchWeapon.WeaponSwitchType.PRIMARY,
                    next
            ));
        }
    }

    private int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private void drawWorldLine(GuiGraphics guiGraphics, double worldX0, double worldZ0, double worldX1, double worldZ1, int color) {
        int x0 = Mth.floor(worldToScreenX(worldX0));
        int y0 = Mth.floor(worldToScreenY(worldZ0));
        int x1 = Mth.floor(worldToScreenX(worldX1));
        int y1 = Mth.floor(worldToScreenY(worldZ1));
        drawLine(guiGraphics, x0, y0, x1, y1, color);
    }

    private void drawRadarSectorOnMap(GuiGraphics guiGraphics, double worldX, double worldZ, float radiusPixels,
                                      float vehicleYaw, float localStartDeg, float localEndDeg, int segments, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        float centerX = (float) worldToScreenX(worldX);
        float centerY = (float) worldToScreenY(worldZ);
        float a = alpha(color);
        float r = red(color);
        float g = green(color);
        float b = blue(color);

        buffer.vertex(centerX, centerY, 0).color(r, g, b, a).endVertex();
        for (int i = 0; i <= segments; i++) {
            float localAngle = localStartDeg + (localEndDeg - localStartDeg) * (i / (float) segments);
            float mapAngle = mapAngleForRadarLocal(vehicleYaw, localAngle);
            float vx = centerX + Mth.sin((float) Math.toRadians(mapAngle)) * radiusPixels;
            float vy = centerY - Mth.cos((float) Math.toRadians(mapAngle)) * radiusPixels;
            buffer.vertex(vx, vy, 0).color(r, g, b, a).endVertex();
        }
        tesselator.end();
        RenderSystem.disableBlend();
    }

    private void drawRadarScanLineOnMap(GuiGraphics guiGraphics, double worldX, double worldZ, float radiusPixels,
                                        float vehicleYaw, float localScanAngle, float thickness, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float centerX = (float) worldToScreenX(worldX);
        float centerY = (float) worldToScreenY(worldZ);
        float a = alpha(color);
        float r = red(color);
        float g = green(color);
        float b = blue(color);

        float mapAngle = mapAngleForRadarLocal(vehicleYaw, localScanAngle);
        float rad = (float) Math.toRadians(mapAngle);
        float dirX = Mth.sin(rad);
        float dirY = -Mth.cos(rad);
        float perpX = -dirY;
        float perpY = dirX;
        float halfWidth = thickness * 0.5f;

        buffer.vertex(centerX - perpX * halfWidth, centerY - perpY * halfWidth, 0).color(r, g, b, a).endVertex();
        buffer.vertex(centerX + perpX * halfWidth, centerY + perpY * halfWidth, 0).color(r, g, b, a).endVertex();
        buffer.vertex(centerX + dirX * radiusPixels + perpX * halfWidth, centerY + dirY * radiusPixels + perpY * halfWidth, 0)
                .color(r, g, b, a).endVertex();
        buffer.vertex(centerX + dirX * radiusPixels - perpX * halfWidth, centerY + dirY * radiusPixels - perpY * halfWidth, 0)
                .color(r, g, b, a).endVertex();

        tesselator.end();
        RenderSystem.disableBlend();
    }

    private float mapAngleForRadarLocal(float vehicleYaw, float localAngle) {
        return mapIconYaw(vehicleYaw) - localAngle;
    }

    private float computeRadarScanAngle(RadarUnit radarUnit, float partialTick, double yRotO, double yRot) {
        RVP_ClientHmdState hmdState = RVP_ClientHmdState.getInstance();
        if (hmdState.isRadarHmd()) {
            if (radarUnit.getData() instanceof RadarUnitDataExt ext && ext.ywzj_rvp$isEnableHms()) {
                Vec3 aimDir = VectorUtil.rotToVec(hmdState.getSmoothPitch(), hmdState.getSmoothYaw()).normalize();
                Vec3 radarPos = radarUnit.worldRadarPosition();
                Vec3 radarToHead = radarPos.add(aimDir.scale(100)).subtract(radarPos);
                Vec2 localRot = radarUnit.worldVecToLocalRot(radarToHead);
                float yMin = radarUnit.getYRotMin();
                float yMax = radarUnit.getYRotMax();
                if (yMax - yMin >= 360f) {
                    yMin = 0f;
                    yMax = 360f;
                }
                float center = Mth.clamp((float) localRot.y, yMin, yMax);
                int tick = hmdState.getTickCount();
                float phase = ((tick % 5) + partialTick) / 5.0f;
                phase = Mth.clamp(phase, 0f, 1f);
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                return center - 1.5f + 3.0f * pingPong;
            }
        }
        if (radarUnit.getData() instanceof RadarUnitDataExt ext
                && "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode())) {
            int periodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (periodTick > 0) {
                float yRotMin = radarUnit.getYRotMin();
                float yRotMax = radarUnit.getYRotMax();
                float scanAz = yRotMax - yRotMin;
                if (scanAz >= 360f) {
                    yRotMin = 0f;
                    scanAz = 360f;
                }
                int enabledTick = RadarEnabledTickHelper.getEnabledTick(radarUnit);
                int elapsed = radarUnit.getVehicle().tickCount - enabledTick;
                if (elapsed < periodTick) {
                    float ratio = (elapsed + partialTick) / periodTick;
                    return yRotMin + scanAz * Mth.clamp(ratio, 0f, 1f);
                }
                float phase = ((radarUnit.getVehicle().tickCount % periodTick) + partialTick) / periodTick;
                phase = Mth.clamp(phase, 0f, 1f);
                if (scanAz >= 360f) {
                    return phase * 360f;
                }
                float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
                return yRotMin + scanAz * pingPong;
            }
        }
        return (float) Mth.lerp(partialTick, yRotO, yRot);
    }
}
