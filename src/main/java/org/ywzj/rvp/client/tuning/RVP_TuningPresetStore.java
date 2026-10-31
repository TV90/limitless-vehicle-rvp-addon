package org.ywzj.rvp.client.tuning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本地客户端预设；只接受当前格式，先完整校验再应用，保存使用临时文件替换。 */
public final class RVP_TuningPresetStore {
    /** 当前预设格式版本；不提供历史键别名或迁移。 */
    private static final int SCHEMA_VERSION = 1;
    /** 单份预设的大小限制，避免误选其它大文件。 */
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    /** 格式化输出器；不依赖 Minecraft 或图形环境。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 当前实例负责的唯一文件路径。 */
    private final Path path;

    public RVP_TuningPresetStore(Path path) { this.path = path; }
    public Path path() { return path; }

    /** 文件不存在时保持业务原值；未知扩展项暂时忽略并返回数量，不会误写其它参数。 */
    public int load(RVP_TuningRegistry registry) throws IOException {
        if (!Files.exists(path)) { return 0; }
        if (Files.size(path) > MAX_FILE_BYTES) { throw new IOException("调参预设超过 1 MiB：" + path); }
        try {
            var parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) { throw new IllegalArgumentException("预设根节点必须为对象"); }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("schema_version") || !root.get("schema_version").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("schema_version").isNumber()
                    || root.get("schema_version").getAsDouble() != SCHEMA_VERSION
                    || !root.has("values") || !root.get("values").isJsonObject()) {
                throw new IllegalArgumentException("只接受当前 schema_version=1 与 values 对象");
            }
            Map<String, Double> values = new LinkedHashMap<>();
            int unknown = 0;
            for (var entry : root.getAsJsonObject("values").entrySet()) {
                if (!registry.options().containsKey(entry.getKey())) { unknown++; continue; }
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("参数必须是数值：" + entry.getKey());
                }
                values.put(entry.getKey(), entry.getValue().getAsDouble());
            }
            // 调用事务式恢复，任何已知参数错误都会保留读取前的全部覆盖。
            registry.restore(values);
            return unknown;
        } catch (RuntimeException failure) {
            throw new IOException("调参预设未应用：" + failure.getMessage(), failure);
        }
    }

    /** 写入完整临时文件后再替换，写盘失败不会破坏上一次成功保存的预设。 */
    public void save(RVP_TuningRegistry registry) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        JsonObject values = new JsonObject();
        // 调用业务覆盖快照，并固定输出顺序，让人工比较预设更方便。
        registry.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.addProperty(entry.getKey(), entry.getValue()));
        root.add("values", values);
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
