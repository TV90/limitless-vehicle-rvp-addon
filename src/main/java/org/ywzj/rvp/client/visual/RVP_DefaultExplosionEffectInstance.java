package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 只维护 MCHR 默认爆炸延迟声音的轻量客户端生命周期实例。 */
final class RVP_DefaultExplosionEffectInstance implements RVP_ClientVisualEffect {
    /** 当前实例年龄，创建时已扣除网络传输经过的世界 tick。 */
    private int age;
    /** 当前事件的客户端声音控制器。 */
    private final RVP_DefaultExplosionSoundController soundController;

    RVP_DefaultExplosionEffectInstance(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_EnumWeaponKind weaponKind, int initialAge) {
        age = initialAge;
        soundController = new RVP_DefaultExplosionSoundController(level, event, weaponKind, initialAge);
    }

    @Override
    public void tick() {
        age++;
        // 调用默认爆炸声音控制器，推进声速延迟后的客户端主爆音。
        soundController.tick(age);
    }

    @Override
    public void render(RenderLevelStageEvent event) {
        // 默认爆炸粒子在工厂创建阶段已提交给粒子引擎，本实例不额外绘制世界几何。
    }

    @Override
    public boolean isFinished() {
        return soundController.isFinished();
    }

    @Override
    public void close() {
        // 调用默认爆炸声音控制器，取消因实例淘汰或切换世界而尚未抵达的声音。
        soundController.close();
    }
}
