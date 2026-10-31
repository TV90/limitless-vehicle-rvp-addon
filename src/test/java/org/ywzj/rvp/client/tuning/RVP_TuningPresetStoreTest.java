package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 JSON 文件往返、当前格式限定与无损失败，不读写用户的游戏配置。 */
class RVP_TuningPresetStoreTest {
    /** JUnit 管理的独立测试目录，不接触载具包和实际游戏目录。 */
    @TempDir Path temporaryDirectory;

    @Test
    void fileRoundTripRestoresOnlyExplicitOverridesAndWritesStableCurrentSchema() throws IOException {
        var binding = new RVP_TuningRegistryTest.Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(RVP_TuningRegistryTest.module("lighting", "光照", binding.option("brightness")));
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("nested/preset.json"));
        binding.override = OptionalDouble.of(12.5);
        store.save(registry);
        String saved = Files.readString(store.path());
        assertTrue(saved.contains("\"schema_version\": 1"));
        binding.override = OptionalDouble.of(3);
        assertEquals(0, store.load(registry));
        assertEquals(12.5, binding.override.orElseThrow());
        registry.restore(Map.of());
        store.save(registry);
        binding.override = OptionalDouble.of(20);
        store.load(registry);
        assertTrue(binding.override.isEmpty());
        try (var files = Files.list(store.path().getParent())) { assertEquals(1, files.count()); }
    }

    @Test
    void malformedOutOfRangeAndWrongVersionFilesKeepAllExistingValues() throws IOException {
        var binding = new RVP_TuningRegistryTest.Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(RVP_TuningRegistryTest.module("lighting", "光照", binding.option("brightness")));
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("preset.json"));
        binding.override = OptionalDouble.of(17);
        for (String invalid : new String[] {"{", "[]", "{\"schema_version\":2,\"values\":{}}",
                "{\"schema_version\":1.1,\"values\":{}}", "{\"schema_version\":\"1\",\"values\":{}}",
                "{\"schema_version\":1,\"values\":{\"lighting/global/brightness\":-1}}",
                "{\"schema_version\":1,\"values\":{\"lighting/global/brightness\":\"5\"}}",
                "{\"schema_version\":1,\"values\":{\"lighting/global/brightness\":1e999}}"}) {
            Files.writeString(store.path(), invalid);
            assertThrows(IOException.class, () -> store.load(registry), invalid);
            assertEquals(17, binding.override.orElseThrow(), invalid);
        }
    }

    @Test
    void missingFileDoesNotResetSessionAndUnknownExtensionKeysAreReported() throws IOException {
        var binding = new RVP_TuningRegistryTest.Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(RVP_TuningRegistryTest.module("lighting", "光照", binding.option("brightness")));
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("preset.json"));
        binding.override = OptionalDouble.of(9);
        assertEquals(0, store.load(registry));
        assertEquals(9, binding.override.orElseThrow());
        Files.writeString(store.path(), "{\"schema_version\":1,\"values\":{\"lighting/global/brightness\":5,\"future/group/value\":7}}");
        assertEquals(1, store.load(registry));
        assertEquals(5, binding.override.orElseThrow());
    }

    @Test
    void saveFailureLeavesThePreviousPresetIntact() throws IOException {
        var binding = new RVP_TuningRegistryTest.Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(RVP_TuningRegistryTest.module("lighting", "光照", binding.option("brightness")));
        Path parent = temporaryDirectory.resolve("occupied-file");
        Files.writeString(parent, "原来的文件内容");
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(parent.resolve("preset.json"));
        assertThrows(IOException.class, () -> store.save(registry));
        assertEquals("原来的文件内容", Files.readString(parent));
    }
}
