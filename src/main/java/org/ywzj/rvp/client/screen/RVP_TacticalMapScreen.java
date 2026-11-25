package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.screens.Screen;
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
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapHost;
import org.ywzj.rvp.client.screen.tool.RVP_TacticalMapTool;
import org.ywzj.rvp.client.render.RVP_MapIconTexture;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteAmmoVisualRenderer;
import org.ywzj.rvp.client.gui.RadarEnabledTickHelper;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;
import org.ywzj.rvp.client.state.RVP_ClientProjectileRenderInterpolator;
import org.ywzj.rvp.client.state.RVP_ClientGunnerVehicleState;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientHbmMissileState;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;
import org.ywzj.rvp.client.state.RVP_ClientMarkedBlockState;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;
import org.ywzj.rvp.client.state.RVP_ArtilleryFireControlState;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.client.RVP_ClientEvents;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.network.S2CExternalRadarSnapshot;
import org.ywzj.rvp.network.S2CMarkedBlockSync;
import org.ywzj.rvp.network.C2SSetLoiterCenter;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CRemoteAmmoSnapshot;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.custom.vehicle.FixedWingVehicleData;
import org.ywzj.vehicle.custom.vehicle.RotaryWingVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientRadarAction;
import org.ywzj.vehicle.network.message.ClientVehicleSwitchWeapon;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.util.RenderHelper;
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

public class RVP_TacticalMapScreen extends Screen implements RVP_TacticalMapHost {

    public enum MapMode {
        TACTICAL,
        ARTILLERY
    }

    /** 页面几何仅在开屏、尺寸或面板状态变化时重算。 */
    private RVP_TacticalLayout layout;
    private RVP_TacticalViewport viewport;
    /** 地图头部始终可见的关闭按钮。 */
    private TerminalButton closeButton;
    /** 长武器列表与 GPS 状态正文独立滚动。 */
    private int weaponScroll, gpsInfoScroll;
    private static final String ICON_TEXTURE_ROOT = "textures/gui/map_icon/";
    private static final int OWN_ICON_COLOR = 0xFF84D699;
    private static final int FRIEND_ICON_COLOR = 0xFF74C5FA;
    private static final int HOSTILE_ICON_COLOR = 0xFFFF9187;
    private static final int NEUTRAL_ICON_COLOR = 0xFFE3CB85;
    private static final int UNMANNED_VEHICLE_ICON_COLOR = 0xFFE3CB85;
    private static final int GPS_ICON_COLOR = 0xFFF2CB75;
    private static final int LOITER_ICON_COLOR = FRIEND_ICON_COLOR;
    private static final ResourceLocation PLAYER_ICON = mapIcon("player.png");
    private static final ResourceLocation HELI_ICON = mapIcon("atkheli.png");
    private static final ResourceLocation JET_ICON = mapIcon("jet.png");
    private static final ResourceLocation GROUND_ICON = mapIcon("mbt.png");
    private static final ResourceLocation SHIP_ICON = mapIcon("ship.png");
    public static final ResourceLocation MONSTER_ICON = mapIcon("monster.png");
    public static final ResourceLocation NORMAL_ICON = mapIcon("normal.png");
    private static final ResourceLocation MISSILE_ICON = mapIcon("msl.png");
    private static final ResourceLocation CRUISE_MISSILE_ICON = mapIcon("cruise_msl.png");
    private static final ResourceLocation BOMB_ICON = mapIcon("jdam.png");
    public static final ResourceLocation GPS_ICON = mapIcon("gps.png");
    /** 生物/怪物 icon 尺寸：比玩家（11）和载具（12）小，作为战场次要目标。 */
    private static final int CREATURE_ICON_SIZE = 7;
    private static final long DOUBLE_CLICK_WINDOW_MS = 350L;
    private static final int RADAR_LOCK_ASSIST_TICKS = 80;
    private static final int QUICK_FIRE_TIMEOUT_TICKS = 160;
    private static final int QUICK_FIRE_LOCK_STABLE_TICKS = 2;
    private static final int QUICK_FIRE_RETRY_INTERVAL_TICKS = 2;
    private static final int IMPACT_CROSS_LIFE_TICKS = 20;
    /** 坐标编辑框的高度（GUI 像素）。 */
    private static final int GPS_FIELD_HEIGHT = 16;

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
        private final double distanceMeters;
        private final double altitudeMeters;
        private final double speedKph;
        private final String nctrLabel;
        private final String relationLabel;
        private final int relationColor;
        private final boolean locked;

        private RadarContactRow(int entityId, Entity entity, RadarUnit sourceRadar, Vec3 detectedPos,
                                double distanceMeters, double altitudeMeters, double speedKph,
                                String nctrLabel, String relationLabel, int relationColor, boolean locked) {
            this.entityId = entityId;
            this.entity = entity;
            this.sourceRadar = sourceRadar;
            this.detectedPos = detectedPos;
            this.distanceMeters = distanceMeters;
            this.altitudeMeters = altitudeMeters;
            this.speedKph = speedKph;
            this.nctrLabel = nctrLabel;
            this.relationLabel = relationLabel;
            this.relationColor = relationColor;
            this.locked = locked;
        }
    }

    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private TerminalButton bindButton;
    private TerminalButton clearButton;
    private TerminalButton centerButton;
    private TerminalButton followButton;
    private TerminalButton trajectoryModeButton;
    private TerminalButton weaponDropdownButton;
    /** 普通战术地图上，从当前操作席发射已选武器的按钮。 */
    private TerminalButton fireButton;
    /** 等待服务器确认切换的武器，避免紧接着的发射点击打出上一项。 */
    @Nullable private AbstractVehicleWeapon<?> switchingTo;
    /** 切换未获确认时恢复显示服务器的实际选择。 */
    private long switchDeadlineMillis;
    private TerminalButton radarPanelButton;
    private TerminalButton gpsPanelButton;
    private TerminalButton gpsModeButton;
    private TerminalButton gpsClearAllButton;
    private TerminalButton gpsQuickMarkButton;
    private TerminalButton radarHideFriendlyButton;
    /** 每次打开普通战术地图时开启，重排界面时保留玩家的选择。 */
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
    /** 当前地图帧的 Tick 内进度，供图标、连线、列表和点击区域共用已有位置插值。 */
    private float renderPartialTick;
    /** 单帧实体位置缓存；按对象身份区分，避免同 ID 的本地实体和广播代理串用。 */
    private final Map<Entity, Vec3> renderPositions = new java.util.IdentityHashMap<>();
    private double blocksPerPixel = 2.5;
    private boolean followPlayer = true;
    private boolean draggingMap;
    // 右键拖动优化：记录右键按下时间和初始位置，松开时再判断是否弹出菜单
    private long rightButtonPressGameTime = -1L;
    private double rightButtonPressX;
    private double rightButtonPressY;
    private boolean rightButtonDragged;
    private static final long RIGHT_CLICK_DRAG_THRESHOLD_TICKS = 60L;
    private static final double RIGHT_CLICK_DRAG_THRESHOLD_PIXELS = 5.0;
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
    private boolean pendingQuickFireEverSeekerReadyChecked;
    /** 既有普通/载具炮兵地图模式。 */ private final MapMode mapMode;
    /** 可空的地图工具；终端上下文安装炮火工具，普通地图保持为空。 */ @Nullable private final RVP_TacticalMapTool mapTool;

    private static ResourceLocation mapIcon(String fileName) {
        return new ResourceLocation("ywzj_rvp", ICON_TEXTURE_ROOT + fileName);
    }

    /** @return 当前可空载具客户端上下文；步行玩家或切换时序中允许为空。 */
    @Nullable
    private static LocalVehiclePlayer localVehiclePlayer() {
        return LocalVehiclePlayer.instance;
    }

    /** @return 当前可空武器站；所有地图载具能力统一经过此无载具守卫。 */
    @Nullable
    private static WeaponUnit localWeaponUnit() {
        LocalVehiclePlayer player = localVehiclePlayer();
        // 调用本体载具玩家上下文：仅在实例存在时读取当前武器站，保证步行终端安全。
        return player == null ? null : player.getWeaponUnit();
    }

    /** @return 当前可空载具实体；步行终端不伪造载具。 */
    @Nullable
    private static AbstractVehicle localVehicle() {
        LocalVehiclePlayer player = localVehiclePlayer();
        return player == null ? null : player.vehicle;
    }

    /** @return 本体远程实体缓存的稳定快照；无载具上下文时返回空列表。 */
    private static List<LocalVehiclePlayer.ServerEntity> localServerEntities() {
        LocalVehiclePlayer player = localVehiclePlayer();
        return player == null ? List.of() : List.copyOf(player.serverEntities.values());
    }

    public RVP_TacticalMapScreen() {
        this(MapMode.TACTICAL);
    }

    public RVP_TacticalMapScreen(MapMode mapMode) {
        this(mapMode, null);
    }

    /** 创建保留既有地图模式、并可安装一个独立工具的战术地图。 */
    public RVP_TacticalMapScreen(MapMode mapMode, @Nullable RVP_TacticalMapTool mapTool) {
        super(mapMode == MapMode.ARTILLERY
                ? Component.translatable("gui.ywzj_rvp.tactical_map.artillery.title")
                : Component.translatable("gui.ywzj_rvp.tactical_map.title"));
        this.mapMode = mapMode == null ? MapMode.TACTICAL : mapMode;
        this.mapTool = mapTool;
        this.gpsQuickMarkMode = this.mapMode == MapMode.TACTICAL && mapTool == null;
    }

    public boolean allowsVehicleInputPassthrough() {
        return mapMode == MapMode.ARTILLERY;
    }

    public boolean isArtilleryMode() {
        return mapMode == MapMode.ARTILLERY;
    }

    @Override
    protected void init() {
        var window = Minecraft.getInstance().getWindow();
        viewport = RVP_TacticalViewport.of(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        width = viewport.width();
        height = viewport.height();
        boolean firstOpen = xBox == null;
        String[] draft = firstOpen ? null : new String[] { xBox.getValue(), yBox.getValue(), zBox.getValue() };
        if (firstOpen) syncViewToPlayer(true);
        if (isArtilleryMode()) {
            sidebarVisible = false;
            sidebarMode = SidebarMode.NONE;
        }
        int boxWidth = 68;
        int boxHeight = 16;
        int compactToolbarHeight = 14;

        xBox = new TerminalTextField(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.x"));
        yBox = new TerminalTextField(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.y"));
        zBox = new TerminalTextField(this.font, 0, 0, boxWidth, boxHeight, Component.translatable("gui.ywzj_rvp.gps.coord.z"));
        xBox.setMaxLength(32);
        yBox.setMaxLength(32);
        zBox.setMaxLength(32);
        if (draft == null) syncGpsFields();
        else {
            xBox.setValue(draft[0]);
            yBox.setValue(draft[1]);
            zBox.setValue(draft[2]);
        }

        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);

        int buttonWidth = 72;
        bindButton = addRenderableWidget(new TerminalButton(
                0, 0, buttonWidth, 16,
                Component.translatable("gui.ywzj_rvp.gps.bind"),
                this::bindGps,
                () -> false
        ));
        clearButton = addRenderableWidget(new TerminalButton(
                0, 0, buttonWidth, 16,
                Component.translatable("gui.ywzj_rvp.gps.clear"),
                this::clearGps,
                () -> false
        ));
        centerButton = addRenderableWidget(new TerminalButton(
                0, 0, 44, compactToolbarHeight,
                Component.translatable("gui.ywzj_rvp.tactical_map.btn.center"),
                () -> syncViewToPlayer(true),
                () -> false
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
                () -> followPlayer
        ));
        trajectoryModeButton = addRenderableWidget(new TerminalButton(
                0, 0, 58, compactToolbarHeight,
                artilleryTrajectoryLabel(),
                RVP_ArtilleryFireControlState::toggleTrajectoryMode,
                () -> RVP_ArtilleryFireControlState.getTrajectoryMode()
                        == RVP_ArtilleryFireControlState.TrajectoryMode.HIGH
        ));
        weaponDropdownButton = addRenderableWidget(new TerminalButton(
                0, 0, 92, compactToolbarHeight,
                weaponDropdownLabel(),
                () -> {
                    weaponDropdownOpen = !weaponDropdownOpen;
                    WeaponUnit station = localWeaponUnit();
                    if (weaponDropdownOpen && station != null) weaponScroll = weaponRows().clamp(station.getCurrentWeaponIndex(), station.weapons.size());
                },
                () -> weaponDropdownOpen
        ));
        fireButton = addRenderableWidget(new TerminalButton(0, 0, 40, compactToolbarHeight,
                Component.translatable("gui.ywzj_rvp.tactical_map.btn.fire"), () -> {
                    weaponDropdownOpen = false;
                    // 与原有手动开火共用入口，沿用席位、弹药和冷却判定。
                    RVP_ClientEvents.fireCurrentWeaponClient();
                }, () -> false));
        fireButton.visible = !isArtilleryMode() && mapTool == null;
        radarPanelButton = addRenderableWidget(new TerminalButton(
                0, 0, 54, compactToolbarHeight,
                radarPanelLabel(),
                () -> toggleSidebar(SidebarMode.RADAR),
                () -> sidebarVisible && sidebarMode == SidebarMode.RADAR
        ));
        gpsPanelButton = addRenderableWidget(new TerminalButton(
                0, 0, 50, compactToolbarHeight,
                gpsPanelLabel(),
                () -> toggleSidebar(SidebarMode.GPS),
                () -> sidebarVisible && sidebarMode == SidebarMode.GPS
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
                () -> RVP_ClientGPSState.getMode() != RVP_ClientGPSState.Mode.SINGLE
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
                () -> false
        ));
        gpsQuickMarkButton = addRenderableWidget(new TerminalButton(
                0, 0, 64, compactToolbarHeight,
                gpsQuickMarkLabel(),
                () -> gpsQuickMarkMode = !gpsQuickMarkMode,
                () -> gpsQuickMarkMode
        ));
        radarHideFriendlyButton = addRenderableWidget(new TerminalButton(
                0, 0, 72, compactToolbarHeight,
                radarHideFriendlyLabel(),
                () -> hideFriendlyRadarRows = !hideFriendlyRadarRows,
                () -> hideFriendlyRadarRows
        ));

        closeButton = addRenderableWidget(new TerminalButton(0, 0, 52, compactToolbarHeight,
                Component.translatable("gui.done"), this::onClose, () -> false));
        refreshLayout();
        refreshSidebarWidgets();
        refreshWeaponButtons();
        if (firstOpen && mapTool != null) {
            // 调用本项目地图工具：在画布布局完成后读取服务端同步快照并初始化草稿。
            mapTool.initialize(this);
        }
    }

    private Component followLabel() {
        return followPlayer
                ? Component.translatable("gui.ywzj_rvp.tactical_map.follow_on")
                : Component.translatable("gui.ywzj_rvp.tactical_map.follow_off");
    }

    private Component artilleryTrajectoryLabel() {
        return Component.literal(RVP_ArtilleryFireControlState.getTrajectoryMode()
                == RVP_ArtilleryFireControlState.TrajectoryMode.HIGH
                ? I18n.get("gui.ywzj_rvp.tactical_map.trajectory_high")
                : I18n.get("gui.ywzj_rvp.tactical_map.trajectory_low"));
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
        // [RVP] 模式参数化：SINGLE/MULTI/FAST/RADAR 各自翻译键
        return Component.translatable("gui.ywzj_rvp.tactical_map.btn.gps_mode_"
                + RVP_ClientGPSState.getMode().name().toLowerCase(java.util.Locale.ROOT));
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
        WeaponUnit weaponUnit = localWeaponUnit();
        if (weaponUnit == null) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.btn.weapon");
        }
        Optional<AbstractVehicleWeapon<?>> currentWeapon = weaponUnit.getCurrentWeapon();
        if (currentWeapon.isEmpty()) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.btn.weapon");
        }
        String name = currentWeapon.get().getDisplayName().getString();
        return Component.literal(name);
    }

    private void toggleSidebar(SidebarMode mode) {
        if (isArtilleryMode()) {
            return;
        }
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

    /** 地形铺满屏幕，顶部工具、底部导航、坐标输入和对应命中框使用固定锚点。 */
    private void refreshLayout() {
        layout = RVP_TacticalLayout.of(width, height, (sidebarVisible || mapTool != null) && !isArtilleryMode(),
                mapTool != null, font.width(title));
        mapLeft=0;
        mapTop=layout.mapTop();
        mapRight=layout.mapRight();
        mapBottom=layout.mapBottom();
        sideLeft=layout.sideLeft();
        sideRight=layout.sideRight();
        int pad=layout.margin(), row=layout.buttonHeight(), right=width-pad;
        place(closeButton,right-row,pad,row,row,RVP_MapGlyph.CLOSE);
        place(radarPanelButton,right-row*2-3,pad,row,row,RVP_MapGlyph.RADAR);
        place(gpsPanelButton,right-row*3-6,pad,row,row,RVP_MapGlyph.GPS);
        int pickX=layout.weaponTabX();
        place(weaponDropdownButton,pickX,pad,layout.tabWidth(),row,RVP_MapGlyph.DOWN);
        place(fireButton,layout.fireX(),pad,layout.fireWidth(),row,null);
        place(trajectoryModeButton,pickX,pad,Math.min(194,gpsPanelButton.getX()-pickX-4),row,null);
        int foot=height-pad-row;
        place(centerButton,right-row*2-3,foot,row,row,RVP_MapGlyph.CENTER);
        place(followButton,right-row,foot,row,row,RVP_MapGlyph.FOLLOW);
        if (sideLeft == sideRight) return;
        int x=sidePanelContentX(), w=sidePanelContentWidth(), gap=3;
        boolean sheet=sideLeft==pad && mapTool==null;
        int controlsX=sheet ? x+w/3 : x;
        int modeY=sheet ? sidePanelTitleY() : sidePanelTitleY()+sidePanelTitleHeight()+2;
        int controlsWidth=x+w-controlsX, third=(controlsWidth-2*gap)/3;
        place(gpsModeButton,controlsX,modeY,third,row,null);
        place(gpsQuickMarkButton,controlsX+third+gap,modeY,third,row,null);
        place(gpsClearAllButton,controlsX+2*(third+gap),modeY,controlsWidth-2*(third+gap),row,null);
        place(radarHideFriendlyButton,controlsX,modeY,controlsWidth,row,null);
        int actionY=layout.sideBottom()-4-row, fieldY=actionY-GPS_FIELD_HEIGHT-3;
        EditBox[] fields={xBox,yBox,zBox};
        for (int i=0; i<fields.length; i++) {
            int left=(w+gap)*i/3;
            fields[i].setPosition(x+left,fieldY);
            fields[i].setWidth((w+gap)*(i+1)/3-left-gap);
        }
        int half=(w-gap)/2;
        place(bindButton,x,actionY,half,row,null);
        place(clearButton,x+half+gap,actionY,w-half-gap,row,null);
    }

    private void place(TerminalButton button, int x, int y, int w, int h, @Nullable RVP_MapGlyph icon) {
        button.setPosition(x,y);
        button.setWidth(w);
        button.setHeight(h);
        button.glyph=icon;
    }

    private void refreshSidebarWidgets() {
        if (isArtilleryMode()) {
            xBox.visible = false;
            yBox.visible = false;
            zBox.visible = false;
            xBox.setEditable(false);
            yBox.setEditable(false);
            zBox.setEditable(false);
            bindButton.visible = false;
            bindButton.active = false;
            clearButton.visible = false;
            clearButton.active = false;
            gpsPanelButton.visible = false;
            gpsPanelButton.active = false;
            radarPanelButton.visible = false;
            radarPanelButton.active = false;
            gpsModeButton.visible = false;
            gpsModeButton.active = false;
            gpsClearAllButton.visible = false;
            gpsClearAllButton.active = false;
            gpsQuickMarkButton.visible = false;
            gpsQuickMarkButton.active = false;
            radarHideFriendlyButton.visible = false;
            radarHideFriendlyButton.active = false;
            weaponDropdownButton.visible = false;
            weaponDropdownButton.active = false;
            centerButton.visible = true;
            centerButton.active = true;
            followButton.visible = true;
            followButton.active = true;
            trajectoryModeButton.visible = true;
            trajectoryModeButton.active = true;
            return;
        }
        if (mapTool != null) {
            xBox.visible = false;
            yBox.visible = false;
            zBox.visible = false;
            xBox.setEditable(false);
            yBox.setEditable(false);
            zBox.setEditable(false);
            bindButton.visible = false;
            bindButton.active = false;
            clearButton.visible = false;
            clearButton.active = false;
            gpsPanelButton.visible = false;
            gpsPanelButton.active = false;
            radarPanelButton.visible = false;
            radarPanelButton.active = false;
            gpsModeButton.visible = false;
            gpsModeButton.active = false;
            gpsClearAllButton.visible = false;
            gpsClearAllButton.active = false;
            gpsQuickMarkButton.visible = false;
            gpsQuickMarkButton.active = false;
            radarHideFriendlyButton.visible = false;
            radarHideFriendlyButton.active = false;
            weaponDropdownButton.visible = false;
            weaponDropdownButton.active = false;
            trajectoryModeButton.visible = false;
            trajectoryModeButton.active = false;
            centerButton.visible = true;
            followButton.visible = true;
            return;
        }
        trajectoryModeButton.visible = false;
        trajectoryModeButton.active = false;
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
        gpsModeButton.visible = showGpsSidebar;
        gpsModeButton.active = showGpsSidebar;
        gpsClearAllButton.visible = showGpsSidebar;
        gpsClearAllButton.active = showGpsSidebar;
        gpsQuickMarkButton.visible = showGpsSidebar;
        gpsQuickMarkButton.active = showGpsSidebar;
        radarHideFriendlyButton.visible = showRadarSidebar;
        radarHideFriendlyButton.active = showRadarSidebar;
    }

    private void openSidebar(SidebarMode mode) {
        if (isArtilleryMode()) {
            return;
        }
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
            xBox.setValue(String.format(Locale.ROOT, "%.2f", pos.x));
            yBox.setValue(String.format(Locale.ROOT, "%.2f", pos.y));
            zBox.setValue(String.format(Locale.ROOT, "%.2f", pos.z));
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
        refreshWeaponButtons();
        if (followButton != null) followButton.setMessage(followLabel());
        if (trajectoryModeButton != null) trajectoryModeButton.setMessage(artilleryTrajectoryLabel());
        if (weaponDropdownButton != null) weaponDropdownButton.setMessage(weaponDropdownLabel());
        if (gpsPanelButton != null) gpsPanelButton.setMessage(gpsPanelLabel());
        if (radarPanelButton != null) radarPanelButton.setMessage(radarPanelLabel());
        if (gpsModeButton != null) gpsModeButton.setMessage(gpsModeLabel());
        if (gpsQuickMarkButton != null) gpsQuickMarkButton.setMessage(gpsQuickMarkLabel());
        if (radarHideFriendlyButton != null) radarHideFriendlyButton.setMessage(radarHideFriendlyLabel());
        if (mapTool != null) {
            // 调用本项目地图工具：刷新客户端 profile revision 与权威任务状态。
            mapTool.tick(this);
        }
    }

    /** 同步操作席和服务器已经选中的武器；工具模式使用各自的操作入口。 */
    private void refreshWeaponButtons() {
        if (!fireButton.visible) {
            fireButton.active = false;
            return;
        }
        WeaponUnit station = localWeaponUnit();
        AbstractVehicleWeapon<?> current = station == null ? null : station.getCurrentWeapon().orElse(null);
        if (switchingTo != null) {
            // 代理槽的当前武器可能是子武器；用服务器同步的槽位索引确认切换。
            int target = station == null ? -1 : station.weapons.indexOf(switchingTo);
            if (target < 0 || station.getCurrentWeaponIndex() == target || net.minecraft.Util.getMillis() >= switchDeadlineMillis)
                switchingTo = null;
        }
        weaponDropdownButton.active = switchingTo == null && station != null && !station.weapons.isEmpty();
        fireButton.active = switchingTo == null && current != null;
        if (!weaponDropdownButton.active) weaponDropdownOpen = false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX = viewport.mapX(mouseX);
        mouseY = viewport.mapY(mouseY);
        // 弹层按绘制逆序命中，覆盖在雷达或工具栏前的选项应先接收点击。
        if (weaponDropdownOpen && isOverWeaponDropdown(mouseX, mouseY) && handleWeaponDropdownClick(mouseX, mouseY, button)) return true;
        if (entityContextMenuVisible && handleEntityContextMenuClick(mouseX, mouseY, button)) return true;
        if (mapContextMenuVisible && handleMapContextMenuClick(mouseX, mouseY, button)) return true;
        if (mapTool != null && mapTool.mouseClicked(this, mouseX, mouseY, button)) {
            closeTransientMenus();
            followPlayer = false;
            return true;
        }
        if (isArtilleryMode()) {
            if (isOverToolbarButton(mouseX, mouseY)) {
                closeTransientMenus();
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (isOverMap(mouseX, mouseY) && button == 0) {
                // 炮兵地图恢复左键开火（工具栏按钮已在上方分流，普通战术地图走另一分支）。
                RVP_ArtilleryFireControlState.requestFire(pickMapPoint(mouseX, mouseY));
                draggingMap = false;
                closeTransientMenus();
                return true;
            }
            if (isOverMap(mouseX, mouseY) && (button == 1 || button == 2)) {
                draggingMap = true;
                followPlayer = false;
                closeTransientMenus();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
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
            closeEntityContextMenu();
            if (button != 1 || !isOverMap(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        if (mapContextMenuVisible) {
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
                // 右键按下时不立即弹出菜单，先进入拖动模式并记录时间
                // 松开时若持续 < 60 tick 且未拖动，才弹出 GPS/实体菜单
                Minecraft mc = Minecraft.getInstance();
                rightButtonPressGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
                rightButtonPressX = mouseX;
                rightButtonPressY = mouseY;
                rightButtonDragged = false;
                draggingMap = true;
                followPlayer = false;
                closeTransientMenus();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseX = viewport.mapX(mouseX);
        mouseY = viewport.mapY(mouseY);
        if (mapTool != null && mapTool.mouseReleased(this, mouseX, mouseY, button)) {
            return true;
        }
        if (button == 1 && draggingMap && rightButtonPressGameTime >= 0) {
            draggingMap = false;
            long pressDuration = 0L;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                pressDuration = mc.level.getGameTime() - rightButtonPressGameTime;
            }
            // 判断是否发生了显著拖动
            double dx = mouseX - rightButtonPressX;
            double dy = mouseY - rightButtonPressY;
            boolean significantDrag = rightButtonDragged
                    || (dx * dx + dy * dy) > RIGHT_CLICK_DRAG_THRESHOLD_PIXELS * RIGHT_CLICK_DRAG_THRESHOLD_PIXELS;
            // 清理状态
            rightButtonPressGameTime = -1L;
            rightButtonDragged = false;
            // 持续时间 >= 60 tick 或发生拖动 → 视为拖动操作，不弹出菜单
            if (pressDuration >= RIGHT_CLICK_DRAG_THRESHOLD_TICKS || significantDrag) {
                return true;
            }
            // 短按未拖动 → 执行原右键逻辑（快速标记 / 实体菜单 / 地图菜单）
            return handleRightClickRelease(mouseX, mouseY);
        }
        if ((button == 1 || button == 2) && draggingMap) {
            draggingMap = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 右键短按释放时执行的菜单弹出逻辑（从 mouseClicked 迁移）。
     */
    private boolean handleRightClickRelease(double mouseX, double mouseY) {
        if (!isOverMap(mouseX, mouseY)) {
            return true;
        }
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
            if (sidebarVisible && sidebarMode == SidebarMode.RADAR) {
                return true;
            }
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

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mouseX = viewport.mapX(mouseX);
        mouseY = viewport.mapY(mouseY);
        dragX = viewport.mapX(dragX);
        dragY = viewport.mapY(dragY);
        if (mapTool != null && mapTool.mouseDragged(this, mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        if (draggingMap && (button == 1 || button == 2) && isOverMap(mouseX, mouseY)) {
            if (button == 1) {
                rightButtonDragged = true;
            }
            closeTransientMenus();
            viewWorldX -= dragX * blocksPerPixel;
            viewWorldZ -= dragY * blocksPerPixel;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        mouseX = viewport.mapX(mouseX);
        mouseY = viewport.mapY(mouseY);
        if (weaponDropdownOpen && isOverWeaponDropdown(mouseX, mouseY)) {
            WeaponUnit station = localWeaponUnit();
            if (station != null) weaponScroll = weaponRows().clamp(weaponScroll - (int)Math.signum(delta), station.weapons.size());
            return true;
        }
        if (mapTool != null && mapTool.mouseScrolled(this, mouseX, mouseY, delta)) return true;
        if (sidebarVisible && sidebarMode == SidebarMode.GPS && mouseX >= sideLeft && mouseX < sideRight &&
                mouseY >= sidePanelTitleY() + sidePanelTitleHeight() && mouseY < xBox.getY() - font.lineHeight) {
            gpsInfoScroll = Math.max(0, gpsInfoScroll - (int)Math.signum(delta));
            return true;
        }
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
        return mouseX >= mapLeft && mouseX < mapRight && mouseY >= mapTop && mouseY < mapBottom
                && !layout.overDrawer(mouseX,mouseY);
    }

    @Override
    public double screenToWorldX(double screenX) {
        return viewWorldX + (screenX - (mapLeft + mapRight) * 0.5) * blocksPerPixel;
    }

    @Override
    public double screenToWorldZ(double screenY) {
        return viewWorldZ + (screenY - (mapTop + mapBottom) * 0.5) * blocksPerPixel;
    }

    @Override
    public double worldToScreenX(double worldX) {
        return (mapLeft + mapRight) * 0.5 + (worldX - viewWorldX) / blocksPerPixel;
    }

    @Override
    public double worldToScreenY(double worldZ) {
        return (mapTop + mapBottom) * 0.5 + (worldZ - viewWorldZ) / blocksPerPixel;
    }

    @Override
    public Vec3 pickMapPoint(double mouseX, double mouseY) {
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
        return cachedHeight != null ? cachedHeight + 1 : y;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        // 关闭时丢弃采样内容并保留显存分配，下一次打开仍采样当前帧。
        RVP_MapFrost.suspend();
        // 调用炮兵火控取消尚未提交的发射请求。
        if (isArtilleryMode()) RVP_ArtilleryFireControlState.cancelPendingFire();
        super.removed();
    }

    @Override
    public void onClose() {
        if (mapTool != null) {
            // 调用本项目地图工具：结束本次 Screen 的方向手柄拖动态。
            mapTool.onClose();
        }
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.pose().pushPose();
        try {
            guiGraphics.pose().scale(viewport.scaleX(), viewport.scaleY(), 1.0F);
            renderMapScreen(guiGraphics, Mth.floor(viewport.mapX(mouseX)), Mth.floor(viewport.mapY(mouseY)), partialTick);
        } finally {
            guiGraphics.pose().popPose();
        }
        org.ywzj.rvp.client.gui.RVP_HitIndicatorOverlay.renderGui(guiGraphics, partialTick);
    }

    private void renderMapScreen(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderPartialTick = partialTick;
        renderPositions.clear();
        if (followPlayer && !draggingMap && Minecraft.getInstance().player != null) {
            // 调用原版玩家帧插值，让地图跟随中心与逐帧移动的目标保持同一时间基准。
            Vec3 playerPosition = Minecraft.getInstance().player.getPosition(partialTick);
            viewWorldX = playerPosition.x;
            viewWorldZ = playerPosition.z;
        }
        updateArtilleryHoverTarget(mouseX, mouseY);
        guiGraphics.fill(0,0,width,height,0xFF252D30);
        renderTerrainTiles(guiGraphics);
        renderGrid(guiGraphics);
        // 扇面铺到整张底图并参与玻璃采样；图标、页签和侧栏各自保留命中区域。
        refreshTacticalRadarContext();
        renderRadarCoverage(guiGraphics, partialTick, false);
        RVP_TacticalViewport.scissor(guiGraphics, mapLeft, mapTop, mapRight, mapBottom);
        renderMap(guiGraphics);
        guiGraphics.disableScissor();
        // 在绘制界面之前采样地形与实际标记，整页共用一份模糊图像。
        RVP_MapFrost.capture(guiGraphics);
        RVP_TacticalViewport.scissor(guiGraphics, mapLeft,mapTop,mapRight,mapBottom);
        renderGpsMarker(guiGraphics);
        renderMarkedBlocks(guiGraphics);
        guiGraphics.disableScissor();
        renderMapHud(guiGraphics,mouseX,mouseY);
        drawFrame(guiGraphics);
        drawMapFooter(guiGraphics, mouseX, mouseY);
        if (sidebarVisible && !isArtilleryMode()) {
            renderSidePanel(guiGraphics);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (mapTool != null) {
            // 调用本项目地图工具：在既有地图之上绘制落区、动态侧栏和权威任务状态。
            mapTool.renderOverlay(this, guiGraphics, mouseX, mouseY, partialTick);
        }
        if (weaponDropdownOpen && !isArtilleryMode()) {
            renderWeaponDropdown(guiGraphics, mouseX, mouseY);
        }
        if (!isArtilleryMode() && entityContextMenuVisible && entityContextTarget != null) {
            if (entityContextTarget.entity != null && entityContextTarget.entity.isRemoved()) {
                closeEntityContextMenu();
            } else {
                renderEntityContextMenu(guiGraphics, mouseX, mouseY);
            }
        }
        if (!isArtilleryMode() && mapContextMenuVisible && mapContextTarget != null) {
            renderMapContextMenu(guiGraphics, mouseX, mouseY);
        }
        // 细轮廓和扫描线在控件之后按原分辨率画一次，跨过页签和底栏时仍然连续。
        renderRadarCoverage(guiGraphics, partialTick, true);
        renderControlTip(guiGraphics,mouseX,mouseY);
    }

    private void updateArtilleryHoverTarget(int mouseX, int mouseY) {
        if (!isArtilleryMode() || draggingMap || RVP_ArtilleryFireControlState.hasPendingFire()
                || !isOverMap(mouseX, mouseY)) {
            return;
        }
        Vec3 picked = pickMapPoint(mouseX, mouseY);
        if (picked != null) {
            RVP_ArtilleryFireControlState.designate(picked);
        }
    }

    private void renderWeaponDropdown(GuiGraphics g, int mx, int my) {
        WeaponUnit station = localWeaponUnit();
        if (station == null || station.weapons.isEmpty()) return;
        int x = weaponDropdownX(), w = weaponDropdownWidth(), count = station.weapons.size();
        RVP_MapChrome.ListArea rows = weaponRows();
        RVP_MapChrome.glass(g,x,rows.top(),x+w,rows.top()+Math.min(count,rows.visible())*rows.step());
        weaponScroll = rows.clamp(weaponScroll, count);
        for (int i = weaponScroll; i < Math.min(count, weaponScroll + rows.visible()); i++) {
            int y = rows.y(i, weaponScroll);
            boolean selected = i == station.getCurrentWeaponIndex();
            boolean hover = mx >= x && mx < x+w && my >= y && my < y + rows.step();
            RVP_MapChrome.strip(g,x,y,x+w,y+rows.step(),selected,hover);
            Component label = Component.literal((i+1) + ". ").append(station.weapons.get(i).getDisplayName());
            RVP_MapChrome.scrollingLabel(g,font,label,x+2,y,x+w-2,y+rows.step(),
                    selected ? RVP_MapChrome.INK : RVP_MapChrome.TEXT,false);
        }
    }

    private void drawFrame(GuiGraphics g) {
        int margin=layout.margin();
        RVP_MapChrome.glass(g,0,0,width,mapTop-margin);
        RVP_MapChrome.toolbarTab(g,font,title,margin,margin,layout.tabWidth(),layout.buttonHeight(),false,RVP_MapChrome.TEXT);
        if (fireButton.visible) {
            int x = fireButton.getX(), y = fireButton.getY();
            g.fillGradient(x,y,x+fireButton.getWidth(),y+fireButton.getHeight(),0xA055605F,0xB22A3335);
        }
        if (sideRight>sideLeft)
            RVP_MapChrome.glass(g,sideLeft,layout.sideTop(),sideRight,layout.sideBottom());
    }

    /** 左下角是真实世界距离的标尺，右下角两个原生按钮分别居中和跟随。 */
    private void drawMapFooter(GuiGraphics g, int mx, int my) {
        int pad=layout.margin(), row=layout.buttonHeight(), y=height-pad-row;
        double metres=RVP_TacticalLayout.rulerMeters(blocksPerPixel,Math.min(100,width/6));
        int px=(int)Math.round(metres/blocksPerPixel), origin=2*pad, baseline=y+row/2;
        String distance=metres>=1000 ? (int)(metres/1000)+" km"
                : (int)metres+" m";
        Vec3 pos=isOverMap(mx,my) ? pickMapPoint(mx,my) : null;
        String coords=pos==null ? "" : "X "+Mth.floor(pos.x)+"   Z "+Mth.floor(pos.z);
        int textX=origin+px+6;
        int labelWidth=Math.round(font.width(distance)*RVP_MapChrome.textScale());
        int coordX=textX+labelWidth+12;
        int right=Math.min(centerButton.getX()-pad,coordX+Math.round(font.width(coords)*RVP_MapChrome.textScale())+pad);
        RVP_MapChrome.glass(g,pad,y,right,y+row);
        RVP_MapChrome.glass(g,centerButton.getX(),y,width-pad,y+row);
        g.fill(origin,baseline+2,origin+px+1,baseline+3,RVP_MapChrome.TEXT);
        g.fill(origin,baseline-2,origin+1,baseline+3,RVP_MapChrome.TEXT);
        g.fill(origin+px,baseline-2,origin+px+1,baseline+3,RVP_MapChrome.TEXT);
        int ty=y+(row-RVP_MapChrome.textHeight(font))/2+1;
        RVP_MapChrome.line(g,font,distance,textX,ty,labelWidth+2,RVP_MapChrome.TEXT);
        RVP_MapChrome.line(g,font,coords,coordX,ty,right-coordX-3,RVP_MapChrome.MUTED);
    }

    private void renderMap(GuiGraphics guiGraphics) {
        markerHits.clear();
        beginTrackedAmmoFrame();
        if (!isArtilleryMode()) {
            renderGpsAmmoLinks(guiGraphics);
            renderArhAmmoLinks(guiGraphics);
        }
        renderLocalProjectiles(guiGraphics);
        renderRemoteAmmoCache(guiGraphics);
        renderRemoteHbmMissiles(guiGraphics);
        finishTrackedAmmoFrame();
        renderLocalVehicles(guiGraphics);
        renderLocalContacts(guiGraphics);
        renderRemoteEntities(guiGraphics);
        renderVehicleAndPlayer(guiGraphics);
        renderLoiterCenter(guiGraphics);
        renderArtilleryCcipMarker(guiGraphics);
        renderRecentImpactCrosses(guiGraphics);
        syncSelectedMarkerHit();
        renderSelectedMarkerHighlight(guiGraphics);
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
            // 调用本项目地图显示位置，消失标记落在最后绘出的弹体位置。
            trackAmmoPosition(bullet.getId(), mapRenderPosition(bullet), resolveProjectileKind(bullet));
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
        for (LocalVehiclePlayer.ServerEntity serverEntity : localServerEntities()) {
            if (!(serverEntity.entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (RVP_RemoteAmmoVisualRenderer.isVisualOnlyRvpAmmo(bullet)) {
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
        // 调用本项目统一显示位置，使制导连线起点贴合弹体图标。
        Vec3 renderPosition = mapRenderPosition(bullet);
        drawDashedWorldLine(guiGraphics,
                renderPosition.x, renderPosition.z,
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
        // 调用本项目快照平滑入口，与只有快照的弹体图标保持一致。
        Vec3 renderPosition = remoteAmmoRenderPosition(entry);
        drawDashedWorldLine(guiGraphics,
                renderPosition.x, renderPosition.z,
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
        for (LocalVehiclePlayer.ServerEntity serverEntity : localServerEntities()) {
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
        // 调用本项目统一显示位置，使主动雷达导弹的连线始终从图标出发。
        Vec3 renderPosition = mapRenderPosition(bullet);
        if (bullet.isActiveRadarCatch()) {
            drawWorldLine(guiGraphics,
                    renderPosition.x, renderPosition.z,
                    target.x, target.z,
                    withAlpha(baseColor, 0xD8));
        } else {
            drawDashedWorldLine(guiGraphics,
                    renderPosition.x, renderPosition.z,
                    target.x, target.z,
                    3, 3,
                    withAlpha(baseColor, 0xA8));
        }
        return true;
    }

    public static boolean isOwnAmmo(Entity player, AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
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
            // 调用本项目显示位置解析，仅调整地图连线终点，不回写弹体制导目标。
            return mapRenderPosition(targetEntity);
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
        double minZ = screenToWorldZ(0);
        double maxZ = screenToWorldZ(height);

        RVP_TacticalViewport.scissor(guiGraphics, 0, 0, width, height);
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
        for (LocalVehiclePlayer.ServerEntity serverEntity : localServerEntities()) {
            Entity entity = serverEntity.entity;
            if (entity == null) {
                continue;
            }
            if (mc.level != null && mc.level.getEntity(entity.getId()) != null) {
                continue;
            }
            if (RVP_RemoteAmmoVisualRenderer.isVisualOnlyRvpAmmo(entity)) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(player, playerVehicle, entity)) {
                continue;
            }
            if (entity instanceof AbstractVehicle) {
                if (playerVehicle != null && isDuplicateOfPlayerVehicle(playerVehicle, (AbstractVehicle) entity)) {
                    continue;
                }
                drawVehicleMarker(guiGraphics, (AbstractVehicle) entity, relationColorForVehicle(player, (AbstractVehicle) entity), MarkerKind.REMOTE_VEHICLE);
            } else if (entity instanceof RVP_BaseBullet bullet) {
                if (!shouldRenderProjectileMarker(bullet)) {
                    continue;
                }
                drawMissileMarker(guiGraphics, bullet, relationColorForBullet(player, playerVehicle, bullet));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
            } else if (isBaseOrdnance(entity) && entity instanceof AmmoEntity ammo) {
                drawMissileMarker(guiGraphics, entity, relationColorForBaseAmmo(player, playerVehicle, ammo));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
            } else if (RVP_RadarContactHelper.isHbmMissile(entity)) {
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
            drawVehicleMarker(guiGraphics, vehicle, relationColorForVehicle(player, vehicle), MarkerKind.REMOTE_VEHICLE);
        }
    }

    private void renderLocalContacts(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            return;
        }
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        Set<Integer> renderedIds = new HashSet<>();
        for (Entity rawEntity : mc.level.entitiesForRendering()) {
            Entity entity = RVP_RadarContactHelper.resolveRadarIdentity(rawEntity);
            if (entity == null || !renderedIds.add(entity.getId())) {
                continue;
            }
            if (entity == player
                    || entity == playerVehicle
                    || entity instanceof AbstractVehicle
                    || entity instanceof RVP_BaseBullet
                    || entity instanceof Player) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(player, playerVehicle, entity)) {
                continue;
            }
            if (isBaseOrdnance(entity) && entity instanceof AmmoEntity ammo) {
                drawMissileMarker(guiGraphics, entity, relationColorForBaseAmmo(player, playerVehicle, ammo));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
                continue;
            }
            if (RVP_RadarContactHelper.isHbmMissile(entity)) {
                drawMissileMarker(guiGraphics, entity, relationColorForEntity(player, entity));
                registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
                continue;
            }
            drawContactMarker(guiGraphics, entity, relationColorForEntity(player, entity));
            registerMarkerHit(entity, MarkerKind.CONTACT, entity.getX(), entity.getY(), entity.getZ(), 7, null);
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
            // 调用本项目快照插值，使消失标记与弹体图标使用相同位置。
            trackAmmoPosition(entry.entityId(), remoteAmmoRenderPosition(entry), entry.weaponKind());
            drawRemoteAmmoMarker(guiGraphics, entry);
            registerRemoteAmmoHit(entry);
        }
    }

    private void renderRemoteHbmMissiles(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            return;
        }
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        ResourceLocation dimension = mc.level.dimension().location();
        Set<Integer> renderedIds = new HashSet<>();
        for (Entity proxy : RVP_ClientHbmMissileState.getProxyEntities(dimension)) {
            Entity entity = RVP_RadarContactHelper.resolveRadarIdentity(proxy);
            if (entity == null || !renderedIds.add(entity.getId())) {
                continue;
            }
            if (mc.level.getEntity(entity.getId()) != null) {
                continue;
            }
            if (!shouldShowEntityOnTacticalMap(player, playerVehicle, entity)) {
                continue;
            }
            drawMissileMarker(guiGraphics, entity, relationColorForEntity(player, entity));
            registerMarkerHit(entity, MarkerKind.MISSILE, entity.getX(), entity.getY(), entity.getZ(), 7, getMarkerTargetPos(entity));
        }
    }

    private void refreshTacticalRadarContext() {
        tacticalRadarUnits.clear();
        tacticalRadarVisibleIds.clear();
        WeaponUnit weaponUnit = localWeaponUnit();
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
        for (S2CExternalRadarSnapshot.Entry entry : currentExternalRadarEntries()) {
            tacticalRadarVisibleIds.add(entry.entityId());
        }
        tacticalRadarVisibleIds.addAll(RVP_ClientTacticalRevealState.getVisibleIds(currentLevelDimension()));
    }

    private void renderRadarCoverage(GuiGraphics guiGraphics, float partialTick, boolean foreground) {
        Minecraft mc = Minecraft.getInstance();
        if (tacticalRadarUnits.isEmpty()
                && !(mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle)) {
            return;
        }
        guiGraphics.flush();
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            float radarYaw = interpolatedRadarMapYaw(radarUnit, partialTick);
            // 调用本项目雷达源插值，保留雷达部件偏移并平滑扫描扇区的移动。
            Vec3 radarPosition = RVP_ClientProjectileRenderInterpolator.resolveRadarEmitterPosition(
                    radarUnit.getVehicle().getId(), radarUnit.worldRadarPosition(), partialTick);
            double centerWorldX = radarPosition.x;
            double centerWorldZ = radarPosition.z;
            float radiusPixels = (float) Math.max(1.0, radarUnit.getMaxScanDistance() / blocksPerPixel);
            float yRotMin = radarUnit.getYRotMin();
            float yRotMax = radarUnit.getYRotMax();
            if (yRotMax - yRotMin >= 360.0f) {
                yRotMin = 0.0f;
                yRotMax = 360.0f;
            }
            int relation=relationColorForVehicle(mc.player,radarUnit.getVehicle());
            int sectorColor=withAlpha(relation,36), lineColor=withAlpha(relation,195);
            int segments = Math.max(16, Mth.ceil(Math.abs(yRotMax - yRotMin) / 8.0f));
            drawRadarSectorOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels, radarYaw, yRotMin, yRotMax,
                    segments, foreground ? withAlpha(relation,145) : sectorColor, foreground);
            if (!foreground) continue;

            Entity lockedEntity = radarUnit.getLockedEntity();
            if (lockedEntity == null) {
                float scanAngle = computeRadarScanAngle(radarUnit, partialTick, radarUnit.yRotO, radarUnit.getYRot());
                drawRadarScanLineOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels, radarYaw, scanAngle, 1.0f, lineColor);
            } else {
                // 调用本项目地图显示位置，让锁定线准确连到目标图标。
                Vec3 lockedPosition = mapRenderPosition(lockedEntity);
                drawWorldLine(guiGraphics, centerWorldX, centerWorldZ, lockedPosition.x, lockedPosition.z, lineColor);
            }
        }
        if (mc.level != null && mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle launcher) {
            List<S2CExternalRadarSnapshot.RadarSector> externalSectors =
                    RVP_ClientExternalRadarState.getSectors(mc.level.dimension().location(), launcher.getUUID());
            int sectorColor=withAlpha(FRIEND_ICON_COLOR,36), lineColor=withAlpha(FRIEND_ICON_COLOR,195);
            for (S2CExternalRadarSnapshot.RadarSector sector : externalSectors) {
                Vec3 pos = sector.position();
                double centerWorldX = pos.x;
                double centerWorldZ = pos.z;
                float radiusPixels = (float) Math.max(1.0, sector.maxDistance() / blocksPerPixel);
                float yRotMin = sector.yRotMin();
                float yRotMax = sector.yRotMax();
                float span = yRotMax - yRotMin;
                if (yRotMin == 0.0f && span >= 360.0f) {
                    yRotMin = 0.0f;
                    yRotMax = 360.0f;
                }
                int segments = Math.max(16, Mth.ceil(Math.abs(yRotMax - yRotMin) / 8.0f));
                drawRadarSectorOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels,
                        sector.yaw(), yRotMin, yRotMax, segments, foreground ? withAlpha(FRIEND_ICON_COLOR,145) : sectorColor, foreground);
                if (!foreground) continue;
                Entity externalLocked = getExternalRadarLockedEntity();
                if (externalLocked != null && externalLocked.isAlive()) {
                    // 调用本项目统一位置，外置雷达锁定线与目标图标同源。
                    Vec3 lockedPosition = mapRenderPosition(externalLocked);
                    drawWorldLine(guiGraphics, centerWorldX, centerWorldZ,
                            lockedPosition.x, lockedPosition.z, lineColor);
                } else {
                    int lockedId = RVP_ClientExternalRadarState.getLockedEntityId(mc.level.dimension().location(), launcher.getUUID());
                    S2CExternalRadarSnapshot.Entry lockedEntry =
                            lockedId == Integer.MIN_VALUE ? null
                                    : RVP_ClientExternalRadarState.getEntry(mc.level.dimension().location(), launcher.getUUID(), lockedId);
                    if (lockedEntry != null) {
                        // 调用本项目外置快照位置解析，复用 HUD 已有的短时外推。
                        Vec3 lockedPos = RVP_ClientProjectileRenderInterpolator.resolveExternalRadarEntryPosition(
                                lockedEntry, partialTick);
                        drawWorldLine(guiGraphics, centerWorldX, centerWorldZ, lockedPos.x, lockedPos.z, lineColor);
                    } else {
                        float scanAngle = externalScanAngle(sector, partialTick, mc.player.tickCount);
                        drawRadarScanLineOnMap(guiGraphics, centerWorldX, centerWorldZ, radiusPixels,
                                sector.yaw(), scanAngle, 1.0f, lineColor);
                    }
                }
            }
        }
    }

    private float externalScanAngle(S2CExternalRadarSnapshot.RadarSector sector, float partialTick, int tickCount) {
        float yRotMin = sector.yRotMin();
        float yRotMax = sector.yRotMax();
        float scanAz = yRotMax - yRotMin;
        boolean fullCircle = yRotMin == 0f && scanAz >= 360f;
        int periodTick = 60;
        float phase = ((tickCount % periodTick) + partialTick) / periodTick;
        phase = Mth.clamp(phase, 0f, 1f);
        if (fullCircle) {
            return phase * 360f;
        }
        float pingPong = phase <= 0.5f ? phase * 2f : 2f - phase * 2f;
        return yRotMin + scanAz * pingPong;
    }

    private boolean hasTrackedAmmoEntity(Minecraft mc, int entityId) {
        if (mc.level != null && mc.level.getEntity(entityId) != null) {
            return true;
        }
        for (LocalVehiclePlayer.ServerEntity serverEntity : localServerEntities()) {
            if (serverEntity.entity != null
                    && serverEntity.entity.getId() == entityId
                    && !RVP_RemoteAmmoVisualRenderer.isVisualOnlyRvpAmmo(serverEntity.entity)) {
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
        // 调用本项目快照插值，避免无实体弹体仍按收包周期跳动。
        Vec3 renderPosition = remoteAmmoRenderPosition(entry);
        drawMarkerIcon(guiGraphics,
                resolveRemoteAmmoIcon(entry),
                renderPosition.x,
                renderPosition.z,
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
            drawVehicleMarker(guiGraphics, vehicle, OWN_ICON_COLOR, MarkerKind.OWN_VEHICLE);
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
            // 炮兵地图模式下不显示 GPS 文案，仅保留图标标记
            if (!isArtilleryMode()) {
                String label = "GPS " + (i + 1);
                int w = this.font.width(label);
                int tx = sx - w / 2;
                int ty = sy + 11;
                if (ty + 8 <= mapBottom - 2) {
                    drawWaypointLabel(guiGraphics,label,tx,ty,w);
                }
            }
        }
    }

    /**
     * 绘制吊舱方块标记。目标指示吊舱在 {@code block} 模式下打下的标记点
     * 通过 {@link RVP_ClientMarkedBlockState} 同步到客户端，这里在战术地图上
     * 使用 GPS_ICON + GPS_ICON_COLOR 渲染，并以 "TGT" 标签与普通 GPS 航点区分。
     */
    private void renderMarkedBlocks(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        ResourceLocation dim = mc.level.dimension().location();
        List<S2CMarkedBlockSync.MarkedBlockEntry> blocks = RVP_ClientMarkedBlockState.getMarkedBlocks(dim);
        if (blocks.isEmpty()) {
            return;
        }
        for (S2CMarkedBlockSync.MarkedBlockEntry block : blocks) {
            int sx = Mth.floor(worldToScreenX(block.x()));
            int sy = Mth.floor(worldToScreenY(block.z()));
            if (sx < mapLeft || sx > mapRight || sy < mapTop || sy > mapBottom) {
                continue;
            }
            drawScreenIcon(guiGraphics, GPS_ICON, sx, sy, 12, GPS_ICON_COLOR, false, 0.0f, 1.0f);
            String label = "TGT";
            int w = this.font.width(label);
            int tx = sx - w / 2;
            int ty = sy + 11;
            if (ty + 8 <= mapBottom - 2) {
                drawWaypointLabel(guiGraphics,label,tx,ty,w);
            }
        }
    }

    private void drawWaypointLabel(GuiGraphics g, String text, int x, int y, int w) {
        RVP_MapChrome.glass(g,x-3,y-2,x+w+3,y+font.lineHeight+1);
        g.drawString(font,text,x,y,GPS_ICON_COLOR,true);
    }

    /**
     * 绘制盘旋圆心标记。从 {@link RVP_ClientLoiterState} 读取服务端同步的盘旋圆，
     * 在战术地图上绘制圆心和盘旋半径圆环，风格与 GPS 标记一致。
     */
    private void renderLoiterCenter(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        ResourceLocation dim = mc.level.dimension().location();
        List<RVP_ClientLoiterState.LoiterCircle> circles = RVP_ClientLoiterState.getCircles(dim);
        if (circles.isEmpty()) {
            return;
        }
        for (RVP_ClientLoiterState.LoiterCircle circle : circles) {
            int sx = Mth.floor(worldToScreenX(circle.centerX()));
            int sy = Mth.floor(worldToScreenY(circle.centerZ()));
            float radius=(float)(circle.radius()/blocksPerPixel);
            if (radius<=2 || !isMarkerVisible(sx,sy,(int)radius+2)) continue;
            Entity aircraft=mc.level.getEntity(circle.vehicleEntityId());
            int color=aircraft instanceof AbstractVehicle vehicle ? relationColorForVehicle(mc.player,vehicle) : LOITER_ICON_COLOR;
            // 已设置但未接管的航线使用更淡的颜色，圆心和半径均来自同步记录。
            int orbit=withAlpha(color,circle.active()?192:90);
            drawPatrolOrbit(guiGraphics,sx,sy,radius,orbit);
            if (isMarkerVisible(sx,sy,6)) RVP_MapGlyph.PATROL.draw(guiGraphics,sx,sy,10,color);
        }
    }

    /** 每条航线用一次绘制提交所有虚线段，缩放只改变段数和半径。 */
    private void drawPatrolOrbit(GuiGraphics g, int cx, int cy, float radius, int color) {
        int count = Mth.clamp((int)(Math.PI * radius / 4),20,144);
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix = g.pose().last().pose();
        var buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        for (int i=0; i<count; i++) {
            double a=i*2*Math.PI/count, b=(i+.6)*2*Math.PI/count;
            float ax=(float)Math.cos(a), ay=(float)Math.sin(a), bx=(float)Math.cos(b), by=(float)Math.sin(b);
            buf.vertex(matrix,cx+ax*(radius+.65f),cy+ay*(radius+.65f),0).color(color).endVertex();
            buf.vertex(matrix,cx+ax*(radius-.65f),cy+ay*(radius-.65f),0).color(color).endVertex();
            buf.vertex(matrix,cx+bx*(radius-.65f),cy+by*(radius-.65f),0).color(color).endVertex();
            buf.vertex(matrix,cx+bx*(radius+.65f),cy+by*(radius+.65f),0).color(color).endVertex();
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buf.end());
    }

    private void renderArtilleryCcipMarker(GuiGraphics guiGraphics) {
        if (!isArtilleryMode()) {
            return;
        }
        RVP_ArtilleryFireControlState.Snapshot snapshot = RVP_ArtilleryFireControlState.snapshot();
        Vec3 target = snapshot.target();
        Vec3 hitPos = RVP_ArtilleryFireControlState.getCurrentImpact();

        int targetX = target != null ? Mth.floor(worldToScreenX(target.x)) : Integer.MIN_VALUE;
        int targetY = target != null ? Mth.floor(worldToScreenY(target.z)) : Integer.MIN_VALUE;
        int hitX = hitPos != null ? Mth.floor(worldToScreenX(hitPos.x)) : Integer.MIN_VALUE;
        int hitY = hitPos != null ? Mth.floor(worldToScreenY(hitPos.z)) : Integer.MIN_VALUE;
        boolean aligned = target != null && hitPos != null
                && Math.hypot(targetX - hitX, targetY - hitY) <= 6.0D;

        if (target != null && isMarkerVisible(targetX, targetY, 8)) {
            int circleColor = !snapshot.exact()
                    ? 0xFFFF4A4A
                    : aligned ? 0xFF62F58A : GPS_ICON_COLOR;
            GuiHelper.drawCircle(guiGraphics.pose(), targetX, targetY, 6.0F,
                    circleColor, 0.08F, 0.0F, 0.0F);
        }
        if (hitPos != null && isMarkerVisible(hitX, hitY, 6)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
                drawDashedWorldLine(guiGraphics,
                        vehicle.getX(), vehicle.getZ(), hitPos.x, hitPos.z,
                        6, 4, withAlpha(GPS_ICON_COLOR, 0xA8));
            }
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(hitX, hitY, 0.0D);
            RenderHelper.drawCross(guiGraphics, 0, 0, 5, 0xFFFFFFFF);
            guiGraphics.pose().popPose();
        }
    }

    private void renderMapHud(GuiGraphics g, int mouseX, int mouseY) {
        if (isArtilleryMode()) renderArtilleryStatus(g);
        else if (gpsQuickMarkMode) {
            String hint=I18n.get("gui.ywzj_rvp.tactical_map.gps_quick_mark_status");
            int x=layout.margin(), y=mapTop+2, w=Math.min(width-2*x,font.width(hint)+10);
            RVP_MapChrome.glass(g,x,y,x+w,y+font.lineHeight+6);
            g.drawString(font,font.plainSubstrByWidth(hint,w-8),x+4,y+3,RVP_MapChrome.TEXT,true);
        }
        MarkerHit hover = isOverMap(mouseX,mouseY) && !weaponDropdownOpen && !mapContextMenuVisible && !entityContextMenuVisible
                ? hitTestMarker(mouseX,mouseY) : null;
        MarkerHit detail=hover==null ? selectedMarkerHit : hover;
        renderRoleLabels(g,detail);
        renderSelectedMarkerInfo(g,detail);
    }

    /** 放大地图时给正在盘旋的飞机和当前数据链雷达加短签；密集区域优先保留实体轮廓。 */
    private void renderRoleLabels(GuiGraphics g, @Nullable MarkerHit detail) {
        Minecraft mc = Minecraft.getInstance();
        if (width < 600 || mc.getWindow().getGuiScale() * viewport.scaleX() / blocksPerPixel < 2.2 || mc.level == null) return;
        AbstractVehicle own = localVehicle();
        java.util.UUID relay = own == null ? null :
                RVP_ClientExternalRadarState.getRelayVehicleUuid(mc.level.dimension().location(), own.getUUID());
        List<net.minecraft.client.renderer.Rect2i> occupied = new ArrayList<>();
        for (MarkerHit hit : markerHits) {
            if (!(hit.entity instanceof AbstractVehicle vehicle) || hit == detail) continue;
            boolean patrol = RVP_ClientLoiterState.isVehicleLoitering(hit.entityId);
            if (!patrol && !vehicle.getUUID().equals(relay)) continue;
            String text = patrol ? I18n.get("hud.ywzj_rvp.action.loiter_active") : hit.displayName.getString();
            int x = hit.screenX + hit.radius + 4, y = hit.screenY - font.lineHeight / 2 - 2;
            int w = Math.min(font.width(text), 145) + 8, h = font.lineHeight + 5;
            if (x+w > width-layout.margin() || y < mapTop || y+h > mapBottom ||
                    (sideRight>sideLeft && x+w>sideLeft && y<layout.sideBottom() && y+h>layout.sideTop())) continue;
            boolean crowded = false;
            for (MarkerHit other : markerHits) {
                if (other!=hit && other.screenX+other.radius>x && other.screenX-other.radius<x+w &&
                        other.screenY+other.radius>y && other.screenY-other.radius<y+h) { crowded=true; break; }
            }
            for (var area : occupied) {
                if (area.getX()+area.getWidth()>x && area.getX()<x+w && area.getY()+area.getHeight()>y && area.getY()<y+h) {
                    crowded=true; break;
                }
            }
            if (crowded) continue;
            occupied.add(new net.minecraft.client.renderer.Rect2i(x,y,w,h));
            RVP_MapChrome.glass(g,x,y,x+w,y+h);
            g.drawString(font,font.plainSubstrByWidth(text,w-8),x+4,y+3,RVP_MapChrome.TEXT,true);
        }
    }

    /** 仅在悬停或键盘聚焦时显示图标的文字含义。 */
    private void renderControlTip(GuiGraphics g,int mx,int my) {
        String label=null;
        for (var child:children()) {
            if (child instanceof TerminalButton button && button.visible && button.glyph!=null
                    && button.glyph!=RVP_MapGlyph.DOWN && (button.isMouseOver(mx,my) || (button.isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()))) {
                label=button.getMessage().getString();
                if (!button.isMouseOver(mx,my)) { mx=button.getX(); my=button.getY(); }
                break;
            }
        }
        if (label==null && mx>=layout.margin() && mx<layout.titleRight() && my>=layout.margin() && my<mapTop-layout.margin())
            label=I18n.get("gui.ywzj_rvp.tactical_map.hint");
        if (label==null) return;
        var lines=font.split(Component.literal(label),Math.min(width-18,245));
        int w=0;
        for (var line:lines) w=Math.max(w,font.width(line));
        int h=6+lines.size()*(font.lineHeight+1);
        int x=Mth.clamp(mx+6,2,width-w-10), y=my+h+15<height ? my+12 : my-h-5;
        RVP_MapChrome.glass(g,x,y,x+w+8,y+h);
        for (int i=0;i<lines.size();i++) g.drawString(font,lines.get(i),x+4,y+3+i*(font.lineHeight+1),RVP_MapChrome.TEXT,true);
    }

    private void renderArtilleryStatus(GuiGraphics guiGraphics) {
        RVP_ArtilleryFireControlState.Snapshot snapshot = RVP_ArtilleryFireControlState.snapshot();
        Vec3 target = snapshot.target();
        Minecraft mc = Minecraft.getInstance();
        AbstractVehicle vehicle = mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle value
                ? value : null;
        double distance = target != null && vehicle != null
                ? horizontalDistance(vehicle.position(), target) : Double.NaN;
        WeaponUnit weaponUnit = localWeaponUnit();
        AbstractVehicleWeapon<?> weapon = weaponUnit != null
                ? org.ywzj.rvp.weapon.util.RVP_WeaponLeafResolver.resolveLeaf(weaponUnit).orElse(null) : null;
        int remainAmmo = weapon != null ? weapon.getRemainAmmo() : 0;
        int maxAmmo = weapon != null ? weapon.getMaxCapacity() : 0;

        String elevationText = snapshot.hasSolution()
                ? String.format(Locale.ROOT, "%.1f°", snapshot.elevationDeg()) : "--";
        String distanceText = Double.isFinite(distance)
                ? String.format(Locale.ROOT, "%.0fm", distance) : "--";
        // [RVP] i18n（2026-09-28）：仰角/落点/解算/残弹文案走 lang；布局测量用本地化后的串
        String line1 = I18n.get("gui.ywzj_rvp.tactical_map.artillery.elevation", elevationText, distanceText);
        String hitText = snapshot.exact()
                ? I18n.get("gui.ywzj_rvp.tactical_map.artillery.hit")
                : I18n.get("gui.ywzj_rvp.tactical_map.artillery.no_solution");
        int hitColor = snapshot.exact() ? 0xFF62F58A : 0xFFFF5B5B;
        String ammoText = maxAmmo > 0 ? remainAmmo + "/" + maxAmmo : Integer.toString(remainAmmo);

        int x = mapLeft + layout.margin();
        int y = mapTop + layout.buttonHeight();
        int right = mapRight - layout.margin();
        RVP_MapChrome.line(guiGraphics, font, line1, x, y, right - x, 0xFFE5EDF6);
        y += RVP_MapChrome.textHeight(font) + 2;
        String solutionLabel = I18n.get("gui.ywzj_rvp.tactical_map.artillery.solution");
        RVP_MapChrome.line(guiGraphics, font, solutionLabel, x, y, right - x, 0xFF9CA9B8);
        x += (int) Math.ceil(font.width(solutionLabel) * RVP_MapChrome.textScale());
        RVP_MapChrome.line(guiGraphics, font, hitText, x, y, right - x, hitColor);
        x += (int) Math.ceil(font.width(hitText) * RVP_MapChrome.textScale());
        RVP_MapChrome.line(guiGraphics, font, I18n.get("gui.ywzj_rvp.tactical_map.artillery.ammo", ammoText),
                x, y, right - x, 0xFFE5EDF6);
    }

    private void renderSelectedMarkerInfo(GuiGraphics g, @Nullable MarkerHit hit) {
        if (hit==null) return;
        String name=hit.displayName.getString(), relation=relationLabelForColor(hit.accentColor);
        String telemetry=Math.round(hit.speedKmh)+" km/h     Y "+Mth.floor(hit.focusPos.y)+" m";
        String state=RVP_ClientLoiterState.isVehicleLoitering(hit.entityId) ? I18n.get("hud.ywzj_rvp.action.loiter_active") : "";
        if (hit.gpsAmmo && hit.targetPos!=null) state="GPS   "+Mth.floor(hit.targetPos.x)+"   "+Mth.floor(hit.targetPos.y)+"   "+Mth.floor(hit.targetPos.z);
        int row=RVP_MapChrome.textHeight(font)+4, inset=6;
        int maximum=Math.min(260,width-2*layout.margin());
        int header=font.width(relation)+7+font.width(name);
        int w=Math.min(maximum,2*inset+Math.round(Math.max(header,Math.max(font.width(telemetry),font.width(state)))*RVP_MapChrome.textScale()));
        int h=2*inset+(state.isEmpty()?2:3)*row-4;
        int maxY=sideLeft==layout.margin() && sideRight>sideLeft ? layout.sideTop()-4 : mapBottom;
        int x=hit.screenX+hit.radius+7, y=Mth.clamp(hit.screenY-h/2,mapTop,Math.max(mapTop,maxY-h));
        if (x+w>width-layout.margin() || (sideRight>sideLeft && x+w>sideLeft && y<layout.sideBottom() && y+h>layout.sideTop()))
            x=hit.screenX-hit.radius-7-w;
        x=Mth.clamp(x,layout.margin(),width-w-layout.margin());
        RVP_MapChrome.glass(g,x,y,x+w,y+h);
        int labelWidth=Math.round(font.width(relation)*RVP_MapChrome.textScale())+1;
        RVP_MapChrome.line(g,font,relation,x+inset,y+inset,labelWidth,hit.accentColor);
        RVP_MapChrome.line(g,font,name,x+inset+labelWidth+5,y+inset,w-2*inset-labelWidth-5,RVP_MapChrome.TEXT);
        RVP_MapChrome.line(g,font,telemetry,x+inset,y+inset+row,w-2*inset,RVP_MapChrome.MUTED);
        if (!state.isEmpty()) RVP_MapChrome.line(g,font,state,x+inset,y+inset+row*2,w-2*inset,hit.accentColor);
    }

    /** 与改装页相同的深浅选中态；原版按钮处理键盘和旁白，长标签在按钮内滚动。 */
    private static final class TerminalButton extends net.minecraft.client.gui.components.AbstractButton {
        /** 点击回调与实时选中状态。 */
        /** 图标按钮保留原生焦点、可访问名称以及原有点击回调。 */
        private RVP_MapGlyph glyph;
        private final Runnable action;
        private final java.util.function.BooleanSupplier selected;

        TerminalButton(int x, int y, int w, int h, Component label, Runnable action, java.util.function.BooleanSupplier selected) {
            super(x, y, w, h, label);
            this.action = action;
            this.selected = selected;
        }

        @Override public void onPress() { action.run(); }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
            boolean light = active && selected.getAsBoolean();
            int color=active ? RVP_MapChrome.TEXT : RVP_MapChrome.MUTED;
            int size=Math.max(12,Math.min(19,height-6));
            if (glyph==RVP_MapGlyph.DOWN) {
                RVP_MapChrome.toolbarTab(g,Minecraft.getInstance().font,getMessage(),getX(),getY(),width,height,true,color);
            } else {
                RVP_MapChrome.strip(g,getX(),getY(),getX()+width,getY()+height,light,active && isHoveredOrFocused());
                if (glyph!=null) glyph.draw(g,getX()+width/2,getY()+height/2,size,color);
                else RVP_MapChrome.buttonLabel(g,Minecraft.getInstance().font,getMessage(),getX(),getY(),getX()+width,getY()+height,color);
            }
            if (isFocused() || (glyph==RVP_MapGlyph.DOWN && (light || (active && isHovered()))))
                g.fill(getX()+3,getY()+height-2,getX()+width-3,getY()+height-1,RVP_MapChrome.ACCENT);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private static final class TerminalTextField extends EditBox {

        private TerminalTextField(net.minecraft.client.gui.Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
            setBordered(false);
            setTextColor(0xFFEAF2FB);
            setTextColorUneditable(0xFF93A6BA);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            if (!this.visible) {
                return;
            }
            guiGraphics.fill(getX(),getY(),getX()+width,getY()+height,isFocused() ? 0x60304246 : 0x4830393C);
            if (isFocused()) guiGraphics.fill(getX()+2,getY()+height-1,getX()+width-2,getY()+height,RVP_MapChrome.ACCENT);
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderSelectedMarkerHighlight(GuiGraphics guiGraphics) {
        if (selectedMarkerHit == null) {
            return;
        }
        int cx=selectedMarkerHit.screenX, cy=selectedMarkerHit.screenY;
        int color=selectedMarkerHit.accentColor, radius=selectedMarkerHit.radius+6;
        // 径向透明光晕不遮住轮廓，也不引入矩形选择边框。
        guiGraphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix=guiGraphics.pose().last().pose();
        var buf=Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN,DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(matrix,cx,cy,0).color(withAlpha(color,82)).endVertex();
        for (int i=0;i<=32;i++) {
            double angle=-i*Math.PI/16;
            buf.vertex(matrix,cx+(float)Math.cos(angle)*radius,cy+(float)Math.sin(angle)*radius,0).color(withAlpha(color,0)).endVertex();
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buf.end());
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

        if (mouseY < menuY + headerHeight + 3) return true;
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
        } else {
            // 最后一项：设为盘旋圆心
            RVP_Network.CHANNEL.sendToServer(new C2SSetLoiterCenter(
                    mapContextTarget.x, mapContextTarget.y, mapContextTarget.z));
        }
        closeMapContextMenu();
        return true;
    }

    private void renderMapContextMenu(GuiGraphics g, int mx, int my) {
        if (mapContextTarget != null) drawContextMenu(g, mx, my, false);
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

        if (mouseY < menuY + headerHeight + 3) return true;
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

    private void renderEntityContextMenu(GuiGraphics g, int mx, int my) {
        if (entityContextTarget != null) drawContextMenu(g, mx, my, true);
    }

    /** 地形与实体的快捷菜单共享样式；绘制、悬停、点击均使用相同的行起点。 */
    private void drawContextMenu(GuiGraphics g, int mx, int my, boolean entity) {
        int x = entity ? entityContextMenuRenderX() : contextMenuRenderX();
        int y = entity ? entityContextMenuRenderY() : contextMenuRenderY();
        int w = entity ? entityContextMenuWidth() : contextMenuWidth();
        int count = entity ? entityContextMenuItemCount() : contextMenuItemCount();
        int header = entity ? entityContextMenuHeaderHeight() : contextMenuHeaderHeight();
        int row = entity ? entityContextMenuItemHeight() : contextMenuItemHeight();
        Vec3 point = entity ? entityContextTarget.focusPos : mapContextTarget;
        String title = entity ? markerLabel(entityContextTarget).getString() : I18n.get("gui.ywzj_rvp.tactical_map.menu_title");
        RVP_MapChrome.glass(g, x, y, x+w, y+header+6+count*row);
        RVP_MapChrome.title(g, font, title, x, y, x+w, y+15);
        RVP_MapChrome.line(g, font, I18n.get("gui.ywzj_rvp.tactical_map.cursor", Mth.floor(point.x), Mth.floor(point.y),
                Mth.floor(point.z)), x+6, y+18, w-12, RVP_MapChrome.MUTED);
        for (int i=0; i<count; i++) {
            int top = y + header + 3 + i*row;
            boolean hover = mx >= x && mx < x+w && my >= top && my < top+row;
            RVP_MapChrome.strip(g, x+1, top, x+w-1, top+row, hover, false);
            String label = (entity ? entityContextMenuLabel(i) : contextMenuLabel(i)).getString();
            RVP_MapChrome.line(g, font, label, x+6, top+(row-font.lineHeight)/2, w-12, hover ? RVP_MapChrome.INK : RVP_MapChrome.TEXT);
        }
    }

    private void renderSidePanel(GuiGraphics guiGraphics) {
        if (sidebarMode == SidebarMode.GPS) {
            renderGpsSidePanel(guiGraphics);
        } else if (sidebarMode == SidebarMode.RADAR) {
            renderRadarSidePanel(guiGraphics);
        }
    }

    private void renderGpsSidePanel(GuiGraphics g) {
        int x = sidePanelContentX(), w = sidePanelContentWidth(), y = sidePanelTitleY();
        drawSidePanelTitle(g, x, y, w, Component.translatable("gui.ywzj_rvp.gps.title"));
        int infoTop = gpsModeButton.getY() + gpsModeButton.getHeight() + 3;
        int labelY = xBox.getY() - font.lineHeight - 2;
        List<Component> info = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof AbstractVehicle vehicle) {
            info.add(Component.translatable("gui.ywzj_rvp.tactical_map.vehicle", vehicle.getDisplayName()));
        }
        // 光标坐标已显示在标尺旁，侧栏仅保留当前载具与航点。
        if (RVP_ClientGPSState.isActive()) {
            Vec3 pos = RVP_ClientGPSState.getPos();
            info.add(Component.translatable("gui.ywzj_rvp.tactical_map.gps_current"));
            info.add(Component.literal(String.format(Locale.ROOT, "X %.1f   Y %.1f   Z %.1f", pos.x, pos.y, pos.z)));
            info.add(Component.literal(RVP_ClientGPSState.getMode().name() + "   "
                    + RVP_ClientGPSUtil.currentPointTag() + " / " + RVP_ClientGPSState.getPointCount()));
        } else {
            info.add(Component.translatable("gui.ywzj_rvp.tactical_map.gps_none"));
        }
        // 只滚动状态正文，坐标框和两个操作按钮固定在底部。
        List<net.minecraft.util.FormattedCharSequence> rows = new ArrayList<>();
        for (Component line : info) rows.addAll(font.split(line, Math.max(8, (int)((w - 10) / RVP_MapChrome.textScale()))));
        int rowH = RVP_MapChrome.textHeight(font) + 5;
        RVP_MapChrome.ListArea area = new RVP_MapChrome.ListArea(infoTop, labelY - 3, rowH);
        gpsInfoScroll = area.clamp(gpsInfoScroll, rows.size());
        for (int i = gpsInfoScroll; i < Math.min(rows.size(), gpsInfoScroll + area.visible()); i++) {
            int top = area.y(i, gpsInfoScroll);
            RVP_MapChrome.text(g, font, rows.get(i), x + 4, top + 2, RVP_MapChrome.TEXT);
        }
        g.drawString(font, "X", xBox.getX() + 1, labelY, RVP_MapChrome.MUTED, false);
        g.drawString(font, "Y", yBox.getX() + 1, labelY, RVP_MapChrome.MUTED, false);
        g.drawString(font, "Z", zBox.getX() + 1, labelY, RVP_MapChrome.MUTED, false);
    }

    private void renderRadarSidePanel(GuiGraphics guiGraphics) {
        collectRadarContacts();
        drawSidePanelTitle(guiGraphics, sidePanelContentX(), sidePanelTitleY(), sidePanelContentWidth(),
                Component.translatable("gui.ywzj_rvp.tactical_map.radar_title"));
        renderRadarContactTable(guiGraphics);
    }

    private void collectRadarContacts() {
        radarContactRows.clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        List<S2CExternalRadarSnapshot.Entry> externalEntries = currentExternalRadarEntries();
        if (player == null) {
            radarRowScroll = 0;
            return;
        }
        if (tacticalRadarUnits.isEmpty() && externalEntries.isEmpty()) {
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
                // 调用本项目地图显示位置，列表距离、高度与图标同步，不读取离散探测坐标。
                Vec3 renderPosition = mapRenderPosition(entity);
                double distanceMeters = horizontalDistance(radarPos, renderPosition);
                boolean locked = isRadarLockedEntity(entity);
                radarContactRows.add(new RadarContactRow(
                        entity.getId(),
                        entity,
                        radarUnit,
                        renderPosition,
                        distanceMeters,
                        renderPosition.y,
                        entity.getDeltaMovement().length() * 72.0,
                        resolveRadarNctrLabel(radarUnit, entity),
                        relationLabelForColor(relationColor),
                        relationColor,
                        locked
                ));
            }
        }
        AbstractVehicle currentVehicle = localVehicle();
        // 调用本项目显示位置，使列表测距两端都使用当前帧坐标。
        Vec3 referencePos = mapRenderPosition(currentVehicle != null ? currentVehicle : player);
        for (S2CExternalRadarSnapshot.Entry entry : externalEntries) {
            if (!seen.add(entry.entityId())) {
                continue;
            }
            Entity resolvedEntity = RVP_ExternalRadarLinkHelper.resolveClientEntity(entry.entityId());
            int relationColor;
            if (resolvedEntity != null) {
                int clientColor = relationColorForEntity(player, resolvedEntity);
                // 客户端实体信息不全（如 getDriver() 为 null 导致判定为无人/中立色）时，
                // 优先使用服务端外部雷达的 affiliation 判定
                if (clientColor == UNMANNED_VEHICLE_ICON_COLOR || clientColor == NEUTRAL_ICON_COLOR) {
                    S2CExternalRadarSnapshot.Affiliation affiliation = entry.affiliation();
                    if (affiliation != S2CExternalRadarSnapshot.Affiliation.UNKNOWN) {
                        relationColor = relationColorForAffiliation(affiliation);
                    } else {
                        relationColor = clientColor;
                    }
                } else {
                    relationColor = clientColor;
                }
            } else {
                relationColor = relationColorForAffiliation(entry.affiliation());
            }
            if (hideFriendlyRadarRows && isRealtimeVisibleRelation(relationColor)) {
                continue;
            }
            // 调用本项目外置雷达插值/外推入口，列表和 HUD 共用显示位置。
            Vec3 detectedPos = RVP_ClientProjectileRenderInterpolator.resolveExternalRadarEntryPosition(entry, renderPartialTick);
            radarContactRows.add(new RadarContactRow(
                    entry.entityId(),
                    resolvedEntity,
                    null,
                    detectedPos,
                    horizontalDistance(referencePos, detectedPos),
                    detectedPos.y,
                    RVP_ExternalRadarLinkHelper.speedKph(entry),
                    entry.nctrLabel(),
                    relationLabelForColor(relationColor),
                    relationColor,
                    false
            ));
        }
        radarContactRows.sort(Comparator
                .comparing((RadarContactRow row) -> !row.locked)
                .thenComparingDouble(row -> row.distanceMeters));
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

        if (radarContactRows.isEmpty()) {
            String empty=I18n.get("gui.ywzj_rvp.tactical_map.radar_contact_count",0);
            RVP_MapChrome.line(guiGraphics,font,empty,x+5,y+headerHeight+5,width-10,RVP_MapChrome.MUTED);
        }

        int contentX = x + 4;
        int contentWidth = width - 12;
        drawRadarTableHeader(guiGraphics, contentX, y, contentWidth);

        int visibleRows = radarVisibleRowCount();
        int rowStart = radarRowScroll;
        int rowEnd = Math.min(radarContactRows.size(), rowStart + visibleRows);
        RVP_TacticalViewport.scissor(guiGraphics, x + 1, y + headerHeight + 1, x + width - 7, y + height - 1);
        for (int index = rowStart; index < rowEnd; index++) {
            RadarContactRow row = radarContactRows.get(index);
            int rowY = y + headerHeight + 2 + (index - rowStart) * rowHeight;
            boolean selected = selectedRadarEntityId != null && selectedRadarEntityId == row.entityId;
            if (selected) {
                RVP_MapChrome.strip(guiGraphics, x + 1, rowY, x + width - 7, rowY + rowHeight, true, false);
            } else if ((index - rowStart) % 2 == 0) {
                guiGraphics.fill(x + 1, rowY, x + width - 7, rowY + rowHeight, 0x22131B24);
            }
            drawRadarRow(guiGraphics, row, contentX, rowY + 2, contentWidth);
        }
        guiGraphics.disableScissor();
        drawRadarTableScrollbar(guiGraphics, x, y, width, height, visibleRows);
    }

    /** 识别信息从左读起，三个测量值与各自的表头共用右边线。 */
    private void drawRadarCells(GuiGraphics g, int x, int y, int w, String identity, int tint, String... values) {
        int nameWidth=w*36/100;
        RVP_MapChrome.line(g,font,identity,x,y,nameWidth-3,tint);
        for (int i=0; i<values.length; i++) {
            int left=x+nameWidth+(w-nameWidth)*i/3;
            int right=x+nameWidth+(w-nameWidth)*(i+1)/3;
            String field=font.plainSubstrByWidth(values[i],(int)((right-left-2)/RVP_MapChrome.textScale()));
            int length=(int)Math.ceil(font.width(field)*RVP_MapChrome.textScale());
            RVP_MapChrome.line(g,font,field,right-length,y,length+1,RVP_MapChrome.TEXT);
        }
    }

    private void drawRadarTableHeader(GuiGraphics g, int x, int y, int w) {
        String key="gui.ywzj_rvp.tactical_map.radar_col_";
        drawRadarCells(g,x,y+3,w,I18n.get(key+"nctr"),RVP_MapChrome.MUTED,
                I18n.get(key+"dist"),I18n.get(key+"alt"),I18n.get(key+"speed"));
    }

    private void drawRadarRow(GuiGraphics g, RadarContactRow row, int x, int y, int w) {
        String name=row.nctrLabel==null || row.nctrLabel.isBlank() ? "?" : row.nctrLabel;
        String lock=row.locked ? I18n.get("gui.ywzj_rvp.tactical_map.radar_col_lock")+" " : "";
        drawRadarCells(g,x,y,w,lock+row.relationLabel+" "+name,row.relationColor,
                formatRadarDistanceOrAltitude(row.distanceMeters)+" m",formatRadarDistanceOrAltitude(row.altitudeMeters)+" m",
                formatRadarSpeedKph(row.speedKph)+" km/h");
    }

    private void drawRadarTableScrollbar(GuiGraphics guiGraphics, int x, int y, int width, int height, int visibleRows) {
        int barX = x + width - 5;
        int barTop = y + radarTableHeaderHeight() + 2;
        int barBottom = y + height - 3;
        if (radarContactRows.size() <= visibleRows) return;
        int barHeight = Math.max(12, (barBottom - barTop) * visibleRows / radarContactRows.size());
        int maxScroll = Math.max(1, radarContactRows.size() - visibleRows);
        int travel = Math.max(1, (barBottom - barTop) - barHeight);
        int thumbTop = barTop + travel * radarRowScroll / maxScroll;
        guiGraphics.fill(barX, thumbTop, barX + 2, thumbTop + barHeight, 0xFFBBBAB5);
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
        WeaponUnit weaponUnit = localWeaponUnit();
        LocalPlayer player = Minecraft.getInstance().player;
        if (weaponUnit == null || player == null) {
            return false;
        }
        RadarUnit mainRadar = RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
        boolean externalVisible = hasExternalRadarContact(target != null ? target.getId() : Integer.MIN_VALUE);
        if (mainRadar == null && !externalVisible) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_no_unit"), true);
            return false;
        }
        if (target == null || !tacticalRadarVisibleIds.contains(target.getId())) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_target_unavailable"), true);
            return false;
        }
        boolean lockApplied = false;
        if (externalVisible && (mainRadar == null || !RVP_RadarRoleHelper.radarCurrentlyDetects(mainRadar, target))) {
            lockApplied = RVP_ExternalRadarLinkHelper.applyClientLockRequest(weaponUnit, target);
        }
        if (!lockApplied && mainRadar != null) {
            lockApplied = RVP_RadarRoleHelper.applyRequestedLock(weaponUnit, target);
        }
        if (!lockApplied) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_no_unit"), true);
            return false;
        }
        beginRadarLockAssist(target.getId(), RADAR_LOCK_ASSIST_TICKS);
        selectedRadarEntityId = target.getId();
        selectMarker(findMarkerByEntityId(target.getId()));
        player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_lock_target", radarTargetDisplayName(target)), true);
        return true;
    }

    private boolean tryQuickFireOnTarget(@Nullable Entity target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        WeaponUnit weaponUnit = localWeaponUnit();
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
                || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_need_missile"), true);
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
        WeaponUnit weaponUnit = localWeaponUnit();
        Entity target = findTrackedEntityById(assistedRadarLockEntityId);
        if (weaponUnit != null && target != null && target.isAlive()) {
            RadarUnit mainRadar = RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
            boolean externalVisible = hasExternalRadarContact(target.getId());
            if (externalVisible && (mainRadar == null || !RVP_RadarRoleHelper.radarCurrentlyDetects(mainRadar, target))) {
                RVP_ExternalRadarLinkHelper.applyClientLockRequest(weaponUnit, target);
            } else {
                RVP_RadarRoleHelper.applyRequestedLock(weaponUnit, target);
            }
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
        WeaponUnit weaponUnit = localWeaponUnit();
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
                || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) {
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_need_missile"), true);
            clearPendingQuickFire();
            return;
        }
        Entity target = findTrackedEntityById(pendingQuickFireEntityId);
        RadarUnit mainRadar = RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
        if (target != null && target.isAlive()) {
            boolean hasSeeker = data.hasSeeker();
            boolean isIr = data.isInfrared();

            // Seeker-equipped missiles: auto-enable seeker and assist lock
            if (hasSeeker) {
                if (!weaponUnit.isSeekerOn()) {
                    weaponUnit.toggleSeeker(true);
                }
                boolean externalVisible = hasExternalRadarContact(target.getId());
                if (externalVisible && (mainRadar == null || !RVP_RadarRoleHelper.radarCurrentlyDetects(mainRadar, target))) {
                    RVP_ExternalRadarLinkHelper.applyClientLockRequest(weaponUnit, target);
                } else if (mainRadar != null) {
                    RVP_RadarRoleHelper.applyRequestedLock(weaponUnit, target);
                }
            }

            boolean radarLocked = (mainRadar != null && entityMatches(mainRadar.getLockedEntity(), pendingQuickFireEntityId))
                    || isExternalRadarLockedEntity(target);
            boolean weaponLocked = entityMatches(weaponUnit.getLockedEntity(), pendingQuickFireEntityId);
            boolean seekerReady = hasSeeker && isTargetInQuickFireSeekerEnvelope(weaponUnit, data, target);
            pendingQuickFireEverRadarLocked |= radarLocked;
            pendingQuickFireEverWeaponLocked |= weaponLocked;
            if (hasSeeker) {
                pendingQuickFireEverSeekerReady |= seekerReady;
                pendingQuickFireEverSeekerReadyChecked = true;
            }

            // IR missiles: must acquire target via seeker, otherwise fail
            if (isIr && !seekerReady && pendingQuickFireEverSeekerReady) {
                // Seeker was ready at some point but lost the target — IR can't re-acquire
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_seeker"), true);
                clearPendingQuickFire();
                return;
            }

            // Determine fire readiness based on guidance type
            boolean readyToFire;
            if (hasSeeker) {
                // Seeker missiles: need radar lock + weapon lock + seeker ready
                readyToFire = radarLocked && weaponLocked && seekerReady;
            } else {
                // Non-seeker missiles (GPS, LBR, etc.): only need radar lock + weapon lock
                readyToFire = radarLocked && weaponLocked;
            }

            if (readyToFire) {
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
        pendingQuickFireEverSeekerReadyChecked = false;
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
        RVP_RadarRoleHelper.applyRequestedLock(weaponUnit, target);
        tacticalRadarVisibleIds.add(target.getId());
    }

    private Component resolveQuickFireFailureMessage() {
        if (!pendingQuickFireEverRadarLocked) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_lock");
        }
        if (!pendingQuickFireEverWeaponLocked) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_turret");
        }
        if (pendingQuickFireEverSeekerReadyChecked && !pendingQuickFireEverSeekerReady) {
            return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_seeker");
        }
        return Component.translatable("message.ywzj_rvp.tactical_map.radar_fire_timeout_launch");
    }

    private boolean isTargetInQuickFireSeekerEnvelope(WeaponUnit weaponUnit, RVP_WeaponData data, Entity target) {
        if (!RVP_IrLockHelper.isTargetWithinLaunchAltitudeRange(data, target)) {
            return false;
        }
        WeaponUnit rootWeaponUnit = weaponUnit.getRootParentWeaponUnit();
        Vec3 origin = rootWeaponUnit.aimContext().from;
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double maxRange = Math.max(1.0, data.resolveLaunchLockRange());
        if (origin.distanceToSqr(targetCenter) > maxRange * maxRange) {
            return false;
        }
        Vec3 look = VectorUtil.rotToVec(rootWeaponUnit.aimContext().direction.x, rootWeaponUnit.aimContext().direction.y);
        Vec3 toTarget = targetCenter.subtract(origin);
        if (look.lengthSqr() <= 1.0E-6 || toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double angle = Math.toDegrees(VectorUtil.angleBetween(look.normalize(), toTarget.normalize()));
        double seekerAngle = Math.max(1.0, data.resolveLaunchOffAxisLockAngle());
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
        Entity externalEntity = RVP_ExternalRadarLinkHelper.resolveClientEntity(entityId);
        if (externalEntity != null) {
            return externalEntity;
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
        return sideLeft + 6;
    }

    private int radarTableY() {
        if (radarHideFriendlyButton != null && radarHideFriendlyButton.visible) {
            return radarHideFriendlyButton.getY() + radarHideFriendlyButton.getHeight() + 6;
        }
        return mapTop + 46;
    }

    private int radarTableWidth() {
        return Math.max(1, sideRight - sideLeft - 12);
    }

    private int radarTableHeight() {
        return Math.max(1, layout.sideBottom() - radarTableY() - 5);
    }

    private int radarTableHeaderHeight() {
        return RVP_MapChrome.textHeight(font) + 5;
    }

    private int radarTableRowHeight() {
        return RVP_MapChrome.textHeight(font) + 3;
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

    private int sidePanelContentX() {
        return sideLeft + 6;
    }

    private int sidePanelContentWidth() {
        return Math.max(12, sideRight - sideLeft - 12);
    }

    private int sidePanelTitleY() {
        return layout.sideTop() + 4;
    }

    private int sidePanelTitleHeight() { return layout.buttonHeight(); }

    private void drawSidePanelTitle(GuiGraphics g, int x, int y, int width, Component title) {
        int end=sideLeft==layout.margin() ? x+width/3-3 : x+width;
        RVP_MapChrome.line(g,font,title.getString(),x+2,y+(sidePanelTitleHeight()-RVP_MapChrome.textHeight(font))/2+1,
                end-x-2,RVP_MapChrome.TEXT);
    }

    private RadarUnit getMainRadarUnit() {
        WeaponUnit weaponUnit = localWeaponUnit();
        return weaponUnit != null ? RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit) : null;
    }

    private Entity getMainRadarLockedEntity() {
        RadarUnit mainRadar = getMainRadarUnit();
        Entity localLocked = mainRadar != null ? mainRadar.getLockedEntity() : null;
        if (localLocked != null) {
            return localLocked;
        }
        return getExternalRadarLockedEntity();
    }

    private boolean isRadarLockedEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (isExternalRadarLockedEntity(entity)) {
            return true;
        }
        for (RadarUnit radarUnit : tacticalRadarUnits) {
            Entity locked = radarUnit.getLockedEntity();
            if (locked != null && locked.getId() == entity.getId()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Entity getExternalRadarLockedEntity() {
        AbstractVehicle launcher = localVehicle();
        Minecraft mc = Minecraft.getInstance();
        if (launcher == null || mc.level == null) {
            return null;
        }
        return RVP_ExternalRadarLinkHelper.getClientLockedEntity(launcher, mc.level.dimension().location());
    }

    private boolean isExternalRadarLockedEntity(@Nullable Entity entity) {
        Entity locked = getExternalRadarLockedEntity();
        return entity != null && locked != null && locked.getId() == entity.getId();
    }

    private boolean hasExternalRadarContact(int entityId) {
        AbstractVehicle launcher = localVehicle();
        Minecraft mc = Minecraft.getInstance();
        if (launcher == null || mc.level == null) {
            return false;
        }
        return RVP_ExternalRadarLinkHelper.isClientTrackedByExternalRadar(launcher, mc.level.dimension().location(), entityId);
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
        String special = RVP_RadarContactHelper.resolveShortNctr(entity);
        if (special != null && !special.isBlank()) {
            return special;
        }
        if (entity instanceof FixedWingVehicle) {
            return "JET";
        }
        if (entity instanceof RotaryWingVehicle) {
            return "HELI";
        }
        // 船载具适配（2026-09-20）：本体新增 VesselVehicle 后早期 NCTR 模式不再显示 "?"
        if (entity instanceof org.ywzj.vehicle.entity.vehicle.VesselVehicle) {
            return "SHIP";
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            String label = RVP_RadarContactHelper.resolveBulletRadarLabel(bullet, Float.MAX_VALUE);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return "?";
    }

    private String resolveModernNctrLabel(Entity entity) {
        String special = RVP_RadarContactHelper.resolveShortNctr(entity);
        if (special != null && !special.isBlank()) {
            return special;
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return VehicleUIPresetCache.getNctrName(vehicle.getVehicleId());
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            String radarLabel = RVP_RadarContactHelper.resolveBulletRadarLabel(bullet, Float.MAX_VALUE);
            if (radarLabel != null && !radarLabel.isBlank()) {
                return radarLabel;
            }
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

    private String formatRadarDistanceOrAltitude(double meters) {
        double absMeters = Math.abs(meters);
        if (absMeters < 1000.0) {
            return String.format(Locale.ROOT, "%.0f", meters);
        }
        return String.format(Locale.ROOT, "%.1fK", meters / 1000.0);
    }

    private String formatRadarSpeedKph(double speedKph) {
        return String.format(Locale.ROOT, "%.0f", speedKph);
    }

    /** 同帧只解析一次实体位置，图标、连线、列表和点击区域复用 HUD 已有平滑结果。 */
    private Vec3 mapRenderPosition(Entity entity) {
        // 调用本项目统一位置解析，不另建地图专用的实体运动时间线。
        return renderPositions.computeIfAbsent(entity,
                target -> RVP_ClientProjectileRenderInterpolator.resolveRenderCenter(target, renderPartialTick));
    }

    /** 只有远距弹药快照时使用快照插值；已有实体时与 HUD 共用实体显示位置。 */
    private Vec3 remoteAmmoRenderPosition(S2CRemoteAmmoSnapshot.Entry entry) {
        // 调用本项目实体查找入口，优先复用正常实体或广播弹体的已有轨迹。
        Entity entity = RVP_ExternalRadarLinkHelper.resolveClientEntity(entry.entityId());
        if (entity != null && entity.isAlive()) {
            // 调用本帧位置缓存，与已解析的实体图标保持一致。
            return mapRenderPosition(entity);
        }
        // 调用本项目弹药快照状态，读取跨快照周期的插值位置。
        return RVP_ClientRemoteAmmoState.resolveRenderPosition(entry, renderPartialTick);
    }

    private void drawContactMarker(GuiGraphics guiGraphics, Entity entity, int color) {
        // 调用本帧位置缓存，让普通雷达目标也逐帧移动。
        Vec3 position = mapRenderPosition(entity);
        if (RVP_RadarContactHelper.usesMonsterIcon(entity)) {
            drawMarkerIcon(guiGraphics, MONSTER_ICON, position.x, position.z, entity.getViewYRot(renderPartialTick), CREATURE_ICON_SIZE, 0xFFFFFFFF, false, false);
            return;
        }
        if (RVP_RadarContactHelper.usesNeutralIcon(entity)) {
            drawMarkerIcon(guiGraphics, NORMAL_ICON, position.x, position.z, entity.getViewYRot(renderPartialTick), CREATURE_ICON_SIZE, 0xFFFFFFFF, false, false);
            return;
        }
        drawMarkerIcon(guiGraphics, resolveFallbackEntityIcon(entity), position.x, position.z, entity.getViewYRot(renderPartialTick), 10, color, true, true);
    }

    private void drawVehicleMarker(GuiGraphics guiGraphics, AbstractVehicle vehicle, int color, MarkerKind kind) {
        // 调用本帧位置缓存，广播载具复用与 HUD 相同的跨包插值。
        Vec3 position = mapRenderPosition(vehicle);
        // 以完整窗口短边定尺。切换侧栏、滚轮缩放地形时，坦克图标保持相同的屏幕尺寸。
        int size=RVP_TacticalLayout.trackedMarkerSize(width,height);
        if (!(vehicle instanceof org.ywzj.vehicle.entity.vehicle.TrackedVehicle)) size=Math.max(15,Math.round(size*.86f));
        drawMarkerIcon(guiGraphics, resolveVehicleIcon(vehicle), position.x, position.z, vehicle.getViewYRot(renderPartialTick), size, color, true, true);
        int sx=Mth.floor(worldToScreenX(position.x)), sy=Mth.floor(worldToScreenY(position.z));
        if (isMarkerVisible(sx,sy,size)) {
            if (RVP_ClientLoiterState.isVehicleLoitering(vehicle.getId()))
                RVP_MapGlyph.PATROL.draw(guiGraphics,sx-size/2,sy-size/2,10,color);
            // 实际装配雷达的车辆显示传感器标，工作中的设备按关系色点亮。
            for (var part:vehicle.getPartUnits()) {
                if (part instanceof RadarUnit radar && !radar.isUiHide()) {
                    RVP_MapGlyph.RADAR.draw(guiGraphics,sx+size/2,sy-size/2,10,radar.isOn()?color:0xA9DBE1DE);
                    break;
                }
            }
        }
        registerMarkerHit(vehicle, kind, vehicle.getX(), vehicle.getY(), vehicle.getZ(), (size + 1) / 2 + 1, null);
    }

    private void drawPlayerMarker(GuiGraphics guiGraphics, Player player, int color) {
        // 调用本帧位置缓存，使玩家标记与地图跟随中心逐帧更新。
        Vec3 position = mapRenderPosition(player);
        drawMarkerIcon(guiGraphics, PLAYER_ICON, position.x, position.z, player.getViewYRot(renderPartialTick), 11, color, false, true);
    }

    private void drawMissileMarker(GuiGraphics guiGraphics, Entity entity, int color) {
        // 调用本帧位置缓存，使远距弹体与 HUD 框沿相同轨迹移动。
        Vec3 position = mapRenderPosition(entity);
        drawMarkerIcon(guiGraphics, resolveMissileIcon(entity), position.x, position.z, resolveMarkerYaw(entity), 10, color, true, true);
    }

    private float resolveMarkerYaw(Entity entity) {
        if (RVP_RadarContactHelper.isHbmMissile(entity)) {
            Vec3 velocity = entity.getDeltaMovement();
            double horizontalSqr = velocity.x * velocity.x + velocity.z * velocity.z;
            if (horizontalSqr > 1.0E-6D) {
                return (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D);
            }
        }
        // 本体弹药不保证每 tick 更新实体朝向：火箭弹/炸弹按速度方向画图标，避免图标钉在发射朝向。
        if (isBaseOrdnance(entity)) {
            Vec3 velocity = entity.getDeltaMovement();
            double horizontalSqr = velocity.x * velocity.x + velocity.z * velocity.z;
            if (horizontalSqr > 1.0E-6D) {
                return (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D);
            }
        }
        return entity.getViewYRot(renderPartialTick);
    }

    private void drawTargetMarker(GuiGraphics guiGraphics, int sx, int sy, int color) {
        long millis = System.currentTimeMillis();
        float pulse = 1.0f + ((millis % 900L) / 900.0f) * 0.16f;
        drawScreenIcon(guiGraphics, GPS_ICON, sx, sy, 12, color, false, 0.0f, pulse, true);
    }

    private void drawMarkerIcon(GuiGraphics guiGraphics, ResourceLocation icon, double worldX, double worldZ, float yaw, int size, int color, boolean rotate) {
        drawMarkerIcon(guiGraphics, icon, worldX, worldZ, yaw, size, color, rotate, true);
    }

    private void drawMarkerIcon(GuiGraphics guiGraphics, ResourceLocation icon, double worldX, double worldZ, float yaw, int size, int color,
                                boolean rotate, boolean tint) {
        int sx = Mth.floor(worldToScreenX(worldX));
        int sy = Mth.floor(worldToScreenY(worldZ));
        if (!isMarkerVisible(sx, sy, size / 2 + 2)) {
            return;
        }
        float iconYaw = mapIconYaw(yaw);
        // 2026-10-07 用户定版：删除航向指示短线（drawHeadingTick），icon 旋转本身已表达朝向
        drawScreenIcon(guiGraphics, icon, sx, sy, size, color, rotate, iconYaw, 1.0f, tint);
    }

    private void drawScreenIcon(GuiGraphics guiGraphics, ResourceLocation icon, int centerX, int centerY, int size, int color,
                                boolean rotate, float yaw, float scale) {
        drawScreenIcon(guiGraphics, icon, centerX, centerY, size, color, rotate, yaw, scale, true);
    }

    private void drawScreenIcon(GuiGraphics guiGraphics, ResourceLocation icon, int centerX, int centerY, int size, int color,
                                boolean rotate, float yaw, float scale, boolean tint) {
        float actualSize = size * scale;
        drawIconLayer(guiGraphics, icon, centerX, centerY, actualSize, color, rotate, yaw, tint);
    }

    private void drawIconLayer(GuiGraphics guiGraphics, ResourceLocation icon, int centerX, int centerY, float actualSize,
                               int color, boolean rotate, float yaw, boolean tint) {
        // 2026-10-26 绘制逻辑移交 RVP_MapIconTexture.blitIcon：轻量化炮兵地图与战术地图共用同一 icon 管线
        RVP_MapIconTexture.blitIcon(guiGraphics, icon, centerX, centerY, actualSize, color, rotate, yaw, tint);
    }

    public static int relationColorForVehicle(@Nullable LocalPlayer player, AbstractVehicle vehicle) {
        if (player == null) {
            return FRIEND_ICON_COLOR;
        }
        if (player.getVehicle() == vehicle) {
            return OWN_ICON_COLOR;
        }
        AbstractVehicle playerVehicle = player.getVehicle() instanceof AbstractVehicle v ? v : null;
        if (playerVehicle != null) {
            java.util.UUID relayUuid = RVP_ClientExternalRadarState.getRelayVehicleUuid(
                    player.level().dimension().location(),
                    playerVehicle.getUUID()
            );
            if (relayUuid != null && relayUuid.equals(vehicle.getUUID())) {
                return FRIEND_ICON_COLOR;
            }
        }
        LivingEntity driver = vehicle.getDriver();
        if (driver != null) {
            return relationColorForEntity(player, driver);
        }
        // driver 为 null 时，检查客户端侧表的远程 Gunner 阵营（替代被删 GunnerDataMixin 的 rvpRemoteFaction）
        RVP_EnumGunnerFaction faction = RVP_ClientGunnerVehicleState.getFaction(vehicle);
        if (faction == RVP_EnumGunnerFaction.ENEMY) {
            return HOSTILE_ICON_COLOR;
        }
        if (faction == RVP_EnumGunnerFaction.FRIENDLY) {
            return FRIEND_ICON_COLOR;
        }
        // fallback：检查外部雷达的 affiliation 判定
        S2CExternalRadarSnapshot.Affiliation affiliation = RVP_ExternalRadarLinkHelper.getAffiliation(vehicle);
        if (affiliation != null && affiliation != S2CExternalRadarSnapshot.Affiliation.UNKNOWN) {
            return relationColorForAffiliation(affiliation);
        }
        return UNMANNED_VEHICLE_ICON_COLOR;
    }

    public static int relationColorForPlayer(@Nullable LocalPlayer player, Player target) {
        if (player == null) {
            return FRIEND_ICON_COLOR;
        }
        if (target == player) {
            return OWN_ICON_COLOR;
        }
        return isAlliedTeam(player, target.getTeam()) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
    }

    public static int relationColorForBullet(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle, RVP_BaseBullet bullet) {
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

    /** 本体战术地图需要显示的本体弹药（导弹/火箭弹/航空炸弹）。 */
    public static boolean isBaseOrdnance(Entity entity) {
        return entity instanceof MissileEntity
                || entity instanceof RocketEntity
                || entity instanceof AerialBombEntity;
    }

    /**
     * 本体弹药的关系色：优先按发射载具判定，其次按 owner/队伍。
     * 与 RVP 弹的 {@link #relationColorForBullet} 同语义，保证己方/友军弹药常显、敌方弹药走雷达可见性。
     */
    public static int relationColorForBaseAmmo(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle,
                                               AmmoEntity ammo) {
        if (player == null || ammo == null) {
            return FRIEND_ICON_COLOR;
        }
        AbstractVehicle shooter = ammo.vehicle;
        if (shooter != null && shooter == playerVehicle) {
            return OWN_ICON_COLOR;
        }
        Entity owner = ammo.getOwner();
        if (owner == player) {
            return OWN_ICON_COLOR;
        }
        if (playerVehicle != null && owner != null && playerVehicle.getPassengers().contains(owner)) {
            return OWN_ICON_COLOR;
        }
        if (shooter != null) {
            int color = relationColorForVehicle(player, shooter);
            if (color != NEUTRAL_ICON_COLOR) {
                return color;
            }
        }
        if (owner != null) {
            int color = relationColorForEntity(player, owner);
            if (color != NEUTRAL_ICON_COLOR) {
                return color;
            }
        }
        Team team = ammo.getTeam();
        if (team != null) {
            return isAlliedTeam(player, team) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
        }
        return NEUTRAL_ICON_COLOR;
    }

    private boolean shouldShowEntityOnTacticalMap(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle, Entity entity) {
        if (player == null || entity == null) {
            return true;
        }
        int relationColor = entity instanceof RVP_BaseBullet bullet
                ? relationColorForBullet(player, playerVehicle, bullet)
                : isBaseOrdnance(entity) && entity instanceof AmmoEntity ammo
                ? relationColorForBaseAmmo(player, playerVehicle, ammo)
                : relationColorForEntity(player, entity);
        if (isRealtimeVisibleRelation(relationColor)) {
            return true;
        }
        if (tacticalRadarVisibleIds.contains(entity.getId())) {
            return true;
        }
        return RVP_ClientTacticalRevealState.isVisible(currentLevelDimension(), entity.getId());
    }

    private boolean shouldShowRemoteAmmoOnTacticalMap(S2CRemoteAmmoSnapshot.Entry entry) {
        if (entry.affiliation() == S2CRemoteAmmoSnapshot.Affiliation.OWN
                || entry.affiliation() == S2CRemoteAmmoSnapshot.Affiliation.FRIEND) {
            return true;
        }
        return tacticalRadarVisibleIds.contains(entry.entityId());
    }

    @Nullable
    private ResourceLocation currentLevelDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.dimension().location() : null;
    }

    public static boolean isRealtimeVisibleRelation(int relationColor) {
        int rgb = relationColor & 0x00FFFFFF;
        return rgb == (OWN_ICON_COLOR & 0x00FFFFFF) || rgb == (FRIEND_ICON_COLOR & 0x00FFFFFF);
    }

    public static int relationColorForEntity(@Nullable LocalPlayer player, @Nullable Entity entity) {
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
        if (RVP_RadarContactHelper.forceHostileIff(entity)) {
            return HOSTILE_ICON_COLOR;
        }
        Team team = entity.getTeam();
        if (team == null) {
            return NEUTRAL_ICON_COLOR;
        }
        return isAlliedTeam(player, team) ? FRIEND_ICON_COLOR : HOSTILE_ICON_COLOR;
    }

    public static boolean isAlliedTeam(LocalPlayer player, @Nullable Team team) {
        Team playerTeam = player.getTeam();
        return playerTeam != null && team != null && team.isAlliedTo(playerTeam);
    }

    public static ResourceLocation resolveVehicleIcon(AbstractVehicle vehicle) {
        // 调用本项目载具 JSON 扩展缓存：tactical_map_icon 优先于类型默认图标
        ResourceLocation packConfigured = resolveConfiguredIcon(
                VehicleUIPresetCache.getTacticalMapIcon(vehicle.getVehicleId()));
        if (packConfigured != null) {
            return packConfigured;
        }
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId()).orElse(null);
        if (data != null) {
            ResourceLocation configured = resolveConfiguredIcon(readVehicleIconOverride(data));
            if (configured != null) {
                return configured;
            }
            if (data instanceof org.ywzj.vehicle.custom.vehicle.VesselVehicleData) {
                // 船载具适配（2026-09-20）：本体新增 VesselVehicle 后战术地图不再回落坦克图标；
                // ship.png 目前为程序生成占位，正式素材就绪后直接替换文件
                return SHIP_ICON;
            }
            if (data instanceof RotaryWingVehicleData) {
                return HELI_ICON;
            }
            if (data instanceof org.ywzj.vehicle.custom.vehicle.WheeledVehicleData) return mapIcon("car.png");
            if (data instanceof FixedWingVehicleData) {
                if (org.ywzj.rvp.client.state.RVP_ClientLoiterSupport.applicable(vehicle)) return mapIcon("uav.png");
                return JET_ICON;
            }
        }
        return GROUND_ICON;
    }

    public static ResourceLocation resolveMissileIcon(Entity entity) {
        if (RVP_RadarContactHelper.isHbmMissile(entity)) {
            return CRUISE_MISSILE_ICON;
        }
        // 本体弹药：导弹/火箭弹用导弹图标，航空炸弹用炸弹图标（与 RVP 弹种图标口径一致）
        if (entity instanceof AerialBombEntity) {
            return BOMB_ICON;
        }
        if (entity instanceof MissileEntity || entity instanceof RocketEntity) {
            return MISSILE_ICON;
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            if (resolveProjectileKind(bullet) == RVP_EnumWeaponKind.ROCKET) {
                return MISSILE_ICON;
            }
            ResourceLocation weaponId = bullet.getWeaponId();
            if (weaponId != null) {
                ResourceLocation weaponIcon = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                        .map(index -> index.data())
                        .filter(RVP_WeaponData.class::isInstance)
                        .map(RVP_WeaponData.class::cast)
                        .map(RVP_TacticalMapScreen::resolveWeaponIcon)
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
        if (entry.weaponKind() == RVP_EnumWeaponKind.ROCKET) {
            return MISSILE_ICON;
        }
        if (entry.weaponId() != null) {
            ResourceLocation weaponIcon = CommonAssetsManager.vehicleWeaponManager().getIndex(entry.weaponId())
                    .map(index -> index.data())
                    .filter(RVP_WeaponData.class::isInstance)
                    .map(RVP_WeaponData.class::cast)
                    .map(RVP_TacticalMapScreen::resolveWeaponIcon)
                    .orElse(null);
            if (weaponIcon != null) {
                return weaponIcon;
            }
        }
        return entry.weaponKind() == RVP_EnumWeaponKind.BOMB ? BOMB_ICON : MISSILE_ICON;
    }

    public static ResourceLocation resolveWeaponIcon(RVP_WeaponData weaponData) {
        if (weaponData.getWeaponKind() == RVP_EnumWeaponKind.ROCKET) {
            return MISSILE_ICON;
        }
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
        return kind == RVP_EnumWeaponKind.MISSILE
                || kind == RVP_EnumWeaponKind.ROCKET
                || kind == RVP_EnumWeaponKind.BOMB;
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
        if (weaponKind != RVP_EnumWeaponKind.MISSILE
                && weaponKind != RVP_EnumWeaponKind.ROCKET
                && weaponKind != RVP_EnumWeaponKind.BOMB) {
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

    public static RVP_EnumWeaponKind resolveProjectileKind(RVP_BaseBullet bullet) {
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

    /**
     * 地图屏与战术地图共用的**弹药标记**判定（导弹/火箭弹/炸弹），条件完全同源：
     * 弹种 ∈ {MISSILE, ROCKET, BOMB}（{@link #resolveProjectileKind}）且
     * 关系实时可见（己方/友军）或已被雷达探测（{@code radarVisibleIds}，战术地图对应
     * tacticalRadarVisibleIds）或已被战术揭示（{@link RVP_ClientTacticalRevealState}）。
     *
     * @return 应显示时返回关系色（己方蓝/友军绿/敌对红/中立白），不应显示返回 0
     */
    public static int tacticalAmmoMarkerColor(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle,
                                              RVP_BaseBullet bullet, @Nullable Set<Integer> radarVisibleIds) {
        RVP_EnumWeaponKind kind = resolveProjectileKind(bullet);
        if (kind != RVP_EnumWeaponKind.MISSILE && kind != RVP_EnumWeaponKind.ROCKET
                && kind != RVP_EnumWeaponKind.BOMB) {
            return 0;
        }
        int color = relationColorForBullet(player, playerVehicle, bullet);
        if (isRealtimeVisibleRelation(color)) {
            return color;
        }
        if (radarVisibleIds != null && radarVisibleIds.contains(bullet.getId())) {
            return color;
        }
        if (player != null && RVP_ClientTacticalRevealState.isVisible(
                player.level().dimension().location(), bullet.getId())) {
            return color;
        }
        return 0;
    }

    /**
     * 本体弹药（导弹/火箭弹/航空炸弹）的地图屏标记颜色，条件与 RVP 弹同源：
     * 己方/友军常显，敌方仅雷达探测或战术揭示可见。
     *
     * @return 应显示时返回关系色，不应显示返回 0
     */
    public static int tacticalBaseAmmoMarkerColor(@Nullable LocalPlayer player, @Nullable AbstractVehicle playerVehicle,
                                                  AmmoEntity ammo, @Nullable Set<Integer> radarVisibleIds) {
        if (!isBaseOrdnance(ammo)) {
            return 0;
        }
        int color = relationColorForBaseAmmo(player, playerVehicle, ammo);
        if (isRealtimeVisibleRelation(color)) {
            return color;
        }
        if (radarVisibleIds != null && radarVisibleIds.contains(ammo.getId())) {
            return color;
        }
        if (player != null && RVP_ClientTacticalRevealState.isVisible(
                player.level().dimension().location(), ammo.getId())) {
            return color;
        }
        return 0;
    }

    public static int relationColorForAffiliation(S2CRemoteAmmoSnapshot.Affiliation affiliation) {
        return switch (affiliation) {
            case OWN -> OWN_ICON_COLOR;
            case FRIEND -> FRIEND_ICON_COLOR;
            case HOSTILE -> HOSTILE_ICON_COLOR;
        };
    }

    public static int relationColorForAffiliation(S2CExternalRadarSnapshot.Affiliation affiliation) {
        return switch (affiliation) {
            case OWN -> OWN_ICON_COLOR;
            case FRIEND -> FRIEND_ICON_COLOR;
            case HOSTILE -> HOSTILE_ICON_COLOR;
            case UNKNOWN -> NEUTRAL_ICON_COLOR;
        };
    }

    private List<S2CExternalRadarSnapshot.Entry> currentExternalRadarEntries() {
        Minecraft mc = Minecraft.getInstance();
        AbstractVehicle vehicle = localVehicle();
        if (mc.level == null || vehicle == null) {
            return List.of();
        }
        return List.copyOf(RVP_ExternalRadarLinkHelper.getClientEntries(vehicle, mc.level.dimension().location()));
    }

    public static float mapIconYaw(float yaw) {
        return yaw + 180.0f;
    }

    public static ResourceLocation resolveFallbackEntityIcon(Entity entity) {
        // 被动电子战假目标：以飞机 icon 呈现（用户批示），增强欺骗观感
        if (entity instanceof org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity) {
            return JET_ICON;
        }
        if (entity instanceof Player) {
            return PLAYER_ICON;
        }
        if (RVP_RadarContactHelper.usesMonsterIcon(entity)) {
            return MONSTER_ICON;
        }
        if (RVP_RadarContactHelper.usesNeutralIcon(entity)) {
            return NORMAL_ICON;
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return resolveVehicleIcon(vehicle);
        }
        return GROUND_ICON;
    }

    @Nullable
    private static ResourceLocation resolveConfiguredIcon(String iconValue) {
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
            return new ResourceLocation(parsed.getNamespace(), path);
        }
        String file = raw.endsWith(".png") ? raw : raw + ".png";
        return mapIcon(file);
    }

    @Nullable
    private static String readVehicleIconOverride(BaseVehicleData<?> data) {
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
        // 调用本帧位置缓存，让鼠标命中区域和选中框跟随平滑图标；动作目标仍保留实体真实位置。
        Vec3 position = mapRenderPosition(entity);
        int sx = Mth.floor(worldToScreenX(position.x));
        int sy = Mth.floor(worldToScreenY(position.z));
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
        // 调用本项目快照显示位置，与远距图标及其选中框保持同一屏幕坐标。
        Vec3 position = remoteAmmoRenderPosition(entry);
        int sx = Mth.floor(worldToScreenX(position.x));
        int sy = Mth.floor(worldToScreenY(position.z));
        if (!isMarkerVisible(sx, sy, radius)) {
            return;
        }
        markerHits.add(new MarkerHit(entry.entityId(), null, MarkerKind.MISSILE, sx, sy, radius,
                new Vec3(entry.x(), entry.y(), entry.z()), entry.guidancePos(),
                Component.literal(resolveRemoteAmmoDisplayName(entry)),
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
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation dimension = mc.level != null ? mc.level.dimension().location() : null;
            // 2026-10-06：快照的制导目标点每 5 tick 实时刷新（含视距内弹），
            // 优先于客户端出生快照的 targetPos（中途改靶不反映的冻结值）
            S2CRemoteAmmoSnapshot.Entry remoteEntry = RVP_ClientRemoteAmmoState.getEntry(dimension, entity.getId());
            if (remoteEntry != null && remoteEntry.guidancePos() != null) {
                return remoteEntry.guidancePos();
            }
            Vec3 target = bullet.getTargetPos();
            return target != null ? target : bullet.getLastGuidancePos();
        }
        if (entity instanceof MissileEntity missile) {
            return missile.targetPos;
        }
        if (entity instanceof AerialBombEntity bomb) {
            return bomb.targetPos;
        }
        Vec3 specialTarget = RVP_RadarContactHelper.resolveSpecialTargetPos(entity);
        if (specialTarget != null) {
            return specialTarget;
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
        // 2026-10-06 修复视距内名称退化：本地 RVP 弹优先武器全名（与视距外快照路径同一语义），
        // NCTR 分类短标签（MSL 等）仅作全名解析失败的回退——分类短标签语义保留给雷达航迹列表
        var player = Minecraft.getInstance().player;
        // 本车和已确认的友军直接显示本地化车名；其他目标仍沿用传感器的识别结果。
        if (entity instanceof AbstractVehicle vehicle && player != null
                && isRealtimeVisibleRelation(relationColorForVehicle(player, vehicle))) {
            return vehicle.getDisplayName();
        }
        if (entity instanceof RVP_BaseBullet bullet) {
            String fullName = resolveWeaponDisplayName(bullet.getWeaponId());
            if (fullName != null && !fullName.isBlank() && !"Unknown".equals(fullName)) {
                return Component.literal(fullName);
            }
        }
        String special = resolveModernNctrLabel(entity);
        if (special != null && !special.isBlank() && !"?".equals(special)) {
            return Component.literal(special);
        }
        if (entity instanceof AbstractVehicle vehicle) {
            return Component.literal("?");
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
        // [RVP] 语言感知（name_CN，2026-09-28）：客户端直查配置按当前语言二选一
        return CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId)
                .map(index -> index.data() instanceof org.ywzj.rvp.weapon.data.RVP_WeaponData rvpData
                        ? org.ywzj.rvp.client.util.RVP_LangHelper.resolveWeaponName(rvpData)
                        : index.data().getName())
                .filter(name -> name != null && !name.isBlank())
                .orElse(weaponId.toString());
    }

    /**
     * [RVP] 远程弹药显示名语言感知（name_CN，2026-09-28）：S2C 包的服务端解析串恒为
     * 服务端语言（专用服=英文），客户端凭包内 weaponId 重查本地化名；查不到回退服务端串。
     */
    private String resolveRemoteAmmoDisplayName(S2CRemoteAmmoSnapshot.Entry entry) {
        if (entry.weaponId() != null) {
            String localized = CommonAssetsManager.vehicleWeaponManager().getIndex(entry.weaponId())
                    .map(index -> index.data() instanceof org.ywzj.rvp.weapon.data.RVP_WeaponData rvpData
                            ? org.ywzj.rvp.client.util.RVP_LangHelper.resolveWeaponName(rvpData)
                            : index.data().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
            if (localized != null) {
                return localized;
            }
        }
        return entry.displayName();
    }

    private double resolveEntitySpeedKmh(Entity entity) {
        if (entity instanceof RVP_BaseBullet bullet) {
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation dimension = mc.level != null ? mc.level.dimension().location() : null;
            // 2026-10-06 修复视距内速度冻结：旧条件 !isClientSide() 对客户端实体恒 false，
            // "优先查广播快照实时速度"是死代码，实际恒走客户端出生即冻结的 deltaMovement
            // （出膛初速 → 72kph 假象）。快照 6144 格内不过滤视距内弹、每 5 tick 实时刷新，
            // 客户端实体同样优先消费它（与视距外同源同值）
            S2CRemoteAmmoSnapshot.Entry remoteEntry = RVP_ClientRemoteAmmoState.getEntry(dimension, entity.getId());
            if (remoteEntry != null) {
                return remoteEntry.speedKmh();
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
        if (isBaseOrdnance(entity) && entity instanceof AmmoEntity ammo) {
            return relationColorForBaseAmmo(player, playerVehicle, ammo);
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
        int count = RVP_ClientGPSState.isActive() ? 3 : 2;
        // 玩家在载具中时增加"设为盘旋圆心"选项
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() != null) {
            count++;
        }
        return count;
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
        return Math.min(width + 16, mapRight - mapLeft - 8);
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
        if (itemIndex == 2 && RVP_ClientGPSState.isActive()) {
            return Component.translatable("gui.ywzj_rvp.tactical_map.menu_clear_gps");
        }
        // 最后一项：设为盘旋圆心
        return Component.translatable("gui.ywzj_rvp.tactical_map.menu_set_loiter_center");
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
        return Math.min(width + 16, mapRight - mapLeft - 8);
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
        return markerHit.displayName;
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
        if (filled) {
            fillDiamond(guiGraphics, cx, cy, radius, color);
            return;
        }
        drawGuiLine(guiGraphics, cx, cy - radius, cx + radius, cy, 1.1f, color);
        drawGuiLine(guiGraphics, cx + radius, cy, cx, cy + radius, 1.1f, color);
        drawGuiLine(guiGraphics, cx, cy + radius, cx - radius, cy, 1.1f, color);
        drawGuiLine(guiGraphics, cx - radius, cy, cx, cy - radius, 1.1f, color);
    }

    private void drawDirectionTick(GuiGraphics guiGraphics, int cx, int cy, float yaw, int inner, int outer, int color) {
        double rad = Math.toRadians(yaw);
        int x1 = cx + Mth.floor((float) (Math.sin(rad) * inner));
        int y1 = cy - Mth.floor((float) (Math.cos(rad) * inner));
        int x2 = cx + Mth.floor((float) (Math.sin(rad) * outer));
        int y2 = cy - Mth.floor((float) (Math.cos(rad) * outer));
        drawGuiLine(guiGraphics, x1, y1, x2, y2, 1.1f, color);
    }

    private void drawLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int color) {
        drawGuiLine(guiGraphics, x0, y0, x1, y1, 1.0f, color);
    }

    private void drawGuiLine(GuiGraphics guiGraphics, float x0, float y0, float x1, float y1, float thickness, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        if (dx * dx + dy * dy < 0.25f) {
            guiGraphics.fill(Mth.floor(x0), Mth.floor(y0), Mth.floor(x0) + 1, Mth.floor(y0) + 1, color);
            return;
        }
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x0, y0, 0.0f);
        RenderHelper.drawLine(pose, new Vec3(dx, 0.0, dy), thickness, color, -1, -1);
        pose.popPose();
    }

    private void fillDiamond(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
        float a = alpha(color);
        float r = red(color);
        float g = green(color);
        float b = blue(color);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        var pose = guiGraphics.pose().last().pose();

        buf.vertex(pose, cx, cy - radius, 0).color(r, g, b, a).endVertex();
        buf.vertex(pose, cx + radius, cy, 0).color(r, g, b, a).endVertex();
        buf.vertex(pose, cx, cy + radius, 0).color(r, g, b, a).endVertex();

        buf.vertex(pose, cx, cy - radius, 0).color(r, g, b, a).endVertex();
        buf.vertex(pose, cx, cy + radius, 0).color(r, g, b, a).endVertex();
        buf.vertex(pose, cx - radius, cy, 0).color(r, g, b, a).endVertex();
        tess.end();
    }

    private void drawDashedWorldLine(GuiGraphics guiGraphics, double worldX0, double worldZ0, double worldX1, double worldZ1,
                                     int dashLength, int gapLength, int color) {
        float x0 = (float) worldToScreenX(worldX0);
        float y0 = (float) worldToScreenY(worldZ0);
        float x1 = (float) worldToScreenX(worldX1);
        float y1 = (float) worldToScreenY(worldZ1);
        drawDashedLine(guiGraphics, x0, y0, x1, y1, dashLength, gapLength, color);
    }

    private void drawDashedLine(GuiGraphics guiGraphics, float x0, float y0, float x1, float y1,
                                int dashLength, int gapLength, int color) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double totalLength = Math.hypot(dx, dy);
        if (totalLength <= 0.5) {
            plot(guiGraphics, Mth.floor(x0), Mth.floor(y0), color);
            return;
        }
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x0, y0, 0.0F);
        RenderHelper.drawLine(pose, new Vec3(dx, 0.0D, dy), 1.0F, color,
                Math.max(1, dashLength), Math.max(0, gapLength));
        pose.popPose();
        double capLength = Math.min(Math.max(1.0D, dashLength), totalLength);
        double capStartRatio = (totalLength - capLength) / totalLength;
        float capX = (float) Mth.lerp(capStartRatio, x0, x1);
        float capY = (float) Mth.lerp(capStartRatio, y0, y1);
        drawGuiLine(guiGraphics, capX, capY, x1, y1, 1.0F, color);
    }

    private void plot(GuiGraphics guiGraphics, int x, int y, int color) {
        if (x < mapLeft || x > mapRight || y < mapTop || y > mapBottom) {
            return;
        }
        guiGraphics.fill(x, y, x + 1, y + 1, color);
    }

    private boolean isOverToolbarButton(double mouseX, double mouseY) {
        return isToolbarHit(closeButton, mouseX, mouseY) || isToolbarHit(centerButton, mouseX, mouseY)
                || isToolbarHit(followButton, mouseX, mouseY)
                || isToolbarHit(trajectoryModeButton, mouseX, mouseY)
                || isToolbarHit(weaponDropdownButton, mouseX, mouseY)
                || isToolbarHit(fireButton, mouseX, mouseY)
                || isToolbarHit(radarPanelButton, mouseX, mouseY)
                || isToolbarHit(gpsPanelButton, mouseX, mouseY)
                || isToolbarHit(gpsModeButton, mouseX, mouseY)
                || isToolbarHit(gpsClearAllButton, mouseX, mouseY)
                || isToolbarHit(gpsQuickMarkButton, mouseX, mouseY);
    }

    private static boolean isToolbarHit(@Nullable AbstractWidget widget, double mouseX, double mouseY) {
        return widget != null && widget.visible && widget.isMouseOver(mouseX, mouseY);
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
        WeaponUnit weaponUnit = localWeaponUnit();
        if (weaponUnit == null) {
            weaponDropdownOpen = false;
            return true;
        }
        int index = weaponRows().rowAt(mouseY, weaponScroll, weaponUnit.weapons.size());
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
        return Math.max(layout.margin(), Math.min(weaponDropdownButton.getX(), width - layout.margin() - 158));
    }

    private int weaponDropdownY() {
        return mapTop;
    }

    private int weaponDropdownWidth() {
        return Math.min(Math.max(158, weaponDropdownButton.getWidth() + 80), width - layout.margin() - weaponDropdownX());
    }

    private float interpolatedRadarMapYaw(RadarUnit radarUnit, float partialTick) {
        float xRot = Mth.lerp(partialTick, radarUnit.xRotO, radarUnit.getXRot());
        float yRot = Mth.lerp(partialTick, radarUnit.yRotO, radarUnit.getYRot());
        return radarUnit.worldRot(xRot, yRot).y;
    }

    private RVP_MapChrome.ListArea weaponRows() {
        return new RVP_MapChrome.ListArea(weaponDropdownY(), mapBottom, Math.max(16, layout.buttonHeight()));
    }

    private int weaponDropdownHeight() {
        WeaponUnit station = localWeaponUnit();
        return station == null ? 0 : Math.min(weaponRows().visible(), station.weapons.size()) * weaponRows().step();
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
        switchingTo = weaponUnit.weapons.get(targetIndex);
        switchDeadlineMillis = net.minecraft.Util.getMillis() + 3000;
        refreshWeaponButtons();
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

    @Override
    public void drawWorldLine(GuiGraphics guiGraphics, double worldX0, double worldZ0,
                              double worldX1, double worldZ1, int color) {
        int x0 = Mth.floor(worldToScreenX(worldX0));
        int y0 = Mth.floor(worldToScreenY(worldZ0));
        int x1 = Mth.floor(worldToScreenX(worldX1));
        int y1 = Mth.floor(worldToScreenY(worldZ1));
        drawLine(guiGraphics, x0, y0, x1, y1, color);
    }

    @Override public int mapLeft() { return mapLeft; }
    @Override public int mapTop() { return mapTop; }
    @Override public int mapRight() { return mapTool == null ? mapRight : sideLeft - 2; }
    @Override public int mapBottom() { return mapBottom; }
    @Override public int sideLeft() { return sideLeft; }
    @Override public int sideRight() { return sideRight; }
    @Override public double blocksPerPixel() { return blocksPerPixel; }
    @Override public net.minecraft.client.gui.Font font() { return font; }

    private void drawRadarSectorOnMap(GuiGraphics guiGraphics, double worldX, double worldZ, float radiusPixels,
                                      float vehicleYaw, float localStartDeg, float localEndDeg, int segments, int color,
                                      boolean outline) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        var matrix = guiGraphics.pose().last().pose();
        buffer.begin(outline ? VertexFormat.Mode.TRIANGLE_STRIP : VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        float centerX = (float) worldToScreenX(worldX);
        float centerY = (float) worldToScreenY(worldZ);
        float a = alpha(color);
        float r = red(color);
        float g = green(color);
        float b = blue(color);

        if (!outline) buffer.vertex(matrix, centerX, centerY, 0).color(r, g, b, a).endVertex();
        for (int i = 0; i <= segments; i++) {
            float localAngle = localStartDeg + (localEndDeg - localStartDeg) * (i / (float) segments);
            float mapAngle = mapAngleForRadarLocal(vehicleYaw, localAngle);
            float dx = Mth.sin((float) Math.toRadians(mapAngle)), dy = -Mth.cos((float) Math.toRadians(mapAngle));
            if (outline) {
                float inner = Math.max(0, radiusPixels - 1.2f);
                buffer.vertex(matrix, centerX + dx * inner, centerY + dy * inner, 0).color(r,g,b,a).endVertex();
            }
            buffer.vertex(matrix, centerX + dx * radiusPixels, centerY + dy * radiusPixels, 0).color(r,g,b,a).endVertex();
        }
        tesselator.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void drawRadarScanLineOnMap(GuiGraphics guiGraphics, double worldX, double worldZ, float radiusPixels,
                                        float vehicleYaw, float localScanAngle, float thickness, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        var matrix = guiGraphics.pose().last().pose();
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

        buffer.vertex(matrix, centerX - perpX * halfWidth, centerY - perpY * halfWidth, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, centerX + perpX * halfWidth, centerY + perpY * halfWidth, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, centerX + dirX * radiusPixels + perpX * halfWidth, centerY + dirY * radiusPixels + perpY * halfWidth, 0)
                .color(r, g, b, a).endVertex();
        buffer.vertex(matrix, centerX + dirX * radiusPixels - perpX * halfWidth, centerY + dirY * radiusPixels - perpY * halfWidth, 0)
                .color(r, g, b, a).endVertex();

        tesselator.end();
        RenderSystem.disableBlend();
    }

    private float mapAngleForRadarLocal(float vehicleYaw, float localAngle) {
        return mapIconYaw(vehicleYaw) + localAngle;
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
