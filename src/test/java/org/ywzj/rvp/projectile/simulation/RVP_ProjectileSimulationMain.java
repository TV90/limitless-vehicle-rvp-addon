package org.ywzj.rvp.projectile.simulation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Gradle 入口；读取权威武器 JSON 与场景，导出插件自动发现的回放索引。 */
public final class RVP_ProjectileSimulationMain {
    /** 不写机器时间；同一场景和输入文件重复运行可逐字节比较。 */
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private RVP_ProjectileSimulationMain() {}
    /** 索引只列出本次成功生成的场景。 */
    public record Entry(/** 场景 ID。 */ String id, /** 显示名。 */ String name, /** 相对文件名。 */ String file) {}
    /** 工作台通用索引格式。 */
    public record Index(/** 当前版本。 */ int schemaVersion, /** 回放列表。 */ List<Entry> replays) {}

    /** 参数：场景路径、输出目录、权威 weapons 目录、可选单武器 ID。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("需要参数：场景文件/目录 输出目录 weapons目录 单武器ID（可为空）");
        Path input = Path.of(args[0]), output = Path.of(args[1]), weapons = Path.of(args[2]);
        var scenes = new ArrayList<RVP_ProjectileSimulationScenario>();
        if (!args[3].isBlank()) {
            // 调用同一武器解析器，快捷生成任意武器的单发场景，不在渲染或实体中按 ID 分支。
            var weapon = RVP_ProjectileSimulationWeapon.read(weapons, args[3]);
            var scene = new RVP_ProjectileSimulationScenario();
            scene.id = "weapon_" + args[3].substring(4).replaceAll("[^a-z0-9_-]", "_");
            scene.name = weapon.name(); scene.weapon = args[3]; scene.target = new double[]{0, 64, 800};
            scenes.add(scene);
        } else {
            List<Path> files;
            if (Files.isDirectory(input)) {
                try (var paths = Files.list(input)) { files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
            } else files = List.of(input);
            for (Path file : files) scenes.add(read(file));
        }
        if (scenes.isEmpty()) throw new IllegalArgumentException("未找到弹体仿真场景：" + input);
        var ids = new HashSet<String>();
        var replays = new ArrayList<RVP_ProjectileSimulation.Replay>();
        for (var scene : scenes) {
            // 先校验全部输入并完成计算，任何失败都不会发布半份新索引。
            scene.validate();
            if (!ids.add(scene.id)) throw new IllegalArgumentException("重复场景 id：" + scene.id);
            var weapon = RVP_ProjectileSimulationWeapon.read(weapons, scene.weapon);
            replays.add(new RVP_ProjectileSimulation().run(scene, weapon));
        }
        Files.createDirectories(output);
        var entries = new ArrayList<Entry>();
        for (var replay : replays) {
            String filename = replay.id() + ".json";
            Files.writeString(output.resolve(filename), JSON.toJson(replay), StandardCharsets.UTF_8);
            entries.add(new Entry(replay.id(), replay.name(), filename));
            System.out.printf("[PROJECTILE-SIM] %s weapon=%s frames=%d -> %s%n",
                    replay.id(), replay.scene().weapon, replay.frames().size(), output.resolve(filename).toAbsolutePath());
        }
        Files.writeString(output.resolve("index.json"), JSON.toJson(new Index(1, entries)), StandardCharsets.UTF_8);
    }

    /** 读取当前场景格式，允许 UTF-8 文件头；不迁移历史键。 */
    public static RVP_ProjectileSimulationScenario read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        var scene = JSON.fromJson(text, RVP_ProjectileSimulationScenario.class);
        if (scene == null) throw new IllegalArgumentException("场景为空：" + file);
        scene.validate(); return scene;
    }
}
