package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.element.Select;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Box;
import com.sighs.apricityui.style.Text;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;
import com.google.gson.JsonObject;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.config.RVP_WorkshopDamage;
import org.ywzj.rvp.config.RVP_WorkshopData;
import org.ywzj.rvp.config.RVP_WorkshopBallistics;
import org.ywzj.rvp.entity.projectile.RVP_WorkshopTrajectory;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData.OpticalSightType;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientVehicleChangeDisplay;
import org.ywzj.vehicle.util.AuiTextHelper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/** 同一页内浏览载具档案、选择武器与挂架、调整涂装及烟雾。 */
public class RVP_AuiVariantScreen extends ApricityScreen {
    /** 当前改装工具指向的实体。 */
    private final AbstractVehicle vehicle;
    /** 辅助入口调用时的返回屏，直接从工具打开时为空。 */
    private final Screen parent;
    /** 同一实例内复用相机，重排、选弹或从辅助载具返回时保留视图。 */
    private final RVP_WorkshopPreview preview;
    private final RVP_WorkshopAuxiliary auxiliary;
    private RVP_WorkshopPreview auxiliaryCamera;
    private SlotRow auxiliaryRow;
    private int lastAppliedAuxiliary = -1;
    private final List<ScrollingLabel> slotLabels = new ArrayList<>();
    private final List<ScrollingLabel> optionLabels = new ArrayList<>();
    /** 实体上实际存在的改装栏，以及对应的 DOM 行。 */
    private List<RVP_WorkshopStation> stations = List.of();
    private final List<SlotRow> stationRows = new ArrayList<>();
    /** 当前展示组的全部涂装 ID。 */
    private List<ResourceLocation> paints = List.of();
    /** 当前子栏。档案、武器、涂装和烟雾都位于同一个文档中。 */
    private enum Panel { PROFILE, WEAPON, PAINT, SMOKE, AUXILIARY }
    private Panel panel = Panel.WEAPON;
    /** 从档案页签返回上次浏览的改装栏目。 */
    private Panel lastEquipment = Panel.WEAPON;
    /** 每次打开界面时查一次资源包中的可选图标，空串使用占位图形。 */
    private final Map<ResourceLocation, String> weaponArt = new HashMap<>();
    /** 当前武器栏下标，仅在武器视图使用。 */
    private int stationIndex;
    /** 涂装筛选文本，跨窗口缩放保留。 */
    private String filter = "";
    /** 当前可点击选项行与对应涂装（武器则直接使用行号）。 */
    private final List<Element> optionRows = new ArrayList<>();
    private final List<ResourceLocation> visiblePaints = new ArrayList<>();
    /** 服务端同步抵达前的短暂高亮；到期后回到实体的实际配置。 */
    private int pendingStation = -1, pendingWeapon = -1, pendingWeaponTicks;
    private ResourceLocation pendingPaint;
    private int pendingPaintTicks;
    /** 上次显示的已装选项，实体数据变化时才更新说明。 */
    private int lastEquipped = -2;
    /** 用户正在预览说明的选项，离开后显示已装选项。 */
    private int hovered = -1;
    /** 捕获拖动的鼠标键，-1 表示由 UI 接管。 */
    private int dragButton = -1;
    /** ApricityUI 每次初始化创建的新文档。 */
    private Document doc;
    /** 右侧数据来自同一份服务器同步快照。 */
    private RVP_WorkshopData stats;
    /** 预览伤害的目标，随页面切换和候选武器悬停保留。 */
    private String damageTarget = RVP_WorkshopDamage.DEFAULT_VEHICLE;
    /** 同一武器的情形与展开状态在详情重新加载时保留。 */
    private ResourceLocation damageWeapon;
    private RVP_WorkshopDamage.Context damageContext;
    private boolean damageExpanded;
    /** 仅缓存当前武器算过的高度及速度组合；服务端重载或换武器后丢弃。 */
    private final Map<Integer, RVP_WorkshopTrajectory.Curve> curves = new HashMap<>();
    /** 画布的已绘曲线。窗口缩放或切换采样后按新的像素尺寸重绘。 */
    private RVP_WorkshopTrajectory.Curve drawnCurve;
    private RVP_WeaponData flightWeapon;
    private RVP_WorkshopData flightData;
    private RVP_WorkshopTrajectory sampling;
    private boolean speedExpanded, speedVisible;
    /** 发射高度以米计，载机速度档位为最大空速的零到三等分。 */
    private int launchAltitude = 100, carrierStep;
    /** 复用载具档案的最大空速，单位格/刻；为零时省略载机下拉。 */
    private double carrierMaxSpeed;
    /** 发射高度预设及载机全速对应的档位。 */
    private static final List<Integer> LAUNCH_ALTITUDES = List.of(10, 100, 500, 1000, 1500, 2000, 2500);
    private static final int MAX_CARRIER_STEP = 3;
    /** 伤害卡只直接显示最前面的两条来源。 */
    private static final int COLLAPSED_SOURCES = 2;
    /** 两类武器共用的距离（米）与入射角（度），表格始终保持此顺序。 */
    private static final int[] DISTANCES = {10, 100, 500, 1000, 1500, 2000};
    private static final int[] ANGLES = {0, 30, 60, 80};
    private static final String FIELD = "gui.ywzj_rvp.workshop.field.";
    /** 外观行，显示当前涂装。 */
    private SlotRow paintRow;
    /** 固定翼飞机的烟雾行，显示当前颜色。 */
    private SlotRow smokeRow;

    public RVP_AuiVariantScreen(AbstractVehicle vehicle, Screen parent) {
        this(vehicle, parent, false);
    }

    RVP_AuiVariantScreen(AbstractVehicle vehicle, Screen parent, boolean openAuxiliary) {
        super("screens/rvp_variants.html");
        preview = new RVP_WorkshopPreview(vehicle, false);
        auxiliary = new RVP_WorkshopAuxiliary(vehicle);
        if (openAuxiliary && !auxiliary.options.isEmpty()) panel = Panel.AUXILIARY;
        this.vehicle = vehicle;
        this.parent = parent;
        setPauseGame(false);
        setShowDefaultBackground(false);
    }

    @Override
    protected void init() {
        super.init();
        doc = getLinkedDocument();
        if (doc == null) return;
        // 浏览器模式按显示器计算 CSS 宽度；根据实际窗口补偿小窗口的文字大小。
        if (minecraft.getWindow().getWidth() < 1080 || minecraft.getWindow().getHeight() < 600) {
            el("workshop").setClassName("compact");
        }
        // 从真实实体与本体的涂装组读取数据，保留没有可改武器的车型档案。
        stations = RVP_WorkshopStation.collect(vehicle);
        paints = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).stream()
                .flatMap(display -> ClientAssetsManager.INSTANCE.getVariableDisplay(display.getDisplayGroup()).stream())
                .map(BaseDisplay::getDisplayId).distinct().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        if (stationIndex >= stations.size()) {
            stationIndex = 0;
            if (stations.isEmpty()) {
                if (panel == Panel.WEAPON) panel = Panel.PROFILE;
                if (lastEquipment == Panel.WEAPON) lastEquipment = Panel.PAINT;
            }
        }
        weaponArt.clear();
        if (pendingStation >= stations.size()) pendingStation = -1;
        stationRows.clear();
        slotLabels.clear();
        Element slots = el("slots");
        for (int index = 0; index < stations.size(); index++) {
            int selected = index;
            stationRows.add(row(slots, stations.get(index).title(), () -> {
                stationIndex = selected;
                open(Panel.WEAPON);
            }));
        }
        paintRow = row(slots, I18n.get("gui.ywzj_rvp.workshop.paint"), () -> open(Panel.PAINT));
        if (vehicle instanceof FixedWingVehicle) {
            smokeRow = row(slots, I18n.get("ui.aerobatic_smoke"), () -> open(Panel.SMOKE));
            // 复用本体的烟雾颜色配置。
            bindColor("r", AllConfigs.common.aerobaticSmokeR);
            bindColor("g", AllConfigs.common.aerobaticSmokeG);
            bindColor("b", AllConfigs.common.aerobaticSmokeB);
        }
        if (!auxiliary.options.isEmpty()) {
            if (panel != Panel.AUXILIARY) auxiliary.enter();
            auxiliaryRow = row(slots, I18n.get("gui.ywzj_rvp.auxiliary.entry"), () -> open(Panel.AUXILIARY));
        }
        el("profile-tab").addEventListener("click", event -> open(Panel.PROFILE));
        el("equipment-tab").addEventListener("click", event -> open(lastEquipment));
        Element title = el("vehicle-title");
        Element name = child(title, "span", "ticker-text", vehicle.getDisplayName().getString());
        slotLabels.add(new ScrollingLabel(title, name, title));
        title.addEventListener("click", event -> open(Panel.PROFILE));
        el("reset-view").addEventListener("click", event -> camera().reset());
        el("close-button").addEventListener("click", event -> onClose());
        Element search = el("paint-search");
        search.setAttribute("placeholder", I18n.get("screen.search"));
        search.setValue(filter);
        search.addEventListener("input", event -> filterPaint(search.getValue()));
        search.addEventListener("change", event -> filterPaint(search.getValue()));
        open(panel);
    }

    private Element el(String id) { return doc.getElementById(id); }

    private Element child(Element parent, String tag, String css, String text) {
        Element node = doc.createElement(tag);
        node.setClassName(css);
        if (!text.isEmpty()) node.setTextContent(text);
        // ApricityUI 挂载 button 等节点时会创建控件实例，子节点和事件要绑到返回值。
        return parent.appendChild(node);
    }

    private SlotRow row(Element host, String caption, Runnable action) {
        Element button = child(host, "button", "slot", "");
        button.setAttribute("type", "button");
        scrollingLabel(button, "caption", caption, slotLabels, button);
        Element band = child(button, "span", "load", "");
        Element label = scrollingLabel(band, "value", "", slotLabels, button);
        Element bay = child(band, "span", "bay", "");
        Element plaque = child(button, "span", "plaque", "");
        button.addEventListener("click", event -> action.run());
        return new SlotRow(button, label, bay, plaque);
    }

    /** 栏目两行分别显示用途和当前装备，活动栏目额外展开大图位。 */
    private record SlotRow(Element button, Element label, Element bay, Element plaque) {}

    private void setRow(SlotRow row, String label, String artwork, boolean active) {
        if (!label.equals(row.label().getTextContent())) {
            row.label().setTextContent(label);
        }
        String style = active ? "slot active" : "slot";
        if (!style.equals(row.button().getClassName())) row.button().setClassName(style);
        art(row.bay(), artwork);
        if (active) art(row.plaque(), artwork);
    }

    /**
     * 图标优先读武器数据的 {@code workshop_icon}（相对 icons/workshop/，同一张图可被多个武器复用）；
     * 未配置时回退按武器完整注册名查找。资源包可补透明 PNG，一张图用于小槽、候选项和说明。
     */
    private String artwork(RVP_WorkshopStation station, int index) {
        if (index < 0 || index >= station.size()) return "";
        var data = station.weapon(index).getData();
        return weaponArt.computeIfAbsent(data.getWeaponId(), id -> {
            String custom = data instanceof RVP_WeaponData rvp ? rvp.getWorkshopIcon() : "";
            String path = workshopIconPath(custom);
            if (!path.isEmpty() && workshopIconExists(path)) return "/" + path;
            path = "icons/workshop/" + id.getNamespace() + "/" + id.getPath() + ".png";
            return workshopIconExists(path) ? "/" + path : "";
        });
    }

    /** 把 {@code workshop_icon} 解析为相对 apricityui:apricity/ 的 PNG 路径；非法值返回空串。 */
    private static String workshopIconPath(String icon) {
        String value = icon == null ? "" : icon.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || value.contains(":")) return "";
        return value.startsWith("icons/") ? value : "icons/workshop/" + value;
    }

    private boolean workshopIconExists(String path) {
        var resource = new ResourceLocation("apricityui", "apricity/" + path);
        return minecraft.getResourceManager().getResource(resource).isPresent();
    }

    /** 没有原画时显示低对比度的双框标记，保留与正式图标相同的占位尺寸。 */
    private void art(Element host, String source) {
        if (host.getChildren().size() > 0 && source.equals(host.getAttribute("data-art"))) return;
        host.setAttribute("data-art", source);
        host.setTextContent("");
        if (source.startsWith("/")) {
            Element img = child(host, "img", "art-image", "");
            img.setAttribute("src", source);
            img.setAttribute("alt", "");
        } else if (source.startsWith("#")) {
            child(host, "span", "art-chip", "").setInlineStyleProperty("background-color", source);
        } else {
            Element mark = child(host, "span", "art-mark", "");
            child(mark, "span", "mark-back", "");
            child(mark, "span", "mark-front", "");
        }
    }

    /** 保留完整字符串，按当前字体与实际内容框宽度移动。列表保留左边线，顶部标题沿用中心线。 */
    private Element scrollingLabel(Element parent, String css, String value, List<ScrollingLabel> labels, Element button) {
        Element frame = child(parent, "span", css + " ticker-frame", "");
        Element ink = child(frame, "span", "ticker-text", value);
        labels.add(new ScrollingLabel(frame, ink, button));
        return ink;
    }

    private static final class ScrollingLabel {
        final Element frame, ink, button;
        int travel, previousX = Integer.MIN_VALUE;
        long started, hoveredSince;
        String previousText = "";

        ScrollingLabel(Element frame, Element ink, Element button) {
            this.frame = frame;
            this.ink = ink;
            this.button = button;
        }

        void update(long now) {
            double space = Box.of(frame).innerSize().width();
            if (space <= 0) return;
            String value = ink.getTextContent();
            double width = Text.measureLine(Text.of(ink), value);
            int overflow = Math.max(0, (int) Math.ceil(width - space));
            if (travel != overflow || !value.equals(previousText)) {
                travel = overflow;
                previousText = value;
                started = now;
                hoveredSince = 0;
            }
            int x = frame == button ? (int) Math.round((space - width) / 2) : 0;
            if (travel > 0) {
                // 两端各停留一秒，往返滚动时最后一个字也能完整读到。
                long run = Math.max(1, travel * 24L);
                long phase = (now - started) % (2 * (1000 + run));
                long distance = phase < 1000 + run ? phase - 1000 : 2000 + 2 * run - phase;
                x = -(int) Math.round(travel * Math.max(0, Math.min(run, distance)) / (double) run);
            }
            if (x != previousX) {
                ink.setInlineStyleProperty("transform", "translateX(" + x + "px)");
                previousX = x;
            }
        }

        boolean hint(double x, double y, long now) {
            var bounds = frame.getBoundingClientRect();
            boolean pointed = frame.isHover && x >= bounds.x && x < bounds.x + bounds.width
                    && y >= bounds.y && y < bounds.y + bounds.height;
            if (travel <= 0 || !(pointed || button.isFocus)) {
                hoveredSince = 0;
                return false;
            }
            if (hoveredSince == 0) hoveredSince = now;
            return now - hoveredSince >= 650;
        }
    }

    private void show(String id, boolean visible) {
        el(id).setInlineStyleProperty("display", visible ? switch (id) {
            case "options", "choices", "detail-art" -> "flex";
            default -> "block";
        } : "none");
    }

    private void open(Panel next) {
        if (next == Panel.AUXILIARY) {
            auxiliary.enter();
            lastAppliedAuxiliary = -1;
        }
        panel = next;
        if (next != Panel.PROFILE) lastEquipment = next;
        el("profile-tab").setClassName(next == Panel.PROFILE ? "tab current" : "tab");
        el("equipment-tab").setClassName(next == Panel.PROFILE ? "tab" : "tab current");
        el("profile-tab").setAttribute("aria-pressed", Boolean.toString(next == Panel.PROFILE));
        el("equipment-tab").setAttribute("aria-pressed", Boolean.toString(next != Panel.PROFILE));
        hovered = -1;
        lastEquipped = -2;
        show("options", next != Panel.PROFILE);
        show("choices", next == Panel.WEAPON || next == Panel.PAINT || next == Panel.AUXILIARY);
        show("aux-actions", next == Panel.AUXILIARY);
        show("options-title", next == Panel.PAINT || next == Panel.SMOKE);
        show("paint-search", next == Panel.PAINT);
        show("smoke", next == Panel.SMOKE);
        show("no-results", false);
        el("choices").setTextContent("");
        el("choices").setScrollTop(0);
        optionRows.clear();
        optionLabels.clear();
        visiblePaints.clear();
        if (next == Panel.WEAPON) {
            var station = stations.get(stationIndex);
            for (int i = 0; i < station.size(); i++) {
                int index = i;
                option(station.weapon(i).getDisplayName().getString(), artwork(station, i), () -> equipWeapon(index));
            }
        } else if (next == Panel.AUXILIARY) {
            for (int i = 0; i < auxiliary.options.size(); i++) {
                int index = i;
                option(RVP_WorkshopAuxiliary.resolveOptionName(auxiliary.options.get(i)), "", () -> {
                    auxiliary.choose(index);
                    clickSound();
                    refreshDetails();
                });
            }
        } else if (next == Panel.PAINT) {
            el("options-title").setTextContent(I18n.get("screen.vehicle_modding_tool.catalog"));
            buildPaints();
        } else if (next == Panel.SMOKE) {
            el("options-title").setTextContent(I18n.get("ui.aerobatic_smoke"));
        }
        refreshRows();
        refreshDetails();
    }

    private void option(String label, String artwork, Runnable choose) {
        int index = optionRows.size();
        Element button = child(el("choices"), "button", "option", "");
        button.setAttribute("type", "button");
        art(child(button, "span", "option-art", ""), artwork);
        scrollingLabel(button, "option-name", label, optionLabels, button);
        button.addEventListener("mouseenter", event -> inspect(index));
        button.addEventListener("focus", event -> inspect(index));
        button.addEventListener("mouseleave", event -> inspect(-1));
        button.addEventListener("blur", event -> inspect(-1));
        button.addEventListener("click", event -> choose.run());
        optionRows.add(button);
    }

    /** 浏览和提交分离，悬停或键盘聚焦只更新右侧说明。 */
    private void inspect(int index) {
        if (hovered != index) {
            hovered = index;
            refreshDetails();
        }
    }

    private void filterPaint(String query) {
        if (filter.equals(query)) return;
        filter = query;
        hovered = -1;
        buildPaints();
        refreshDetails();
    }

    private void buildPaints() {
        el("choices").setTextContent("");
        optionRows.clear();
        optionLabels.clear();
        visiblePaints.clear();
        String query = filter.strip().toLowerCase(Locale.ROOT);
        for (var id : paints) {
            if (!id.toString().toLowerCase(Locale.ROOT).contains(query)) continue;
            visiblePaints.add(id);
            option(id.getPath(), "", () -> {
                // 只有明确点击才发送本体涂装包，筛选与打开界面均不发包。
                Channel.CHANNEL.sendToServer(new ClientVehicleChangeDisplay(vehicle.getId(), id));
                pendingPaint = id;
                pendingPaintTicks = 40;
                clickSound();
                refreshRows();
                refreshDetails();
            });
        }
        show("no-results", visiblePaints.isEmpty());
    }

    private void equipWeapon(int index) {
        var station = stations.get(stationIndex);
        // 单项固定武器可以查看档案。点击时没有待提交的改装。
        if (station.size() <= 1) return;
        // 打开档案与浏览说明不受限，提交时重新检查车辆速度和乘员。
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(vehicle)) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("message.ywzj_rvp.modding_blocked"), true);
            return;
        }
        station.equip(vehicle, index);
        pendingStation = stationIndex;
        pendingWeapon = index;
        pendingWeaponTicks = 40;
        clickSound();
        refreshRows();
        refreshDetails();
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    private int equipped(int index) {
        return pendingStation == index ? pendingWeapon : stations.get(index).equipped();
    }

    private ResourceLocation paint() { return pendingPaint != null ? pendingPaint : vehicle.getDisplayId(); }

    private void refreshRows() {
        for (int i = 0; i < stations.size(); i++) {
            var station = stations.get(i);
            int index = equipped(i);
            setRow(stationRows.get(i), index >= 0 && index < station.size()
                    ? station.weapon(index).getDisplayName().getString() : "—",
                    artwork(station, index), panel == Panel.WEAPON && stationIndex == i);
        }
        setRow(paintRow, paint().getPath(), "", panel == Panel.PAINT);
        if (auxiliaryRow != null) setRow(auxiliaryRow, auxiliary.appliedIndex() < 0 && panel != Panel.AUXILIARY
                ? I18n.get("gui.ywzj_rvp.workshop.configure") : auxiliary.appliedName(), "", panel == Panel.AUXILIARY);
        updateSwatch();
    }

    private void focusAuxiliary(int index) {
        if (index == auxiliary.focusedIndex) return;
        auxiliary.focus(index);
        auxiliaryCamera = new RVP_WorkshopPreview(auxiliary.previewVehicle, true);
    }

    private RVP_WorkshopPreview camera() {
        return panel == Panel.AUXILIARY && auxiliaryCamera != null ? auxiliaryCamera : preview;
    }

    private void updateAuxiliaryStatus() {
        Element label = el("aux-state");
        String status = auxiliary.status();
        if (!status.equals(label.getTextContent())) label.setTextContent(status);
    }

    private void refreshDetails() {
        int active = switch (panel) {
            case WEAPON -> equipped(stationIndex);
            case PAINT -> visiblePaints.indexOf(paint());
            case AUXILIARY -> auxiliary.selectedIndex();
            default -> -1;
        };
        lastEquipped = active;
        for (int i = 0; i < optionRows.size(); i++) {
            optionRows.get(i).setClassName(i == active ? "option active" : "option");
            optionRows.get(i).setAttribute("aria-pressed", Boolean.toString(i == active));
            if (panel == Panel.AUXILIARY) optionRows.get(i).setAttribute("aria-current", Boolean.toString(i == auxiliary.appliedIndex()));
        }
        int index = hovered >= 0 && hovered < optionRows.size() ? hovered : active;
        stats = RVP_WorkshopData.current();
        // 原生下拉菜单在文档顶层，替换武器详情前先关闭旧菜单。
        if (el("damage-target") instanceof Select select) select.closePopup();
        if (el("damage-context") instanceof Select select) select.closePopup();
        if (el("speed-altitude") instanceof Select select) select.closePopup();
        if (el("speed-carrier") instanceof Select select) select.closePopup();
        speedVisible = false;
        el("facts").setTextContent("");
        show("detail-art", panel == Panel.WEAPON && index >= 0 && index < stations.get(stationIndex).size());
        if (panel == Panel.WEAPON && index >= 0 && index < stations.get(stationIndex).size()) {
            var station = stations.get(stationIndex);
            var weapon = station.weapon(index);
            art(el("detail-art"), artwork(station, index));
            weaponDetails(weapon);
        } else if (panel == Panel.AUXILIARY) {
            focusAuxiliary(index >= 0 ? index : Math.max(0, auxiliary.focusedIndex));
            var option = auxiliary.options.get(auxiliary.focusedIndex);
            vehicleDetails(RVP_WorkshopAuxiliary.resolveOptionName(option), auxiliary.previewVehicle);
            show("aux-unavailable", auxiliary.previewVehicle == null);
            updateAuxiliaryStatus();
        } else if (panel == Panel.PAINT) {
            var id = index >= 0 && index < visiblePaints.size() ? visiblePaints.get(index) : paint();
            detail(id.getPath(), ClientAssetsManager.INSTANCE.getVehicleDisplay(id));
            fact("gui.ywzj_rvp.workshop.vehicle", vehicle.getDisplayName().getString());
        } else if (panel == Panel.SMOKE) {
            detail(I18n.get("ui.aerobatic_smoke"), Optional.empty());
            fact("gui.ywzj_rvp.workshop.color", rgbText());
        } else {
            vehicleDetails(vehicle.getDisplayName().getString(), vehicle);
        }
    }

    private void detail(String title, Optional<? extends BaseDisplay> display) {
        detail(title, display, "", "");
    }

    private void detail(String title, Optional<? extends BaseDisplay> display, String description, String descriptionCN) {
        el("detail-title").setTextContent(title);
        String copy = !descriptionCN.isEmpty() && minecraft.getLanguageManager().getSelected().equals("zh_cn")
                ? descriptionCN : description;
        if (copy.isEmpty()) copy = display.map(BaseDisplay::getDescription).orElse("");
        if (!copy.isBlank()) copy = I18n.get(copy);
        // 复用本体的多段说明格式。缺介绍的包仍有下方从当前数据读取的参数。
        AuiTextHelper.setDescription(el("detail-copy"), copy);
        show("detail-copy", !copy.isBlank());
    }

    private void fact(String key, String value) {
        Element line = child(el("facts"), "div", "fact", "");
        child(line, "span", "fact-name", I18n.get(key));
        child(line, "span", "fact-value", value);
    }

    /** 仅为已配置的指标生成行和对比条。各条相对同类型载具的同一指标归一化。 */
    private void vehicleDetails(String title, AbstractVehicle target) {
        var data = target == null ? null : stats.vehicle(target.getVehicleId());
        var display = target == null ? Optional.<BaseDisplay>empty()
                : ClientAssetsManager.INSTANCE.getVehicleDisplay(target.getDisplayId());
        detail(title, display, data == null ? "" : data.description(), data == null ? "" : data.descriptionCN());
        if (data == null) return;
        if (data.hasParts()) fact(FIELD + "crew", String.valueOf(target.passengerCapacity()));
        numeric("health", data.health(), "");
        if (!data.mobility().isEmpty()) {
            child(el("facts"), "div", "fact-heading", I18n.get(FIELD + "mobility"));
            data.mobility().forEach((key, value) -> {
                Element row = child(el("facts"), "div", "mobility-row", "");
                child(row, "span", "mobility-label", I18n.get(FIELD + key));
                Element track = child(row, "span", "meter-track", "");
                double max = stats.maximum(data, key);
                double percent = max > 0 ? 100 * Math.abs(value) / max : 0;
                child(track, "span", "meter-fill", "").setInlineStyleProperty("width", format(percent) + "%");
                String label = switch (key) {
                    case "forward", "reverse" -> format(Math.abs(value) * 72) + " km/h";
                    case "airspeed" -> (data.type().endsWith(":fixed_wing_vehicle") ? "≈" : "")
                            + format(Math.round(value * 720) / 10.0) + " km/h";
                    case "climb" -> "≈" + format(Math.round(value * 200) / 10.0) + " m/s";
                    case "ceiling" -> format(value) + " m";
                    case "turret" -> format(value * 20) + " °/s";
                    default -> format(value);
                };
                child(row, "span", "meter-value", label);
            });
        }
        String grade = RVP_WorkshopData.armorGrade(data.armor());
        if (!grade.isEmpty()) fact(FIELD + "armor", I18n.get(FIELD + "armor." + grade));
        // 本体从乘员所在武器站切换视镜。取可开镜座位的实际倍率，含部件默认值。
        Double zoom = null;
        for (var part : target.getPartUnits()) {
            if (!(part instanceof WeaponUnit station) || !station.isSeat()
                    || station.getOpticalSightType() == OpticalSightType.NONE || station.getData() == null) continue;
            double max = Math.max(station.getData().getZoomMin(), station.getData().getZoomMax());
            if (zoom == null || max > zoom) zoom = max;
        }
        numeric("zoom", zoom, "×");
    }

    private void weaponDetails(AbstractVehicleWeapon<?> weapon) {
        var id = weapon.getData().getWeaponId();
        JsonObject raw = stats.weapon(id);
        detail(weapon.getDisplayName().getString(), ClientAssetsManager.INSTANCE.getWeaponDisplay(id),
                RVP_WorkshopData.text(raw, "description"), RVP_WorkshopData.text(raw, "description_CN"));
        if (raw == null) return;
        var data = RVP_WorkshopData.weapon(raw, weapon.getData());
        // 弹容量由当前武器站覆写。显示和单发判定共用实际可装填的容量。
        int capacity = weapon.getMaxCapacity();
        weaponFacts(data, capacity, data.capacity() != null || weapon.getWeaponUnit().getAmmoCapacity() != -1);
        // 沿用载具档案已经计算的母载具最大空速。
        speedDetails(raw, weapon.getData(), stats.vehicle(vehicle.getVehicleId()));
        // 从正式子弹药索引读取解析数据，原文用于保留缺项。
        var projectile = new RVP_WorkshopDamage.Projectile(raw, weapon.getData() instanceof RVP_WeaponData rvp ? rvp : null);
        java.util.function.Function<ResourceLocation, RVP_WorkshopDamage.Projectile> lookup = childId ->
                new RVP_WorkshopDamage.Projectile(stats.weapon(childId), RVP_SubmunitionSpawner.loadWeaponData(childId));
        var scenarios = RVP_WorkshopDamage.read(id, projectile, lookup);
        var blocks = RVP_WorkshopDamage.read(id, projectile, lookup, true);
        if (!scenarios.isEmpty() || blocks.stream().anyMatch(s -> s.shot().hasPower() || s.shot().radius() != null
                || !s.shot().effects().isEmpty())) damageDetails(id, scenarios, blocks);
    }

    /** 武器站的实际容量为 1 时，保留口径等数据并省略发射模式和射速。 */
    private void weaponFacts(RVP_WorkshopData.Weapon data, int capacity, boolean showCapacity) {
        if (data.guided()) {
            var stages = new ArrayList<String>();
            if (!data.guidance().isBlank()) stages.add(enumLabel("guidance", data.guidance()));
            if (!data.terminalGuidance().isBlank()) stages.add(enumLabel("guidance", data.terminalGuidance()));
            if (!stages.isEmpty()) fact(FIELD + "guidance", String.join(" → ", stages));
        } else {
            numeric("caliber", data.caliber(), " mm");
            if (capacity != 1) {
                if (!data.fireMode().isBlank()) fact(FIELD + "mode", enumLabel("mode", data.fireMode()));
                numeric("rpm", data.rpm(), " RPM");
            }
        }
        if (showCapacity) numeric("capacity", (double) capacity, "");
        numeric("reload", data.reloadSeconds(), " s");
    }

    private String enumLabel(String field, String raw) {
        String key = FIELD + field + "." + raw.toLowerCase(Locale.ROOT);
        return I18n.exists(key) ? I18n.get(key) : raw;
    }

    private void numeric(String key, Double value, String unit) {
        if (value != null) fact(FIELD + key, format(value) + unit);
    }

    private static String format(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** 武器初速和设计参数直接取当前弹药；曲线从展示实体采样，两者分别保留出膛和平台速度语义。 */
    private void speedDetails(JsonObject raw, BaseVehicleWeaponData resolved, RVP_WorkshopData.Vehicle carrierProfile) {
        var data = resolved instanceof RVP_WeaponData rvp ? rvp : null;
        boolean missile = resolved instanceof VehicleMissileWeaponData || data != null && data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        Double speed = RVP_WorkshopBallistics.muzzle(raw, resolved);
        if (speed != null) fact(FIELD + "muzzle", speedText(speed, missile));
        if (flightWeapon != data || flightData != stats) {
            flightWeapon = data;
            flightData = stats;
            curves.clear();
            sampling = null;
            launchAltitude = 100;
            carrierStep = 0;
            carrierMaxSpeed = data != null && data.isInheritVehicleVelocity() && carrierProfile != null
                    ? carrierProfile.mobility().getOrDefault("airspeed", 0.0) : 0;
            speedExpanded = RVP_WorkshopDamage.isAirOnlyMissile(data);
        }
        if (data != null && data.usesPropulsion()) {
            Element line = child(el("facts"), "div", "fact", "");
            child(line, "span", "fact-name", I18n.get(FIELD + "forces"));
            child(line, "span", "fact-value", "").setAttribute("id", "speed-forces");
            updateForces();
        } else {
            var base = RVP_WorkshopBallistics.baseForces(raw, resolved);
            if (base != null) fact(FIELD + "forces", forceText(base));
        }
        String range = "", angle = "";
        var guidance = RVP_WorkshopData.object(raw, "guidance_data");
        if (data != null && guidance != null) {
            var lock = data.getGuidanceData().getLockTargetDistanceRange();
            if (guidance.has("lock_target_distance_range") && lock != null) {
                var intervals = new ArrayList<String>();
                for (var interval : lock.intervals()) {
                    String from = interval.lower() == null ? "0" : format(interval.lower());
                    String to = interval.upper() == null ? "∞" : format(interval.upper());
                    intervals.add(from + "–" + to);
                }
                if (!intervals.isEmpty()) range = String.join(", ", intervals) + " m";
            }
            if (RVP_WorkshopData.number(guidance, "max_lock_angle") != null)
                angle = data.getGuidanceData().getMaxLockAngle() + "°";
        } else if (resolved instanceof VehicleMissileWeaponData base && RVP_WorkshopData.number(raw, "seeker_fov") != null) {
            angle = format(base.getSeekerFov()) + "°";
        }
        if (!range.isEmpty() && !angle.isEmpty()) fact(FIELD + "lock_fov", range + " / " + angle);
        else if (!range.isEmpty()) fact(FIELD + "lock_range", range);
        else if (!angle.isEmpty()) fact(FIELD + "fov", angle);
        Double gs = RVP_WorkshopBallistics.maxG(raw, resolved);
        if (gs != null) numeric("max_g", Math.round(gs * 10) / 10.0, " G");
        if (data == null || !data.usesPropulsion() && !RVP_WorkshopDamage.isAirOnlyMissile(data)) return;
        speedVisible = true;

        Element toggle = child(el("facts"), "button", "fact speed-toggle", "");
        toggle.setAttribute("id", "speed-toggle");
        toggle.setAttribute("type", "button");
        toggle.setAttribute("aria-controls", "speed-panel");
        Element label = child(toggle, "span", "speed-label", "");
        child(label, "span", "fact-name", I18n.get(FIELD + "flight_speed"));
        child(label, "span", "damage-chevron", "").setAttribute("aria-hidden", "true");
        child(toggle, "span", "fact-value speed-range", "").setAttribute("id", "speed-range");
        Element area = child(el("facts"), "div", "speed-panel", "");
        area.setAttribute("id", "speed-panel");
        Element controls = child(area, "div", "speed-selects", "");
        String altitudeText = I18n.get(FIELD + "speed.altitude");
        Select altitude = nativeSelect(controls, "speed-altitude", altitudeText);
        for (int height : LAUNCH_ALTITUDES)
            damageOption(altitude, Integer.toString(height), altitudeText + " " + height + " m");
        altitude.setValue(Integer.toString(launchAltitude));
        altitude.addEventListener("change", e -> {
            launchAltitude = Integer.parseInt(altitude.getValue());
            sampling = null;
            updateForces();
            updateSpeedPlot();
        });
        if (carrierMaxSpeed > 0) {
            Select carrier = nativeSelect(controls, "speed-carrier", I18n.get(FIELD + "speed.carrier"));
            String platform = I18n.get(FIELD + "speed.platform");
            for (int step = 0; step <= MAX_CARRIER_STEP; step++)
                damageOption(carrier, Integer.toString(step), platform + " " + speedText(carrierSpeed(step), true));
            carrier.setValue(Integer.toString(carrierStep));
            carrier.addEventListener("change", e -> {
                carrierStep = Integer.parseInt(carrier.getValue());
                sampling = null;
                updateSpeedPlot();
            });
        }
        Canvas graph = (Canvas) area.appendChild(new Canvas(doc));
        graph.setClassName("speed-chart");
        graph.setAttribute("id", "speed-chart");
        graph.setAttribute("role", "img");
        graph.setAttribute("aria-label", I18n.get(FIELD + "speed.distance") + " / " + I18n.get(FIELD + "speed.axis"));
        drawnCurve = null;
        toggle.addEventListener("click", e -> {
            if (speedExpanded) {
                altitude.closePopup();
                if (el("speed-carrier") instanceof Select carrier) carrier.closePopup();
            }
            speedExpanded = !speedExpanded;
            updateSpeedPlot();
        });
        updateSpeedPlot();
    }

    private static String speedText(double blocksPerTick, boolean missile) {
        return speedNumber(blocksPerTick, missile) + (missile ? " KPH" : " m/s");
    }

    private static String speedNumber(double blocksPerTick, boolean missile) {
        return format(Math.round(blocksPerTick * (missile ? 72 : 20) * 10) / 10.0);
    }

    private void updateForces() {
        if (flightWeapon == null || !flightWeapon.usesPropulsion()) return;
        var f = RVP_WorkshopBallistics.forces(flightWeapon, launchAltitude);
        el("speed-forces").setTextContent(forceText(f));
    }

    private static String forceText(RVP_WorkshopBallistics.Forces f) {
        return Math.round(f.first()) + (f.second() == null ? "" : " → " + Math.round(f.second()))
                + " / " + Math.round(f.drag()) + " N";
    }

    private int curveKey() {
        return launchAltitude * (MAX_CARRIER_STEP + 1) + carrierStep;
    }

    private double carrierSpeed(int step) {
        return carrierMaxSpeed * step / MAX_CARRIER_STEP;
    }

    private void updateSpeedPlot() {
        el("speed-toggle").setAttribute("aria-expanded", Boolean.toString(speedExpanded));
        show("speed-panel", speedExpanded);
        var curve = curves.get(curveKey());
        boolean missile = flightWeapon.getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        el("speed-range").setTextContent(curve == null ? "…"
                : speedNumber(curve.points().get(0).speed(), missile) + "～" + speedText(curve.peak(), missile));
        show("speed-chart", curve != null);
        if (curve == null && sampling == null)
            sampling = new RVP_WorkshopTrajectory(vehicle.level(), flightWeapon, launchAltitude, carrierSpeed(carrierStep));
    }

    /** 布局完成后按 AUI 实际屏幕像素绘制位图，条件及尺寸相同时复用上次绘制。 */
    private void paintSpeedPlot() {
        if (!speedVisible || !speedExpanded) return;
        var curve = curves.get(curveKey());
        if (curve == null) return;
        Canvas canvas = (Canvas) el("speed-chart");
        var box = canvas.getBoundingClientRect();
        if (box.width < 1 || box.height < 1) return;
        double scale = doc.getViewport().scissorScale();
        int bitmapWidth = Math.max(1, (int) Math.round(box.width * scale));
        int bitmapHeight = Math.max(1, (int) Math.round(box.height * scale));
        var surface = canvas.getSurface();
        if (drawnCurve == curve && Math.abs(surface.getWidth() - bitmapWidth) <= 1
                && Math.abs(surface.getHeight() - bitmapHeight) <= 1) return;
        boolean missile = flightWeapon.getWeaponKind() == RVP_EnumWeaponKind.MISSILE;
        RVP_WorkshopSpeedPlot.draw(canvas, curve, missile, (int) Math.round(box.width), (int) Math.round(box.height),
                bitmapWidth, bitmapHeight);
        drawnCurve = curve;
    }

    private void damageDetails(ResourceLocation weapon, List<RVP_WorkshopDamage.Scenario> entities,
                               List<RVP_WorkshopDamage.Scenario> blocks) {
        if (!weapon.equals(damageWeapon)) {
            damageWeapon = weapon;
            damageContext = null;
            damageExpanded = false;
        }
        if (entities.isEmpty()) damageTarget = RVP_WorkshopDamage.BLOCK;
        boolean block = RVP_WorkshopDamage.BLOCK.equals(damageTarget);
        var scenarios = block ? blocks : entities;
        var initial = scenarios.stream().filter(scenario -> scenario.context() == damageContext)
                .findFirst().orElse(scenarios.get(0));
        damageContext = initial.context();
        Element panel = child(el("facts"), "section", "damage-details", "");
        panel.setAttribute("aria-label", I18n.get(FIELD + "damage_details"));
        Element controls = damageElement(panel, "div", "damage-controls", "controls", "");
        Element labels = child(controls, "div", "damage-control-labels", "");
        Element inputs = child(controls, "div", "damage-control-selects", "");
        damageElement(labels, "span", "fact-name", "power-name", I18n.get(FIELD + "power"));
        Select target = damageSelect(inputs, "target", I18n.get(FIELD + "target"));
        Element total = damageElement(controls, "span", "fact-value damage-total", "power-value", "");
        total.setAttribute("aria-live", "polite");
        total.setAttribute("aria-label", I18n.get(FIELD + "current_power"));

        // 同时列出当前载具包中的类型、装甲分类和此弹药独有的倍率键。
        var types = new TreeSet<>(stats.vehicleTypes());
        scenarios.forEach(scenario -> types.addAll(scenario.shot().vehicleTypes()));
        var targets = new ArrayList<String>();
        if (!entities.isEmpty()) {
            targets.addAll(List.of(RVP_WorkshopDamage.DEFAULT_VEHICLE, RVP_WorkshopDamage.PLAYER, RVP_WorkshopDamage.LIVING));
            types.stream().filter(type -> type.contains(":") && ResourceLocation.tryParse(type) != null).forEach(targets::add);
        }
        targets.add(RVP_WorkshopDamage.BLOCK);
        for (String kind : targets) {
            String key = FIELD + "target." + kind.replace(':', '.').replace('/', '.');
            damageOption(target, kind, I18n.exists(key) ? I18n.get(key) : kind);
        }
        if (!targets.contains(damageTarget)) damageTarget = RVP_WorkshopDamage.DEFAULT_VEHICLE;
        target.setValue(damageTarget);

        if (scenarios.size() > 1) {
            String label = I18n.get(FIELD + "context");
            child(labels, "span", "fact-name", label);
            Select selector = damageSelect(inputs, "context", label);
            for (var scenario : scenarios) {
                var context = scenario.context();
                String title = I18n.get(FIELD + "context." + context.key());
                if (scenario.threshold() != null)
                    title += " " + format(scenario.threshold()) + (context == RVP_WorkshopDamage.Context.DISTANCE ? " m" : " s");
                damageOption(selector, context.key(), title);
            }
            selector.setValue(damageContext.key());
            selector.addEventListener("change", ignored -> {
                damageContext = scenarios.stream().map(RVP_WorkshopDamage.Scenario::context)
                        .filter(value -> value.key().equals(selector.getValue())).findFirst().orElse(initial.context());
                damageExpanded = false;
                updateDamage(scenarios);
            });
        }

        Element sources = damageElement(panel, "div", "damage-sources", "sources", "");
        sources.setAttribute("aria-label", I18n.get(FIELD + "sources"));
        Element more = damageElement(panel, "button", "damage-more", "more", "");
        more.setAttribute("type", "button");
        more.setAttribute("aria-controls", "damage-sources");
        child(more, "span", "damage-chevron", "").setAttribute("aria-hidden", "true");
        more.addEventListener("click", ignored -> {
            damageExpanded = !damageExpanded;
            updateDamageSources(currentShot(scenarios));
        });
        damageFact(panel, "radius");
        damageFact(panel, "effects");
        damageElement(panel, "div", "penetration", "grid", "")
                .setAttribute("aria-label", I18n.get(FIELD + "impact_table"));
        target.addEventListener("change", ignored -> {
            damageTarget = target.getValue();
            if (block != RVP_WorkshopDamage.BLOCK.equals(damageTarget)) {
                if (el("damage-context") instanceof Select select) select.closePopup();
                panel.remove();
                damageExpanded = false;
                damageDetails(weapon, entities, blocks);
            } else updateDamage(scenarios);
        });
        updateDamage(scenarios);
    }

    private Element damageElement(Element parent, String tag, String css, String id, String text) {
        Element element = child(parent, tag, css, text);
        element.setAttribute("id", "damage-" + id);
        return element;
    }

    private Select damageSelect(Element parent, String id, String label) {
        return nativeSelect(parent, "damage-" + id, label);
    }

    private Select nativeSelect(Element parent, String id, String label) {
        // 显式挂载 ApricityUI 的原生控件，沿用键盘及弹出层行为。
        Select select = new Select(doc);
        select.setClassName("damage-select");
        select.setAttribute("id", id);
        select.setAttribute("aria-label", label);
        return (Select) parent.appendChild(select);
    }

    private void damageOption(Select select, String value, String label) {
        child(select, "option", "", label).setAttribute("value", value);
    }

    private void damageFact(Element parent, String name) {
        Element row = damageElement(parent, "div", "fact damage-stat", name + "-row", "");
        String label = name.equals("radius") && RVP_WorkshopDamage.BLOCK.equals(damageTarget) ? "block_radius" : name;
        child(row, "span", "fact-name", I18n.get(FIELD + label));
        damageElement(row, "span", "fact-value", name, "");
    }

    private RVP_WorkshopDamage.Shot currentShot(List<RVP_WorkshopDamage.Scenario> scenarios) {
        return scenarios.stream().filter(scenario -> scenario.context() == damageContext).findFirst().orElseThrow().shot();
    }

    private void updateDamage(List<RVP_WorkshopDamage.Scenario> scenarios) {
        var damage = currentShot(scenarios);
        boolean power = damage.hasPower();
        el("damage-controls").setClassName("damage-controls");
        show("damage-power-name", true);
        show("damage-target", true);
        show("damage-power-value", power);
        el("damage-power-value").setTextContent(power ? format(damage.power(damageTarget)) : "");
        updateDamageSources(damage);
        Double radius = damage.radius(damageTarget);
        updateDamageFact("radius", radius == null ? "" : format(radius) + " m");
        updateDamageFact("effects", String.join(I18n.get(FIELD + "effects.separator"),
                damage.effects().stream().map(effect -> I18n.get(FIELD + "effects." + effect)).toList()));
        Element grid = el("damage-grid");
        boolean aircraft = vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle;
        boolean showGrid = !RVP_WorkshopDamage.BLOCK.equals(damageTarget) && damage.hasTableDamage(aircraft);
        show("damage-grid", showGrid);
        if (showGrid) damageTable(grid, damage);
        else grid.setTextContent("");
    }

    private void updateDamageFact(String name, String value) {
        el("damage-" + name + "-row").setClassName("fact damage-stat" + (value.isEmpty() ? " hidden" : ""));
        el("damage-" + name).setTextContent(value);
    }

    private void updateDamageSources(RVP_WorkshopDamage.Shot damage) {
        Element sources = el("damage-sources");
        sources.setTextContent("");
        var rows = damage.stages().stream().filter(RVP_WorkshopDamage.Stage::hasDamage).toList();
        int visible = damageExpanded ? rows.size() : Math.min(COLLAPSED_SOURCES, rows.size());
        for (int i = 0; i < visible; i++) {
            var stage = rows.get(i);
            Element line = child(sources, "div", "damage-source", "");
            Element label = child(line, "span", "damage-source-label", "");
            child(label, "span", "damage-source-branch", i + 1 == rows.size() ? "└─" : "├─")
                    .setAttribute("aria-hidden", "true");
            String name = stage.source() == RVP_WorkshopDamage.Source.ROOT
                    ? "" : I18n.get(FIELD + "source." + stage.source().key()) + " ";
            boolean direct = stage.direct() != null;
            boolean blast = stage.explosion() != null;
            String type = stage.source() == RVP_WorkshopDamage.Source.PROXIMITY ? "proximity"
                    : direct && blast ? "both" : direct ? "direct" : "blast";
            child(label, "span", "damage-source-name", name + I18n.get(FIELD + "damage_type." + type));
            String amount = direct ? format(stage.directFor(damageTarget)) : "";
            if (blast) amount += (direct ? " / " : "") + format(stage.explosionFor(damageTarget));
            if (stage.count().compareTo(BigInteger.ONE) > 0) amount += " ×" + stage.count();
            child(line, "span", "damage-source-value", amount);
        }
        Element more = el("damage-more");
        more.setClassName("damage-more" + (rows.size() > COLLAPSED_SOURCES ? "" : " hidden"));
        more.setAttribute("aria-expanded", Boolean.toString(damageExpanded && rows.size() > COLLAPSED_SOURCES));
        more.setAttribute("aria-label", I18n.get(FIELD + (damageExpanded ? "collapse" : "expand")));
    }

    /** 每格显示直击与爆炸合计，跳弹用文字表示。 */
    private void damageTable(Element table, RVP_WorkshopDamage.Shot shot) {
        table.setTextContent("");
        Element header = child(table, "div", "damage-row damage-header", "");
        child(header, "span", "damage-distance", I18n.get(FIELD + "distance"));
        for (int angle : ANGLES) child(header, "span", "damage-cell", angle + "°");
        for (int distance : DISTANCES) {
            Element row = child(table, "div", "damage-row", "");
            child(row, "span", "damage-distance", distance + " m");
            for (int angle : ANGLES) {
                Double damage = shot.impact(damageTarget, distance, angle);
                child(row, "span", damage == null ? "damage-cell ricochet" : "damage-cell",
                        damage == null ? I18n.get(FIELD + "ricochet") : format(damage));
            }
        }
    }

    private void bindColor(String channel, ForgeConfigSpec.ConfigValue<Integer> config) {
        Element range = el(channel + "-range"), number = el(channel + "-value");
        range.setValue(config.get().toString());
        number.setValue(range.getValue());
        for (String event : List.of("input", "change")) {
            range.addEventListener(event, ignored -> changeColor(range, number, config, true));
            number.addEventListener(event, ignored -> changeColor(number, range, config, event.equals("change")));
        }
    }

    private void changeColor(Element input, Element other, ForgeConfigSpec.ConfigValue<Integer> config, boolean commit) {
        int n;
        try {
            n = Mth.clamp(Integer.parseInt(input.getValue()), 0, 255);
        } catch (NumberFormatException invalid) {
            if (commit) input.setValue(config.get().toString());
            return;
        }
        String value = Integer.toString(n);
        if (commit && !value.equals(input.getValue())) input.setValue(value);
        if (!value.equals(other.getValue())) other.setValue(value);
        if (config.get() != n) config.set(n);
        updateSwatch();
        if (panel == Panel.SMOKE) refreshDetails();
    }

    private String rgbText() {
        var c = AllConfigs.common;
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.aerobaticSmokeR.get(), c.aerobaticSmokeG.get(), c.aerobaticSmokeB.get());
    }

    private void updateSwatch() {
        if (smokeRow == null) return;
        String color = rgbText();
        setRow(smokeRow, color, color, panel == Panel.SMOKE);
        el("swatch").setInlineStyleProperty("background-color", color);
    }

    private void tickSpeed() {
        if (speedVisible && sampling != null) {
            // 只在菜单客户端刻采样。切换条件会取消上一项，每帧渲染仅显示缓存位图。
            var curve = sampling.advance(1_500_000);
            if (curve != null) {
                curves.put(curveKey(), curve);
                sampling = null;
                updateSpeedPlot();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (doc == null) return;
        tickSpeed();
        if (!auxiliary.options.isEmpty()) {
            if (panel == Panel.AUXILIARY && (vehicle.isRemoved() || vehicle.level() != minecraft.level)) {
                onClose();
                return;
            }
            auxiliary.tick(panel == Panel.AUXILIARY);
            if (panel == Panel.AUXILIARY) {
                int applied = auxiliary.appliedIndex();
                if (applied != lastAppliedAuxiliary || auxiliary.selectedIndex() != lastEquipped) {
                    lastAppliedAuxiliary = applied;
                    refreshRows();
                    refreshDetails();
                }
                updateAuxiliaryStatus();
            }
        }
        long now = Util.getMillis();
        slotLabels.forEach(label -> label.update(now));
        optionLabels.forEach(label -> label.update(now));
        if (pendingStation >= 0 && (stations.get(pendingStation).equipped() == pendingWeapon || --pendingWeaponTicks <= 0)) pendingStation = -1;
        if (pendingPaint != null && (pendingPaint.equals(vehicle.getDisplayId()) || --pendingPaintTicks <= 0)) pendingPaint = null;
        // 服务端回包或其他玩家操作后按实体数据更新，只有显示值变化才重绘。
        if (vehicle.tickCount % 5 == 0) {
            if (stats != RVP_WorkshopData.current()) refreshDetails();
            refreshRows();
            int active = panel == Panel.WEAPON ? equipped(stationIndex) : panel == Panel.PAINT ? visiblePaints.indexOf(paint()) : -1;
            if (panel != Panel.AUXILIARY && active != lastEquipped) refreshDetails();
        }
    }

    private boolean over(String id, double x, double y) {
        var rect = el(id).getBoundingClientRect();
        double scale = doc.getViewport().renderScale();
        return rect.width > 0 && rect.height > 0 && x >= rect.x * scale && x < (rect.x + rect.width) * scale
                && y >= rect.y * scale && y < (rect.y + rect.height) * scale;
    }

    private boolean overScene(double x, double y) {
        // 菜单可浮到卡片外，仍让 ApricityUI 处理选项、滚动和外部点击。
        return doc != null && !(el("damage-target") instanceof Select select && select.isPopupOpen())
                && !(el("damage-context") instanceof Select context && context.isPopupOpen())
                && !(el("speed-altitude") instanceof Select altitude && altitude.isPopupOpen())
                && !(el("speed-carrier") instanceof Select carrier && carrier.isPopupOpen())
                && !over("navigation", x, y) && (panel == Panel.PROFILE || !over("options", x, y))
                && !over("detail", x, y) && !over("vehicle-title", x, y)
                && !over("reset-view", x, y) && !over("close-button", x, y);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (!Screen.hasControlDown() && overScene(x, y)) { camera().zoom(delta); return true; }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if ((button == 0 || button == 1) && overScene(x, y)) { dragButton = button; return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragButton == button) { camera().drag(button, dx, dy, width, height); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (dragButton == button) { dragButton = -1; return true; }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float partialTick) {
        camera().render(graphics, panel == Panel.AUXILIARY ? auxiliary.previewVehicle : vehicle, width, height);
        super.render(graphics, mx, my, partialTick);
        if (doc == null) return;
        paintSpeedPlot();
        long now = Util.getMillis();
        double scale = doc.getViewport().renderScale();
        for (var labels : List.of(optionLabels, slotLabels)) {
            for (var label : labels) {
                if (label.hint(mx / scale, my / scale, now)) {
                    var lines = font.split(Component.literal(label.ink.getTextContent()), Math.max(32, Math.min(215, width - 18)));
                    graphics.renderTooltip(font, lines, mx, my);
                    return;
                }
            }
        }
    }

    @Override
    public void onClose() {
        super.onClose();
        if (parent != null) Minecraft.getInstance().setScreen(parent);
    }
}
