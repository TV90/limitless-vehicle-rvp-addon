package org.ywzj.rvp.client;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.S2CSmallCaliberSound;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证声音实例直接指向用户已有的每一个 Ogg 音频文件。 */
class RVP_ClientSmallCaliberSoundTest {

    @Test
    void everyIncomingAndImpactSampleResolvesAndExists() {
        for (S2CSmallCaliberSound.Kind kind : S2CSmallCaliberSound.Kind.values()) {
            for (int i = 0; i < kind.variantCount(); i++) {
                // 调用本项目声音工厂并执行原版解析入口，不依赖 sounds.json。
                SoundInstance sound = RVP_ClientSmallCaliberSound.createSound(kind, i, new Vec3(1, 2, 3));
                assertNotNull(sound.resolve(null));
                assertEquals("ywzj_rvp", sound.getSound().getPath().getNamespace());
                assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/ywzj_rvp",
                        sound.getSound().getPath().getPath())));
                assertEquals(kind == S2CSmallCaliberSound.Kind.INCOMING ? 1.0F : 2.0F, sound.getVolume());
            }
        }
    }
}
