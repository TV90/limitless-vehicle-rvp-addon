package org.ywzj.rvp.entity.gunner.simulation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Gradle JavaExec 入口：接受场景文件或目录，导出 JSON 与回放索引。 */
public final class RVP_GunnerSimulationMain {
    /** 数值、UTF-8 与 null 目标保持确定性，不写当前时间。 */
    public static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private RVP_GunnerSimulationMain() {}
    /** 索引条目，文件名相对于输出目录。 */
    public record Entry(/** 场景标识。 */ String id, /** 场景名称。 */ String name, /** JSON 文件。 */ String file) {}
    /** 当前完整索引。 */
    public record Index(/** 格式版本。 */ int schemaVersion, /** 已生成回放。 */ List<Entry> replays) {}

    /** 两个可选参数：输入文件/目录、输出目录。 */
    public static void main(String[] args) throws IOException {
        if (args.length>2) throw new IllegalArgumentException("用法：输入场景文件或目录 输出目录");
        Path input=Path.of(args.length>0 ? args[0] : "src/test/resources/gunner-simulation");
        Path output=Path.of(args.length>1 ? args[1] : "build/gunner-simulation");
        List<Path> paths;
        if (Files.isDirectory(input)) {
            try (var files=Files.list(input)) { paths=files.filter(p->p.toString().endsWith(".json")).sorted().toList(); }
        } else paths=List.of(input);
        if (paths.isEmpty()) throw new IllegalArgumentException("未找到场景 JSON："+input);
        // 先完整读取并检查，重复 id 不得悄悄覆盖另一个场景的记录。
        List<RVP_GunnerSimulationScenario> scenes=new ArrayList<>();
        var ids=new HashSet<String>();
        for (Path path:paths) {
            var scene=read(path);
            if (!ids.add(scene.id)) throw new IllegalArgumentException("重复场景 id："+scene.id);
            scenes.add(scene);
        }
        Files.createDirectories(output);
        List<Entry> entries=new ArrayList<>();
        for (var scene:scenes) {
            // 调用逐刻仿真，同初态每次建立独立运行态。
            var replay=new RVP_GunnerSimulation().run(scene);
            String filename=scene.id+".json";
            Files.writeString(output.resolve(filename),JSON.toJson(replay),StandardCharsets.UTF_8);
            entries.add(new Entry(scene.id,scene.name,filename));
            System.out.printf("[GUNNER-SIM] %s ticks=%d blocked=%d attempts=%d recoveries=%d -> %s%n",
                    scene.id,scene.ticks,replay.summary().blockedTicks(),replay.summary().fireAttempts(),
                    replay.summary().recoveries(),output.resolve(filename).toAbsolutePath());
        }
        entries.sort(Comparator.comparing(Entry::id));
        Files.writeString(output.resolve("index.json"),JSON.toJson(new Index(1,List.copyOf(entries))),StandardCharsets.UTF_8);
    }

    /** 供自动测试和命令行共用的场景读取边界。 */
    public static RVP_GunnerSimulationScenario read(Path path) throws IOException {
        try (var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            var scene=JSON.fromJson(reader,RVP_GunnerSimulationScenario.class);
            if (scene==null) throw new IllegalArgumentException("场景为空："+path);
            scene.validate(); return scene;
        }
    }
}
