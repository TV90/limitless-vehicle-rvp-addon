package org.ywzj.rvp.client.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.visual.RVP_FiringMuzzleResolver;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/** 物理客户端光源事件；公共事件订阅器和网络协议不引用本类。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientLightEvents {
    /** 本逻辑 tick 见到的本地发动机弹体；逐帧只插值这些弹体，不再次遍历世界或按粒子更新。 */
    private static final java.util.List<org.ywzj.rvp.entity.projectile.RVP_BaseBullet> LOCAL_ENGINES = new java.util.ArrayList<>();
    private RVP_ClientLightEvents() { }

    /** 每个显示帧独立清空借用深度；暂停时不能靠游戏 tick 判断新旧。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 调用帧起点，DH 的早期渲染事件也能归属于本帧，不能等到天气阶段才重置。
            RVP_ScreenLightPass.beginFrame();
        }
    }

    /** 在所有 NORMAL 优先级的 CRT/TV/过载后处理前合成，HUD 仍未绘制。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            for (org.ywzj.rvp.entity.projectile.RVP_BaseBullet bullet : LOCAL_ENGINES) {
                // 调用和实体渲染一致的部分 tick 插值，快速飞行时环境光不能超前整整一 tick。
                if (bullet.level() == Minecraft.getInstance().level) {
                    RVP_RocketEngineLightQuery.update(bullet, bullet.getPosition(event.getPartialTick()), event.getPartialTick());
                }
            }
        }
        // 调用场景输入捕获与末端合成；两入口自行筛选天气后/世界结束阶段。
        RVP_ScreenLightPass.capture(event);
        RVP_ScreenLightPass.render(event);
    }

    @SubscribeEvent
    public static void onFire(VehicleFireEvent.Post event) {
        // 本体成功开火包会对追踪者重新发布此事件，本地与远端射击均可复用。
        if (!event.isClientSide() || !(event.getVehicle().level() instanceof ClientLevel level)) { return; }
        // 调用世界身份检查，防止新世界首个开火发生在下一客户端 tick 之前。
        RVP_ScreenLightManager manager = RVP_ScreenLightManager.INSTANCE;
        manager.beginWorld(level, level.getGameTime());
        // 调用统一实际武器和参数解析，齐射、代理与口径规则与炮口烟共用。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        RVP_LightParameters parameters = RVP_FiringMuzzleResolver.lightParameters(weapon);
        if (weapon == null || parameters == null) { return; }
        for (RVP_FiringMuzzleResolver.MuzzleContext muzzle :
                RVP_FiringMuzzleResolver.resolveFiringMuzzleContexts(weapon.getWeaponUnit())) {
            // 调用短光合并；相同载具/武器站/炮口的高射速武器不逐发扩张 Shader 循环。
            manager.addTransient(muzzle.position(), RVP_LightSourceType.MUZZLE,
                    event.getVehicle().getUUID() + ":" + weapon.getWeaponUnit().getIndex() + ":" + muzzle.muzzleIndex(),
                    parameters, level.getGameTime());
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().isPaused()) { return; }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            LOCAL_ENGINES.clear();
            // 调用统一清理，登出后没有灯或旧显存资源残留。
            RVP_ScreenLightManager.INSTANCE.clear();
            RVP_RocketPlumeManager.INSTANCE.clear();
            RVP_ScreenLightPass.clear();
            return;
        }
        // 调用生命周期清理，先淘汰过期条目，再为仍燃烧的本地实体续期。
        RVP_ScreenLightManager.INSTANCE.tick(level, level.getGameTime());
        // 调用独立喷焰时钟，清理远端失去心跳的喷流；暂停时与模型一起冻结。
        RVP_RocketPlumeManager.INSTANCE.beginWorld(level, level.getGameTime());
        LOCAL_ENGINES.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof org.ywzj.rvp.entity.projectile.RVP_BaseBullet bullet) {
                // 调用类型化推进配置，只缓存有发动机的弹体；机枪弹数量不能变成逐帧灯光查询量。
                org.ywzj.rvp.weapon.data.RVP_WeaponData config = bullet.getResolvedWeaponConfig();
                if (config != null && config.getProjectileData().hasRocketEngine()) { LOCAL_ENGINES.add(bullet); }
            }
            // 调用按实体的燃烧/喷口查询，与是否启用尾烟、粒子数量无关。
            RVP_RocketEngineLightQuery.update(entity, entity.position(), 1);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            LOCAL_ENGINES.remove(event.getEntity());
            // 调用 UUID 删除入口，销毁、区块卸载不等到两 tick 心跳超时。
            RVP_ScreenLightManager.INSTANCE.removePersistent(event.getEntity().getUUID());
            RVP_RocketPlumeManager.INSTANCE.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            LOCAL_ENGINES.clear();
            // 调用两个独立生命周期，CPU 光表和 GPU 画面副本均在换维度时丢弃。
            RVP_ScreenLightManager.INSTANCE.clear();
            RVP_RocketPlumeManager.INSTANCE.clear();
            RVP_ScreenLightPass.clear();
        }
    }
}
