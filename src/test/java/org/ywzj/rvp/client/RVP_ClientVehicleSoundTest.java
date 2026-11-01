package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.S2CVehicleSound.Kind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证所有新增音频可由原版声音系统解析，且距离与自定义声音优先级正确。 */
class RVP_ClientVehicleSoundTest {

    @Test
    void everyVariantResolvesARealOggWithTheRequestedRange() throws Exception {
        Set<Path> resolved = new HashSet<>();
        Vec3 position = new Vec3(13, 64, -9);
        for (Kind kind : Kind.values()) {
            for (boolean far : new boolean[]{false, true}) {
                int variants = 3;
                for (int variant = 0; variant < variants; variant++) {
                    // 调用实际播放工厂和原版解析入口，逐一确认所有样本路径和衰减距离。
                    var sound = RVP_ClientVehicleSound.createPositionSound(kind, far, variant, position);
                    assertNotNull(sound.resolve(null));
                    Path file = Path.of("src/main/resources/assets/ywzj_rvp", sound.getSound().getPath().getPath());
                    assertTrue(Files.isRegularFile(file), file.toString());
                    assertTrue(Files.size(file) > 0, file.toString());
                    assertEquals(kind.maximumDistance(), sound.getSound().getAttenuationDistance());
                    assertEquals(1.0F, sound.getVolume());
                    assertEquals(SoundSource.PLAYERS, sound.getSource());
                    assertFalse(sound.isLooping());
                    assertEquals(position.x, sound.getX());
                    assertEquals(position.y, sound.getY());
                    assertEquals(position.z, sound.getZ());
                    resolved.add(file);
                }
            }
        }
        // 冷发射 6 个、地面热发射 3 个、机载 3 个，共 12 个文件；殉燃爆响已改为客户端本地跟随播放，不再走消息。
        assertEquals(12, resolved.size());
    }

    @Test
    void customReloadOrSightSoundAlsoBlocksMissileDefaultAndMissingResourcesFallBack() {
        ResourceLocation nativeFire = ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "missile_launch");
        ResourceLocation custom = ResourceLocation.fromNamespaceAndPath("rvp", "custom_reload");
        // 调用火箭、导弹共用策略：任意自定义声音保护整套配置，不仅检查 fire。
        assertTrue(RVP_WeaponFireSoundPolicy.allowsDefaultSound(true, null));
        assertTrue(RVP_WeaponFireSoundPolicy.allowsDefaultSound(true, List.of(nativeFire)));
        assertFalse(RVP_WeaponFireSoundPolicy.allowsDefaultSound(true, List.of(nativeFire, custom)));
        assertFalse(RVP_WeaponFireSoundPolicy.allowsDefaultSound(false, List.of(nativeFire)));
    }
}
