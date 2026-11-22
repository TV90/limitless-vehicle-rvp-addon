package org.ywzj.rvp.client.screen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sighs.apricityui.element.Canvas;
import com.sighs.apricityui.element.Select;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.parser.HTML;
import com.sighs.apricityui.viewport.ApricityViewport;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.ywzj.rvp.config.RVP_WorkshopDamage;
import org.ywzj.rvp.config.RVP_WorkshopData;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationLevel;
import org.ywzj.rvp.entity.projectile.RVP_WorkshopTrajectory;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 在无窗口环境调用游戏菜单的实际入口，检查数值、翻译、布局和原生选择事件。 */
@Execution(ExecutionMode.SAME_THREAD)
class RVP_WorkshopDamageAuditTest {
    /** 独立的距离和角度样本。 */
    private static final List<String> DISTANCES = List.of("10 m", "100 m", "500 m", "1000 m", "1500 m", "2000 m");
    private static final List<String> ANGLES = List.of("0°", "30°", "60°", "80°");
    /** 核对定稿实际进入两个原生菜单。 */
    private static final Map<String, String> CONTEXT_NAMES = Map.of(
            "impact", "命中", "airburst", "可编程空爆", "delay", "延时引爆",
            "ground_proximity", "近地引爆", "proximity", "近炸触发", "normal_blast", "常规爆炸", "underwater", "水下爆炸",
            "intercept", "被拦截");
    private static final String FIELD = "gui.ywzj_rvp.workshop.field.";
    /** 仅在所有实际档案用例完成后导出快照。 */
    private static final Map<String, List<View>> CAPTURES = new LinkedHashMap<>();
    private static JsonObject expected;
    private static final Map<ResourceLocation, RVP_WorkshopDamage.Projectile> PROJECTILES = new LinkedHashMap<>();
    /** 重用同一文档及其 ApricityUI 布局器。 */
    private static Document document;
    private static RVP_AuiVariantScreen screen;
    private static Method details;
    private static Method weaponFacts;
    private static Method commit;
    /** 还原测试进程原来的全局语言。 */
    private static Language previousLanguage;

    @BeforeAll static void prepare() throws Exception {
        // 沿用本体注册表和解析链路。静态翻译节点在无窗口测试中不展开，动态字样使用游戏语言资源。
        RVP_ProjectileSimulationBootstrap.initialize();
        JsonObject weapons = jsonResource("/workshop/damage-scenarios.json").getAsJsonObject("weapons");
        weapons.entrySet().forEach(entry -> {
            JsonObject raw = entry.getValue().getAsJsonObject();
            PROJECTILES.put(id(entry.getKey()), new RVP_WorkshopDamage.Projectile(raw,
                    GsonUtil.GSON.fromJson(raw, RVP_WeaponData.class)));
        });
        expected = jsonResource("/workshop/display-expectations.json");
        Map<String, String> translations = new LinkedHashMap<>();
        jsonResource("/assets/ywzj_rvp/lang/zh_cn.json").entrySet()
                .forEach(entry -> translations.put(entry.getKey(), entry.getValue().getAsString()));
        var language = ClientLanguage.class.getDeclaredConstructor(Map.class, boolean.class);
        language.setAccessible(true);
        previousLanguage = (Language) RVP_ProjectileSimulationBootstrap.read(I18n.class, null, "language");
        RVP_ProjectileSimulationBootstrap.write(I18n.class, null, "language", language.newInstance(translations, false));

        String template = "workshop-compact-production-test.html";
        HTML.putTemple(template, resource("/assets/apricityui/apricity/screens/rvp_variants.html")
                .replaceAll("<translation>[^<]*</translation>", ""));
        document = Document.create(template);
        screen = RVP_ProjectileSimulationBootstrap.environment(RVP_AuiVariantScreen.class);
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "doc", document);
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "stats", RVP_WorkshopData.current());
        details = RVP_AuiVariantScreen.class.getDeclaredMethod("damageDetails", ResourceLocation.class, List.class, List.class);
        weaponFacts = RVP_AuiVariantScreen.class.getDeclaredMethod("weaponFacts", RVP_WorkshopData.Weapon.class, int.class, boolean.class);
        commit = Select.class.getDeclaredMethod("commitUserSelection", int.class);
        for (Method method : List.of(details, weaponFacts, commit)) method.setAccessible(true);
    }

    @AfterAll static void export() throws IOException {
        if (previousLanguage != null)
            RVP_ProjectileSimulationBootstrap.write(I18n.class, null, "language", previousLanguage);
        if (expected == null || CAPTURES.size() != expected.size()) return;
        Path output = Path.of("build/reports/workshop-compact-integration/snapshots.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(CAPTURES));
    }

    @TestFactory Stream<DynamicTest> exactWeaponReadoutsByContext() {
        return expected.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(),
                () -> verify(entry.getKey(), entry.getValue().getAsJsonObject())));
    }

    @Test void effectiveSingleRoundCapacityHidesOnlyModeAndRate() throws Exception {
        var weapon = new RVP_WorkshopData.Weapon(false, "", "", "BURST", 20.0, 800.0, 30.0, 1.25);
        var rows = document.getElementById("facts");
        rows.setTextContent("");
        // 模拟弹药配置 30 发、武器站覆写 1 发。正式入口将 getMaxCapacity 的值交给同一渲染方法。
        weaponFacts.invoke(screen, weapon, 1, true);
        var labels = rows.querySelectorAll(".fact-name").stream().map(Element::getTextContent).toList();
        assertEquals(List.of(I18n.get(FIELD + "caliber"), I18n.get(FIELD + "capacity"), I18n.get(FIELD + "reload")), labels);
        assertEquals("1", rows.querySelectorAll(".fact-value").get(1).getTextContent());
        rows.setTextContent("");
        weaponFacts.invoke(screen, weapon, 2, true);
        labels = rows.querySelectorAll(".fact-name").stream().map(Element::getTextContent).toList();
        assertTrue(labels.containsAll(List.of(I18n.get(FIELD + "mode"), I18n.get(FIELD + "rpm"))));
        assertTrue(rows.querySelectorAll(".fact-value").stream().anyMatch(node -> "800 RPM".equals(node.getTextContent())));
    }

    @Test void z10ClusterRocketDisplaysGroundFuseAndSevenSubmunitions() throws Exception {
        var weapon = ResourceLocation.tryParse("rvp:z10_fs70b");
        var child = ResourceLocation.tryParse("rvp:z10_fs70b_sub");
        var mother = packProjectile(weapon);
        var children = Map.of(child, packProjectile(child));
        var scenarios = RVP_WorkshopDamage.read(weapon, mother, children::get);
        assertEquals(List.of(RVP_WorkshopDamage.Context.IMPACT, RVP_WorkshopDamage.Context.GROUND_PROXIMITY),
                scenarios.stream().map(RVP_WorkshopDamage.Scenario::context).toList());
        render(weapon, scenarios, RVP_WorkshopDamage.read(weapon, mother, children::get, true));
        var impact = capture("impact", RVP_WorkshopDamage.DEFAULT_VEHICLE);
        assertEquals("60", impact.power());
        assertEquals("跳弹", impact.matrix().get(2).get(4));

        Select context = (Select) byId("context");
        assertEquals(List.of("命中", "近地引爆"),
                context.querySelectorAll("option").stream().map(Element::getTextContent).toList());
        commit.invoke(context, 1);
        var ground = capture("ground_proximity", RVP_WorkshopDamage.DEFAULT_VEHICLE);
        assertEquals(List.of("爆炸", "子级弹药 直击／爆炸"), ground.labels());
        assertEquals(List.of("10", "40 / 15 ×7"), ground.rows());
        assertEquals("395", ground.power());
        assertEquals("395", ground.matrix().get(2).get(1));
        assertEquals("283", ground.matrix().get(2).get(3));
        selectTarget((Select) byId("target"), "ywzj_vehicle:wheeled_vehicle");
        assertEquals("698.5", capture("ground_proximity", "ywzj_vehicle:wheeled_vehicle").power());

        JsonObject late = mother.raw().deepCopy();
        late.getAsJsonObject("fuse_data").addProperty("ground_proximity_fuse_arm_tick", Integer.MAX_VALUE);
        assertEquals(List.of(RVP_WorkshopDamage.Context.IMPACT), RVP_WorkshopDamage.read(weapon, projectile(late), children::get)
                .stream().map(RVP_WorkshopDamage.Scenario::context).toList());
    }

    @Test void blockSelectionShowsItsOwnRadiusAndTriggersAndCanSwitchBack() throws Exception {
        open("special_triggers");
        selectTarget((Select) byId("target"), RVP_WorkshopDamage.BLOCK);
        assertEquals(List.of("命中方块", "穿透方块后", "被拦截"),
                byId("context").querySelectorAll("option").stream().map(Element::getTextContent).toList());
        assertEquals("24", byId("power-value").getTextContent());
        commit.invoke(byId("context"), 1);
        assertEquals("6", byId("power-value").getTextContent());
        selectTarget((Select) byId("target"), RVP_WorkshopDamage.DEFAULT_VEHICLE);
        assertEquals("40", byId("power-value").getTextContent());

        var id = ResourceLocation.tryParse("rvp:z10_fs70b_sub");
        var round = packProjectile(id);
        render(id, RVP_WorkshopDamage.read(id, round, child -> null),
                RVP_WorkshopDamage.read(id, round, child -> null, true));
        assertEquals("3 m", shownText("radius"));
        selectTarget((Select) byId("target"), RVP_WorkshopDamage.BLOCK);
        assertEquals("1 m", shownText("radius"));
        assertEquals("方块破坏半径", byId("radius-row").querySelector(".fact-name").getTextContent());
        selectTarget((Select) byId("target"), RVP_WorkshopDamage.PLAYER);
        assertEquals("3 m", shownText("radius"));
        assertEquals("爆炸半径", byId("radius-row").querySelector(".fact-name").getTextContent());
    }

    @Test void timedDistanceAndBounceThresholdsUseTheirOwnUnits() throws Exception {
        var raw = PROJECTILES.get(id("ground_timer_life")).raw().deepCopy();
        raw.add("projectile_data", JsonParser.parseString("{\"self_destruct_distance\":512,\"self_destruct_explode\":true}"));
        raw.add("collision_data", JsonParser.parseString("{\"bounce\":2,\"bounce_fuse_tick\":8}"));
        for (int delay : List.of(10, 120)) {
            raw.getAsJsonObject("fuse_data").addProperty("delay_tick", delay);
            var p = new RVP_WorkshopDamage.Projectile(raw, GsonUtil.GSON.fromJson(raw, RVP_WeaponData.class));
            var contexts = RVP_WorkshopDamage.read(id("fuses"), p, PROJECTILES::get);
            render(id("fuses"), contexts, RVP_WorkshopDamage.read(id("fuses"), p, PROJECTILES::get, true));
            for (var entry : Map.of("delay", delay == 10 ? "0.5 s" : "4.5 s", "distance", "512 m", "bounce_delay", "0.4 s").entrySet()) {
                selectTarget((Select) byId("context"), entry.getKey());
                assertTrue(byId("explanation").getTextContent().endsWith(entry.getValue()), entry.toString());
            }
        }
    }

    @Test void aim260UsesRecordedGameSpeedAndMenuControls() throws Exception {
        var weapon = packProjectile(ResourceLocation.tryParse("rvp:f22a_aim260a"));
        var level = RVP_ProjectileSimulationLevel.create(64, null);
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.level.Level.class, level, "random",
                net.minecraft.util.RandomSource.create(1));
        var vehicle = RVP_ProjectileSimulationBootstrap.environment(TrackedVehicle.class);
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.entity.Entity.class, vehicle, "level", level);
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "vehicle", vehicle);
        var curves = new HashMap<Integer, RVP_WorkshopTrajectory.Curve>();
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "curves", curves);
        var speed = RVP_AuiVariantScreen.class.getDeclaredMethod("speedDetails", JsonObject.class, BaseVehicleWeaponData.class);
        var tick = RVP_AuiVariantScreen.class.getDeclaredMethod("tickSpeed");
        speed.setAccessible(true);
        tick.setAccessible(true);
        var facts = document.getElementById("facts");
        facts.setTextContent("");
        speed.invoke(screen, weapon.raw(), weapon.resolved());
        assertEquals("true", document.getElementById("speed-toggle").getAttribute("aria-expanded"));
        for (int i = 0; curves.isEmpty() && i < 605; i++) tick.invoke(screen);
        var curve = curves.get(20);
        assertNotNull(curve);
        assertEquals(114.1192, curve.peak() * 20, 0.01);
        assertEquals(1040.46, curve.points().get(curve.points().size() - 1).distance(), 0.1);
        assertEquals(List.of("ignition", "burnout", "ignition2", "end"), curve.marks().stream().map(RVP_WorkshopTrajectory.Mark::key).toList());
        assertEquals(655.1, curve.points().get(curve.marks().get(2).sample()).distance(), 0.2);
        assertEquals("410.8 KPH", document.getElementById("speed-peak").getTextContent());
        assertTrue(facts.querySelectorAll(".fact-value").stream().map(Element::getTextContent).toList()
                .containsAll(List.of("7.2 KPH", "3290 → 1600 / 428 N", "0–1500 m / 12°", "26.1 G")));
        assertTrue(facts.querySelectorAll(".fact-name").stream().noneMatch(e -> e.getTextContent().contains("限速")));
        Canvas canvas = (Canvas) document.getElementById("speed-chart");
        Path image = Path.of("build/reports/workshop-compact-integration/aim260-speed.png");
        Files.createDirectories(image.getParent());
        javax.imageio.ImageIO.write(canvas.getSurface(), "png", image.toFile());
        selectTarget((Select) document.getElementById("speed-scene"), "1");
        selectTarget((Select) document.getElementById("speed-carrier"), "40");
        for (int i = 0; !curves.containsKey(140) && i < 605; i++) tick.invoke(screen);
        assertEquals(List.of("ignition", "end"), curves.get(140).marks().stream().map(RVP_WorkshopTrajectory.Mark::key).toList());
        selectTarget((Select) document.getElementById("speed-scene"), "0");
        selectTarget((Select) document.getElementById("speed-carrier"), "20");
        assertSame(curve, curves.get(20));
        assertEquals("410.8 KPH", document.getElementById("speed-peak").getTextContent());
        var surface = packProjectile(ResourceLocation.tryParse("rvp:ea18g_agm65f"));
        facts.setTextContent("");
        speed.invoke(screen, surface.raw(), surface.resolved());
        assertEquals("false", document.getElementById("speed-toggle").getAttribute("aria-expanded"));
        var cannon = packProjectile(ResourceLocation.tryParse("rvp:f22a_m61a1"));
        facts.setTextContent("");
        speed.invoke(screen, cannon.raw(), cannon.resolved());
        assertEquals("320 m/s", facts.querySelector(".fact-value").getTextContent());
        assertNull(document.getElementById("speed-toggle"));
    }

    @Test void trajectoryStopsAtTheConfiguredTimerOrDistance() throws Exception {
        var raw = packProjectile(ResourceLocation.tryParse("rvp:f22a_aim260a")).raw().deepCopy();
        var level = RVP_ProjectileSimulationLevel.create(64, null);
        RVP_ProjectileSimulationBootstrap.write(net.minecraft.world.level.Level.class, level, "random",
                net.minecraft.util.RandomSource.create(1));
        var scene = RVP_WorkshopTrajectory.SCENES.get(0);
        raw.getAsJsonObject("projectile_data").addProperty("self_destruct_distance", 2.5);
        var data = projectile(raw).resolved();
        data.setWeaponKind(org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MISSILE);
        var distance = new RVP_WorkshopTrajectory(level, data, scene, 20).advance(Long.MAX_VALUE);
        assertEquals(2.5, distance.points().get(distance.points().size() - 1).distance());
        raw.getAsJsonObject("fuse_data").addProperty("delay_tick", 1);
        data = projectile(raw).resolved();
        data.setWeaponKind(org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.MISSILE);
        var timed = new RVP_WorkshopTrajectory(level, data, scene, 20).advance(Long.MAX_VALUE);
        assertEquals(1, timed.points().size());
        assertEquals(0.0, timed.points().get(0).distance());
    }

    private static RVP_WorkshopDamage.Projectile packProjectile(ResourceLocation id) throws IOException {
        var weapon = org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon.read(
                Path.of("limitless_vehicle", "rvp", "data", id.getNamespace(), "weapons"), id.toString());
        return new RVP_WorkshopDamage.Projectile(weapon.source(), weapon.data());
    }

    private static RVP_WorkshopDamage.Projectile projectile(JsonObject raw) {
        JsonObject config = raw.deepCopy();
        // 无窗口测试未注册 Forge 的物品配方序列化器，结算只读取武器和引信数据。
        config.getAsJsonObject("reload").remove("ammo");
        return new RVP_WorkshopDamage.Projectile(raw, GsonUtil.GSON.fromJson(config, RVP_WeaponData.class));
    }

    @Test void sharedColumnAlignsBothSelectsAtRegularAndCompactSizes() throws Exception {
        ApricityViewport viewport = document.getViewport();
        try {
            for (int width : List.of(1600, 980)) {
                document.body.setClassName(width < 1080 ? "compact" : "");
                RVP_ProjectileSimulationBootstrap.write(Document.class, document, "viewport",
                        new ApricityViewport(width, 640, 1f, 1));
                open("exclusive");
                document.commitPendingStyleRecalcForRender();
                document.getPaintList();
                var controls = byId("controls");
                var targetBox = byId("target").getBoundingClientRect();
                var contextBox = byId("context").getBoundingClientRect();
                assertTrue(targetBox.width > 50 && contextBox.top > targetBox.top,
                        "两排应有独立且可点选的下拉框，窗口宽度 " + width);
                assertEquals(targetBox.left, contextBox.left, 0.5);
                assertEquals(targetBox.width, contextBox.width, 0.5);
                assertEquals(width < 1080 ? "19px" : "15px", controls.getComputedStyle().gap);
            }
        } finally {
            document.body.setClassName("");
            RVP_ProjectileSimulationBootstrap.write(Document.class, document, "viewport", viewport);
        }
    }

    private static void verify(String name, JsonObject variants) throws Exception {
        var scenarios = open(name);
        assertEquals(new ArrayList<>(variants.keySet()), scenarios.stream().map(s -> s.context().key()).toList());
        Select target = (Select) byId("target");
        assertEquals("默认", target.querySelectorAll("option").get(0).getTextContent());
        Select context = (Select) byId("context");
        assertEquals(scenarios.size() > 1, context != null);
        if (context != null) assertEquals(scenarios.stream().map(scenario -> CONTEXT_NAMES.get(scenario.context().key())).toList(),
                context.querySelectorAll("option").stream().map(Element::getTextContent).toList());
        var snapshots = new ArrayList<View>();
        for (int i = 0; i < scenarios.size(); i++) {
            String key = scenarios.get(i).context().key();
            if (i > 0) commit.invoke(context, i);
            commit.invoke(target, 0);
            View view = capture(key, "vehicle_default");
            JsonObject reference = variants.getAsJsonObject(key);
            if (reference.has("largePower"))
                assertTrue(new BigDecimal(view.power()).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0);
            else assertEquals(text(reference, "power"), view.power(), "威力");
            assertEquals(text(reference, "radius"), view.radius(), "半径");
            assertEquals(text(reference, "effects"), view.effects(), "附加效果");
            checkMatrix(reference, view);

            int sourceCount = (int) scenarios.get(i).shot().stages().stream().filter(RVP_WorkshopDamage.Stage::hasDamage).count();
            assertEquals(Math.min(2, sourceCount), view.rows().size());
            assertEquals(sourceCount > 2, visible(byId("more")));
            assertTrue(byId("more").getTextContent().isBlank(), "展开按钮只含图形");
            if (sourceCount > 2) {
                assertEquals("展开", byId("more").getAttribute("aria-label"));
                byId("more").click();
                view = capture(key, "vehicle_default");
                assertEquals(sourceCount, view.rows().size());
                assertEquals("收起", byId("more").getAttribute("aria-label"));
            }
            if (reference.has("rows")) assertEquals(strings(reference, "rows"), view.rows());
            if (reference.has("labels")) assertEquals(strings(reference, "labels"), view.labels());
            snapshots.add(view);
            if (reference.has("tracked")) {
                selectTarget(target, "ywzj_vehicle:tracked_vehicle");
                View tracked = capture(key, "ywzj_vehicle:tracked_vehicle");
                assertEquals(text(reference, "tracked"), tracked.power());
                assertEquals(sourceCount, tracked.rows().size());
                snapshots.add(tracked);
            }
            if (reference.has("player")) {
                selectTarget(target, "player");
                View player = capture(key, "player");
                assertEquals(text(reference, "player"), player.power());
                snapshots.add(player);
            }
        }
        CAPTURES.put(name, snapshots);
    }

    private static List<RVP_WorkshopDamage.Scenario> open(String name) throws Exception {
        ResourceLocation weapon = id(name);
        var scenarios = RVP_WorkshopDamage.read(weapon, PROJECTILES.get(weapon), PROJECTILES::get);
        assertFalse(scenarios.isEmpty(), name);
        render(weapon, scenarios, RVP_WorkshopDamage.read(weapon, PROJECTILES.get(weapon), PROJECTILES::get, true));
        return scenarios;
    }

    private static void render(ResourceLocation weapon, List<RVP_WorkshopDamage.Scenario> scenarios,
                               List<RVP_WorkshopDamage.Scenario> blocks) throws Exception {
        document.getElementById("facts").setTextContent("");
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "damageTarget",
                RVP_WorkshopDamage.DEFAULT_VEHICLE);
        RVP_ProjectileSimulationBootstrap.write(RVP_AuiVariantScreen.class, screen, "damageWeapon", null);
        details.invoke(screen, weapon, scenarios, blocks);
    }

    private static void selectTarget(Select target, String value) throws Exception {
        var options = target.querySelectorAll("option");
        int index = java.util.stream.IntStream.range(0, options.size())
                .filter(i -> value.equals(options.get(i).getAttribute("value"))).findFirst().orElseThrow();
        commit.invoke(target, index);
        assertEquals(value, target.getValue());
    }

    private static View capture(String context, String target) {
        document.commitPendingStyleRecalcForRender();
        List<Element> branches = document.querySelectorAll(".damage-source-branch");
        assertTrue(branches.stream().allMatch(node -> "normal".equals(node.getComputedStyle().fontStyle)));
        var labels = document.querySelectorAll(".damage-source-name").stream().map(node -> {
            assertEquals("italic", node.getComputedStyle().fontStyle);
            return node.getTextContent();
        }).toList();
        assertTrue(labels.stream().noneMatch(label -> label.contains("母弹") || label.contains("·") || label.startsWith(" ")));
        var rows = document.querySelectorAll(".damage-source-value").stream().map(Element::getTextContent).toList();
        var grid = document.querySelectorAll(".damage-row").stream()
                .map(row -> row.querySelectorAll(".damage-distance, .damage-cell").stream().map(Element::getTextContent).toList())
                .toList();
        if (!grid.isEmpty()) {
            assertEquals(7, grid.size());
            assertEquals(List.of("距离", "0°", "30°", "60°", "80°"), grid.get(0));
            assertEquals(DISTANCES, grid.subList(1, 7).stream().map(row -> row.get(0)).toList());
            assertTrue(grid.stream().allMatch(row -> row.size() == 5));
            assertEquals("按距离和入射角计算的直击与爆炸伤害", byId("grid").getAttribute("aria-label"));
        }
        assertEquals(!grid.isEmpty(), visible(byId("grid")));
        return new View(context, target, shownText("power-value"), shownText("radius"), shownText("effects"), labels, rows, grid);
    }

    private static void checkMatrix(JsonObject reference, View view) {
        if (reference.has("noMatrix")) assertTrue(view.matrix().isEmpty());
        if (reference.has("all")) {
            String value = text(reference, "all");
            for (List<String> row : view.matrix().subList(1, 7))
                assertEquals(List.of(value, value, value, value), row.subList(1, 5));
        }
        JsonObject cells = reference.getAsJsonObject("cells");
        if (cells != null) cells.entrySet().forEach(entry -> {
            String[] axes = entry.getKey().split("/");
            int row = DISTANCES.indexOf(axes[0] + " m") + 1;
            int column = ANGLES.indexOf(axes[1] + "°") + 1;
            assertEquals(entry.getValue().getAsString(), view.matrix().get(row).get(column), entry.getKey());
        });
    }

    private static boolean visible(Element element) { return !element.getComputedStyle().isDisplayNone(); }

    private static String shownText(String key) {
        if ("power-value".equals(key)) return visible(byId(key)) ? byId(key).getTextContent() : null;
        return visible(byId(key + "-row")) ? byId(key).getTextContent() : null;
    }

    private static Element byId(String key) { return document.getElementById("damage-" + key); }

    private static String text(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : null; }

    private static List<String> strings(JsonObject json, String key) {
        return json.getAsJsonArray(key).asList().stream().map(item -> item.getAsString()).toList();
    }

    private static ResourceLocation id(String key) {
        return Objects.requireNonNull(ResourceLocation.tryParse("workshop_test:" + key));
    }

    private static JsonObject jsonResource(String path) throws IOException {
        return JsonParser.parseString(resource(path)).getAsJsonObject();
    }

    private static String resource(String path) throws IOException {
        try (var stream = RVP_WorkshopDamageAuditTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(stream, path).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 从生产控件读取的实际显示文字。 */
    private record View(String context, String target, String power, String radius, String effects,
                        List<String> labels, List<String> rows, List<List<String>> matrix) {}
}
