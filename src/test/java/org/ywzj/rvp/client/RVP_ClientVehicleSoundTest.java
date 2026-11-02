package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_DisplayFireSound;
import static org.junit.jupiter.api.Assertions.*;

/** 客户端声音实例只使用包声明事件与参数，不再在内存伪造默认采样组。 */
class RVP_ClientVehicleSoundTest {
    private static RVP_DisplayFireSound config(ResourceLocation fire, ResourceLocation far, float split,
                                               float max, float volume, float pitch) {
        var settings=new RVP_DisplayFireSound.Settings(); settings.farDistance=split; settings.maxDistance=max;
        settings.volume=volume; settings.pitch=pitch;
        return RVP_DisplayFireSound.bind(settings,fire,far);
    }
    @Test void nearAndFarInstancesKeepConfiguredEventsVolumePitchAndWorldPosition() throws Exception {
        var near=new ResourceLocation("example","launch_near"); var far=new ResourceLocation("example","launch_far");
        var config=config(near,far,137.5F,950F,2.5F,.85F);
        Vec3 p=new Vec3(13,64,-9);
        for (double distance:new double[]{0,137.5,137.6,900}) {
            var sound=RVP_ClientVehicleSound.createPositionSound(config,distance,p);
            // 原版在 resolve 阶段填充样本；此处提供中性样本，声音资源解析另由真实客户端回归。
            var sample=new net.minecraft.client.resources.sounds.Sound("example:neutral",r->1F,r->1F,1,
                    net.minecraft.client.resources.sounds.Sound.Type.FILE,false,false,16);
            var field=net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("sound");
            field.setAccessible(true); field.set(sound,sample);
            assertEquals(distance>137.5?far:near,sound.getLocation());
            assertEquals(2.5F,sound.getVolume()); assertEquals(.85F,sound.getPitch());
            assertEquals(SoundSource.PLAYERS,sound.getSource()); assertFalse(sound.isLooping());
            assertEquals(p.x,sound.getX()); assertEquals(p.y,sound.getY()); assertEquals(p.z,sound.getZ());
        }
    }
    @Test void singleEventConfigDoesNotInventASecondSoundForDistantListeners() {
        var id=new ResourceLocation("example","one_event");
        var config=config(id,null,0,2048,1,1);
        assertEquals(id,config.soundAt(0)); assertEquals(id,config.soundAt(1000));
    }
}
