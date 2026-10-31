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

    RVP_DefaultExplosionEffectInstance(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_EnumWeaponKind weaponKind, int initialAge) {
        age = initialAge;
        soundController = new RVP_DefaultExplosionSoundController(level, event, weaponKind, initialAge);
        // 调用代码中的爆炸光参数，仅从现有事件读取爆炸半径，不解析 JSON 光配置或新增闪光事件。
        org.ywzj.rvp.light.RVP_LightParameters parameters =
                org.ywzj.rvp.light.RVP_LightParameters.explosion(event.baseExplosionRadius());
        org.ywzj.rvp.client.light.RVP_ScreenLightManager manager = org.ywzj.rvp.client.light.RVP_ScreenLightManager.INSTANCE;
        // 调用世界检查与短光登记，startGameTime 自动扣除网络延迟。
        manager.beginWorld(level, level.getGameTime());
        // 调用唯一的光源判定：只有“允许闪光 + 服务端标记空中起爆”才建灯；
        // 地面/命中起爆保留粒子、烟雾与声音，但不再产生照亮、光晕、白热核心与天空染色。
        boolean emitLight = org.ywzj.rvp.weapon.visual.RVP_DefaultExplosionEventData
                .shouldEmitScreenLight(event.flash(), event.canonicalPresetDataJson());
        lightHandle = emitLight ? manager.addTransient(event.position(),
                org.ywzj.rvp.client.light.RVP_LightSourceType.EXPLOSION, null, parameters,
                event.startGameTime()) : 0;
        // 调用天空散射追踪：未达离地高度门槛不建灯是预期行为，单独记录避免与“光源被剔除”混淆。
        if (!emitLight) {
            org.ywzj.rvp.client.light.RVP_SkyScatterTrace.lightSkipped(event.position(),
                    event.flash()
                            ? "爆心离地不足 " + org.ywzj.rvp.weapon.visual.RVP_DefaultExplosionVisualService
                                    .MIN_AIR_BURST_CLEARANCE + " 格，仅空中起爆生成爆炸光源"
                            : "事件未允许闪光");
        }
        // 调用天空散射追踪，记录光源是否真的登记成功；handle=0 后不会再有任何帧日志。
        org.ywzj.rvp.client.light.RVP_SkyScatterTrace.lightRegistered(event.position(), lightHandle,
                manager.size(),
                org.ywzj.rvp.light.RVP_LightRuntimeTuning.skyIntensity(
                        org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group.explosion, 1),
                initialAge);
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
        // 调用短光实际存活查询，包装器跟随整盏光源的动态寿命，不用旧固定32刻提前结束。
        return soundController.isFinished()
                && !org.ywzj.rvp.client.light.RVP_ScreenLightManager.INSTANCE.hasTransient(lightHandle);
    }

    @Override
    public void close() {
        // 调用默认爆炸声音控制器，取消因实例淘汰或切换世界而尚未抵达的声音。
        soundController.close();
        // 调用天空散射追踪，记录光源是自然结束还是被效果实例预算提前淘汰。
        org.ywzj.rvp.client.light.RVP_SkyScatterTrace.lightClosed(lightHandle,
                soundController.isFinished() ? "效果自然结束" : "效果实例被淘汰或世界切换");
        // 调用短光取消入口，实例预算淘汰时不会遗留它的闪光。
        org.ywzj.rvp.client.light.RVP_ScreenLightManager.INSTANCE.removeTransient(lightHandle);
    }
}
