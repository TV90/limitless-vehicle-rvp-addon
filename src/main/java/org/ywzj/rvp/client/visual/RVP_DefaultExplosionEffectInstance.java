package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 维护 MCHR 默认爆炸声音与附加短光；原有粒子仍由工厂独立生成。 */
final class RVP_DefaultExplosionEffectInstance implements RVP_ClientVisualEffect {
    /** 当前实例年龄，创建时已扣除网络传输经过的世界 tick。 */
    private int age;
    /** 当前事件的客户端声音控制器。 */
    private final RVP_DefaultExplosionSoundController soundController;
    /** 管理器返回的短光取消句柄；0 表示关闭或网络迟到到已过期。 */
    private final long lightHandle;
    /** 光源实际结束年龄，声音提前结束也不能提早取消闪光。 */
    private final int lightDuration;

    RVP_DefaultExplosionEffectInstance(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_EnumWeaponKind weaponKind, int initialAge) {
        age = initialAge;
        soundController = new RVP_DefaultExplosionSoundController(level, event, weaponKind, initialAge);
        // 调用代码中的爆炸光参数，仅从现有事件读取爆炸半径，不解析 JSON 光配置或新增闪光事件。
        org.ywzj.rvp.light.RVP_LightParameters parameters =
                org.ywzj.rvp.light.RVP_LightParameters.explosion(event.baseExplosionRadius());
        lightDuration = event.flash() && parameters.enabled() ? parameters.durationTicks() : 0;
        org.ywzj.rvp.client.light.RVP_ScreenLightManager manager = org.ywzj.rvp.client.light.RVP_ScreenLightManager.INSTANCE;
        // 调用世界检查与短光登记，startGameTime 自动扣除网络延迟。
        manager.beginWorld(level, level.getGameTime());
        lightHandle = event.flash() ? manager.addTransient(event.position(),
                org.ywzj.rvp.client.light.RVP_LightSourceType.EXPLOSION, null, parameters,
                event.startGameTime()) : 0;
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
        return soundController.isFinished() && age >= lightDuration;
    }

    @Override
    public void close() {
        // 调用默认爆炸声音控制器，取消因实例淘汰或切换世界而尚未抵达的声音。
        soundController.close();
        // 调用短光取消入口，实例预算淘汰时不会遗留它的闪光。
        org.ywzj.rvp.client.light.RVP_ScreenLightManager.INSTANCE.removeTransient(lightHandle);
    }
}
