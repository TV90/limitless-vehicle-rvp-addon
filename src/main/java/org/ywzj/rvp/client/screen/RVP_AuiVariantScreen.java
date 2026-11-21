package org.ywzj.rvp.client.screen;

import com.sighs.apricityui.element.Select;
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
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData.OpticalSightType;
import org.ywzj.vehicle.all.AllConfigs;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientVehicleChangeDisplay;
import org.ywzj.vehicle.util.AuiTextHelper;

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
    /** 两类武器共用的距离（米）与入射角（度），表格始终保持此顺序。 */
    private static final int[] DISTANCES = {10, 100, 500, 1000, 1500, 2000};
    private static final int[] ANGLES = {0, 30, 60, 80};
    private static final String FIELD = "gui.ywzj_rvp.workshop.field.";
    /** 外观行，显示当前涂装。 */
    private SlotRow paintRow;
    /** 烟雾行，显示当前颜色。 */
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
        smokeRow = row(slots, I18n.get("ui.aerobatic_smoke"), () -> open(Panel.SMOKE));
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
        // 烟雾仍写入本体的三个配置项，与原工具使用相同的取值范围。
        bindColor("r", AllConfigs.common.aerobaticSmokeR);
        bindColor("g", AllConfigs.common.aerobaticSmokeG);
        bindColor("b", AllConfigs.common.aerobaticSmokeB);
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

    /** 图标按武器的完整注册名查找。资源包可补透明 PNG，一张图用于小槽、候选项和说明。 */
    private String artwork(RVP_WorkshopStation station, int index) {
        if (index < 0 || index >= station.size()) return "";
        return weaponArt.computeIfAbsent(station.weapon(index).getData().getWeaponId(), id -> {
            String path = "icons/workshop/" + id.getNamespace() + "/" + id.getPath() + ".png";
            var resource = new ResourceLocation("apricityui", "apricity/" + path);
            return minecraft.getResourceManager().getResource(resource).isPresent() ? "/" + path : "";
        });
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
        // 打开档案与浏览说明不受限，提交时重新检查车辆速度和乘员。
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(vehicle)) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("message.ywzj_rvp.modding_blocked"), true);
            return;
        }
        stations.get(stationIndex).equip(vehicle, index);
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
        if (data.guided()) {
            var stages = new ArrayList<String>();
            if (!data.guidance().isBlank()) stages.add(enumLabel("guidance", data.guidance()));
            if (!data.terminalGuidance().isBlank()) stages.add(enumLabel("guidance", data.terminalGuidance()));
            if (!stages.isEmpty()) fact(FIELD + "guidance", String.join(" → ", stages));
        } else {
            numeric("caliber", data.caliber(), " mm");
            if (!data.fireMode().isBlank()) fact(FIELD + "mode", enumLabel("mode", data.fireMode()));
            numeric("rpm", data.rpm(), " RPM");
        }
        // 武器站可指定独立弹容量；使用当前候选实例，与实际装填取值一致。
        if (data.capacity() != null || weapon.getWeaponUnit().getAmmoCapacity() != -1)
            numeric("capacity", (double) weapon.getMaxCapacity(), "");
        numeric("reload", data.reloadSeconds(), " s");
        // 组合已选武器和实际装载的子弹药索引，同时读取同步原文，保留缺项语义。
        var projectile = new RVP_WorkshopDamage.Projectile(raw, weapon.getData() instanceof RVP_WeaponData rvp ? rvp : null);
        var damage = RVP_WorkshopDamage.read(id, projectile, childId -> new RVP_WorkshopDamage.Projectile(
                stats.weapon(childId), RVP_SubmunitionSpawner.loadWeaponData(childId)));
        numeric("radius", damage.radius(), " m");
        if (damage.hasPower()) damageDetails(damage);
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

    private void damageDetails(RVP_WorkshopDamage.Shot damage) {
        Element power = child(el("facts"), "div", "fact", "");
        Element label = child(power, "div", "power-label", "");
        child(label, "span", "fact-name", I18n.get(FIELD + "power"));
        // 显式挂载原生 Select，让弹出层、键盘和 change 走 ApricityUI 控件行为。
        Select select = new Select(doc);
        select.setClassName("damage-target");
        Element target = label.appendChild(select);
        target.setAttribute("id", "damage-target");
        target.setAttribute("aria-label", I18n.get(FIELD + "target"));

        // 同时列出当前载具包中的类型、装甲分类和此弹药独有的倍率键。
        var types = new TreeSet<>(stats.vehicleTypes());
        types.addAll(damage.vehicleTypes());
        var targets = new ArrayList<>(List.of(RVP_WorkshopDamage.DEFAULT_VEHICLE, RVP_WorkshopDamage.PLAYER,
                RVP_WorkshopDamage.LIVING));
        types.stream().filter(type -> type.contains(":") && ResourceLocation.tryParse(type) != null).forEach(targets::add);
        for (String kind : targets) {
            String key = FIELD + "target." + kind.replace(':', '.').replace('/', '.');
            Element option = child(target, "option", "", I18n.exists(key) ? I18n.get(key) : kind);
            option.setAttribute("value", kind);
        }
        if (!targets.contains(damageTarget)) damageTarget = RVP_WorkshopDamage.DEFAULT_VEHICLE;
        target.setValue(damageTarget);

        Element total = child(power, "span", "fact-value", "");
        Element sources = child(el("facts"), "div", "damage-sources", "");
        Element table = damage.hasDirect() ? child(el("facts"), "div", "penetration", "") : null;
        updateDamage(damage, total, sources, table);
        target.addEventListener("change", ignored -> {
            damageTarget = target.getValue();
            // 原地刷新数字和表格，保留选择控件与档案滚动位置。
            updateDamage(damage, total, sources, table);
        });
    }

    private void updateDamage(RVP_WorkshopDamage.Shot damage, Element total, Element sources, Element table) {
        total.setTextContent(format(damage.power(damageTarget)));
        sources.setTextContent("");
        long remaining = damage.stages().stream().filter(RVP_WorkshopDamage.Stage::hasDamage).count();
        for (int i = 0; i < damage.stages().size(); i++) {
            var stage = damage.stages().get(i);
            if (!stage.hasDamage()) continue;
            String key = damage.stages().size() == 1 ? "damage_source"
                    : i == 0 ? "damage_source.first" : "damage_source.followup";
            Element line = child(sources, "div", "damage-source", "");
            String branch = --remaining == 0 ? "└─ " : "├─ ";
            child(line, "span", "damage-source-name", branch + I18n.get(FIELD + key));
            String amount = format(stage.directFor(damageTarget)) + "/" + format(stage.explosionFor(damageTarget));
            child(line, "span", "damage-source-value", stage.count() > 1 ? amount + " ×" + stage.count() : amount);
        }
        if (table != null) penetration(table, damage);
    }

    /** 每格显示所有能命中的直击合计。跳弹格只显示文字。 */
    private void penetration(Element table, RVP_WorkshopDamage.Shot shot) {
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
        String color = rgbText();
        setRow(smokeRow, color, color, panel == Panel.SMOKE);
        el("swatch").setInlineStyleProperty("background-color", color);
    }

    @Override
    public void tick() {
        super.tick();
        if (doc == null) return;
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
