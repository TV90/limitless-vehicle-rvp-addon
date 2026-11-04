package org.ywzj.rvp.client.bridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_ParticleProjectileEmitter;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.firesupport.RVP_FireSupportMapTool;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.client.state.RVP_ClientFocusLock;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.ParticleUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;

/** 客户端真实动作实现。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ClientActions implements RVP_IClientActions {

    @Override
    public net.minecraft.world.entity.Entity findInfraredTarget(
            org.ywzj.vehicle.vehicle.part.WeaponUnit station, float halfAngle) {
        // 调用含真实实体与远距代理的统一红外搜索。
        return org.ywzj.rvp.client.state.RVP_ClientWeaponTargets.findInfraredTarget(station, halfAngle);
    }

    @Override
    public boolean tryManualRemoteVehicleLock(org.ywzj.vehicle.vehicle.part.WeaponUnit station) {
        // 调用远距光电补充选取，保留本体已有 R 键解除锁定语义。
        return org.ywzj.rvp.client.state.RVP_ClientWeaponTargets.tryManualRemoteLock(station);
    }

    @Override
    public void refreshWeaponTarget(org.ywzj.vehicle.vehicle.part.WeaponUnit station) {
        // 调用按实体 ID 的表示交接，不重新开始导引头驻留计时。
        org.ywzj.rvp.client.state.RVP_ClientWeaponTargets.refreshStationLock(station);
    }

    @Override
    public boolean isRemoteVehicleTargetProxy(net.minecraft.world.entity.Entity target) {
        // 调用远距状态表严格确认代理身份。
        return org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState.isTargetProxy(target);
    }

    @Override
    public double remoteVehicleTargetHeight(net.minecraft.world.entity.Entity target) {
        // 调用已通过维度、有效期与对象身份检查的服务端离地高度。
        return org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState.targetHeightAboveGround(target);
    }

    @Override
    public void onFocusLockChanged(org.ywzj.vehicle.vehicle.part.WeaponUnit station) {
        // 调用焦点服务，在本体 R 键分流完成后重查带命中语义的视线。
        RVP_ClientFocusLock.onFocusLockChanged(station);
    }

    @Override
    public void beforeVehicleViewChange(boolean toScope, boolean toOperator) {
        // 调用飞机观瞄退出复位，恢复当前座位默认前方，而不是继续沿旧吊舱瞄准点。
        org.ywzj.rvp.client.state.RVP_ClientAircraftViewReset.beforeViewChange(toScope, toOperator);
    }

    /** 保留已有电视导弹输入语义，再把普通焦点拖动交给独立客户端状态。 */
    @Override
    public boolean handleVehiclePlayerTurn(double yawInput, double pitchInput) {
        // 调用默认关闭的只读诊断入口，记录本体收到的鼠标输入；不消费或修改输入。
        long diagnosticInput = org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.beforeInput(yawInput, pitchInput);
        // 调用 HITL 会话判断，电视导弹操控优先于母车观瞄焦点。
        if (!RVP_ClientHitlState.isActive()) {
            // 调用焦点输入服务；未建立焦点时继续本体及已有半自动火控输入。
            boolean handled = RVP_ClientFocusLock.tryHandleTurn(yawInput, pitchInput);
            String route = handled ? "focus_handled" : "base_pending";
            if (!handled && org.ywzj.rvp.client.state.RVP_ClientOpticalSight.tryHandleTurn(yawInput, pitchInput)) {
                // 调用普通吊舱屏幕轴输入，过竖直方向后不再直接把鼠标横移加到机械偏航。
                handled = true;
                route = "optical_view_handled";
            }
            // 调用诊断分流记录；未处理时标为等待本体，实际结果由后续相机帧/逻辑刻留证。
            org.ywzj.rvp.client.debug.RVP_OpticalSightDebug.afterInput(diagnosticInput, route);
            return handled;
        }
        // 调用焦点会话清理，避免离开电视导弹时复用母车旧渲染原点。
        RVP_ClientFocusLock.clear();
        if (RVP_ClientHitlState.isMouseSteering()) {
            // 调用原有电视导弹转向，保持输入尺度及网络节奏。
            RVP_ClientHitlState.applySteeringDelta(yawInput, pitchInput);
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (RVP_ClientHitlState.isDesignateMode() && minecraft.level != null) {
            // 调用既有统一显示入口，保留真实/虚拟导弹指定视角的相同解析方式。
            RVP_MissileEntity missile = RVP_ClientHitlState.resolveActiveMissile(minecraft);
            if (missile != null) {
                // 调用原有指定视角偏移，继续使用弹体配置的最大观察角度。
                RVP_ClientHitlState.applyLookOffsetDelta(yawInput, pitchInput,
                        missile.rvp$getHitlMaxLookOffsetDeg());
                return true;
            }
        }
        return false;
    }

    @Override
    public void acceptVirtualMissileTargets(org.ywzj.rvp.network.S2CVirtualMissileTargets message) {
        // 调用客户端目标缓存，建立不加入 ClientLevel 的类型化导弹表示。
        org.ywzj.rvp.client.state.RVP_ClientVirtualMissileState.accept(message);
    }

    @Override
    public net.minecraft.world.entity.Entity resolveRemoteRadarTarget(int entityId) {
        // 调用现有本地/远程目标查找，使外置雷达锁定与虚拟目标使用同一套客户端身份。
        return org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper.resolveClientEntity(entityId);
    }

    @Override
    public void acceptVehicleSound(org.ywzj.rvp.network.S2CVehicleSound message) {
        // 调用本项目兜底声音播放器，未加载载具实体时也能按世界坐标播放。
        org.ywzj.rvp.client.RVP_ClientVehicleSound.accept(message);
    }

    @Override
    public void acceptSmallCaliberSound(org.ywzj.rvp.network.S2CSmallCaliberSound message) {
        // 调用本项目客户端播放器，播放服务端确认的小口径声音。
        org.ywzj.rvp.client.RVP_ClientSmallCaliberSound.accept(message);
    }

    @Override
    public void acceptVehicleImpactSparks(org.ywzj.rvp.network.S2CVehicleImpactSparks message) {
        // 调用客户端发射器，结合来弹方向与装甲表面生成下坠火星。
        org.ywzj.rvp.client.visual.RVP_VehicleImpactSparkEffects.accept(message);
    }

    @Override
    public void acceptWreckDestructionState(org.ywzj.rvp.network.S2CWreckDestructionState message) {
        // 调用客户端权威快照缓存，供殉燃与长程烟读取同一服务端时间表。
        org.ywzj.rvp.client.visual.cookoff.RVP_ClientWreckDestructionState.accept(message);
    }

    @Override
    public void tickParticleProjectile(RVP_BaseBullet projectile) {
        // 调用本项目粒子弹体发射器：按 Tick 生成主体及历史路径采样点。
        RVP_ParticleProjectileEmitter.tick(projectile);
    }

    @Override
    public void preserveDestroyedVehicleWaterBubbles(AbstractVehicle vehicle) {
        // 目的：本体 AbstractVehicle.tickParticle 原先在击毁烟前生成水面气泡；取消该方法后按原条件补回。
        if (vehicle.getMainCubeOBB() != null
                && vehicle.tickCount % 5 == 0
                && vehicle.getDeltaMovement().length() > 0.01f) {
            // 调用本体粒子工具，仅恢复水面气泡，不再生成本体击毁烟。
            ParticleUtil.spawnWaterSurfaceBubbles(vehicle.level(), vehicle.level().random, vehicle.getMainCubeOBB());
        }
    }

    @Override
    public boolean shouldSuppressDestroyedVehicleBurning(AbstractVehicle vehicle) {
        // 调用本项目殉燃控制器：仅地面载具且权威快照仍在燃烧时替换本体 FIRE_AMBIENT。
        return org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffController
                .shouldSuppressBaseBurningSound(vehicle);
    }

    @Override
    public void openFireSupportTerminal() {
        // 调用本项目战术地图 Screen：保留普通地图画布并安装独立炮火工具，不进入载具 ARTILLERY 链路。
        Minecraft.getInstance().setScreen(new RVP_TacticalMapScreen(
                RVP_TacticalMapScreen.MapMode.TACTICAL, new RVP_FireSupportMapTool()));
    }

    @Override
    public boolean isFireSupportProfileAvailable(ResourceLocation profileId) {
        // 调用本项目客户端 profile 状态：reload 删除 profile 后让已有变体显示失效配置。
        return RVP_ClientFireSupportState.INSTANCE.isProfileAvailable(profileId);
    }

    /**
     * 生成可指定渲染尺寸倍率的粒子。
     *
     * <p>实现依据（已核对 1.20.1 源码）：</p>
     * <ul>
     *   <li>{@code LevelRenderer.addParticleInternal} 在 {@code force=true} 时**直接**
     *       调 {@code particleEngine.createParticle(...)}（跳过距离裁剪），故本方法与此前
     *       {@code level().addParticle(options, true, ...)} 路径等价；</li>
     *   <li>{@code ParticleEngine.createParticle(...)} 为 public 且返回实例，其内部已把粒子
     *       加入待 Tick 队列，因此在同一次调用后立刻缩放，顺序安全（队列在后续 tick 才生效）；</li>
     *   <li>{@code SingleQuadParticle} 覆盖了 {@code Particle#scale(float)}，执行
     *       {@code quadSize *= pScale}（quadSize 即单面粒子的渲染尺寸）；</li>
     *   <li>{@code CampfireSmokeParticle.tick()} 不重设 quadSize，故单次缩放即可保持到粒子消亡。</li>
     * </ul>
     */
    @Override
    public void addScaledParticle(ParticleOptions options, double x, double y, double z, float scale) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(options, x, y, z, 0.0D, 0.0D, 0.0D);
        // scale <= 0 或 == 1：保持原尺寸（createParticle 已完成与 addParticle(force=true) 等价的生成）
        if (particle != null && scale > 0f && scale != 1f) {
            particle.scale(scale);
        }
    }

    /**
     * 生成 MCHR 风格翻滚烟团作为尾迹粒子。
     *
     * <p>不走 {@code ParticleEngine.createParticle}（那需要已注册的 {@code ParticleType} + provider，
     * 且无法携带尺寸），而是复用视觉工厂的写法：直接静态构造自定义粒子实例（尺寸烘进构造），
     * 再 {@code engine.add(...)}——与 {@code RVP_DefaultExplosionEffectFactory} 生成爆炸烟同一条路径。</p>
     */
    @Override
    public void addTrailSmokeParticle(double x, double y, double z, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                RVP_MchrSmokeParticle.ofTrailScaled(level, x, y, z, sizeScale));
    }

    /** 生成 HBM 风格火箭尾焰粒子（TRAIL 模式，先火后烟膨胀柱，参数见粒子类移植注释；
     *  holdTicks 为距发动机燃尽的 tick 数，凝结云保持期据此绑定燃烧期）。 */
    @Override
    public void addRocketFlameTrailParticle(double x, double y, double z,
                                            double mx, double my, double mz, float sizeScale,
                                            int holdTicks, int holdMinTicks, int fadeTicks,
                                            int fadeRandomTicks, float alphaHoldRatio) {
        // 调用代码总开关停用旧贴图尾迹；下面的原始构造和寿命实现完整保留。
        if (!org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters.originalParticlesEnabled()) { return; }
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofTrail(
                        level, x, y, z, mx, my, mz, sizeScale, holdTicks,
                        holdMinTicks, fadeTicks, fadeRandomTicks, alphaHoldRatio));
    }

    /** 生成 HBM 风格液氧煤油黑烟尾焰粒子（技术储备款，TRAIL 模式，09-19 前原始黑烟观感）。 */
    @Override
    public void addKeroseneBlackSmokeTrailParticle(double x, double y, double z,
                                                   double mx, double my, double mz, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofKeroseneBlackSmokeTrail(
                        level, x, y, z, mx, my, mz, sizeScale));
    }

    /** 生成 HBM 风格发射地面烟浪粒子（WASH 模式，初速内部随机径向冲刷 + 浮升）。 */
    @Override
    public void addLaunchWashParticle(double x, double y, double z, float sizeScale) {
        // rvp_rocket_flame 已在公共弹体处分流；其它显式配置旧烟浪的风格继续沿用原实现。
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofLaunchWash(
                        level, x, y, z, sizeScale));
    }

    /** 接收公共弹体的世界尾迹采样，真实配置解析和烟段管理都留在纯客户端。 */
    @Override
    public void addRocketVolumeTrail(RVP_BaseBullet projectile, net.minecraft.world.phys.Vec3 position,
            net.minecraft.world.phys.Vec3 exhaust, float scale, int remainingBurnTicks) {
        // 调用弹体已有的类型化配置出口，不按武器 ID 或模型名称判断尾烟。
        var config = projectile.getResolvedWeaponConfig();
        if (config == null) { return; }
        // 调用新世界烟段入口，保持近距和远距使用相同的配置和寿命规则。
        org.ywzj.rvp.client.light.RVP_RocketTrailEmitter.emit(projectile, config.getEffectsData(),
                position, exhaust, scale, remainingBurnTicks, false);
    }

    /** 接收原冷发射和贴地阶段的烟浪请求，改为程序体积烟。 */
    @Override
    public void addRocketVolumeWash(RVP_BaseBullet projectile, net.minecraft.world.phys.Vec3 position, float scale) {
        // 调用独立烟浪入口，保留已有生成条件而替换显示方式。
        org.ywzj.rvp.client.light.RVP_RocketTrailEmitter.wash(projectile, position, scale);
    }

    /**
     * 生成一枚热诱弹（flare）拖尾烟粒子：直接构造本项目静态贴图烟并加入客户端引擎。
     *
     * <p>不走 {@code ParticleEngine.createParticle}（那需要已注册的 particle type + provider），
     * 与殉燃火柱/炮口烟同一路径；贴图集与渲染批次由 {@code RVP_WreckCookoffParticleTextures}
     * 统一提供，热诱弹拖尾因此复用 {@code textures/wreck_cookoff/particle/} 下的烟雾。</p>
     */
    @Override
    public void addFlareTrailSmokeParticle(double x, double y, double z, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_FlareTrailSmokeParticle.of(
                        level, x, y, z, sizeScale));
    }

    /** HITL 粒子屏蔽查询：委托 RVP_ClientHitlState（激活导弹半径内返回 true）。 */
    @Override
    public boolean shouldSuppressTrailParticleNearHitlMissile(double x, double y, double z) {
        return org.ywzj.rvp.client.state.RVP_ClientHitlState.shouldSuppressParticleNearActiveMissile(x, y, z);
    }

    /** 本地操作者是否处于 SCOPE（开镜）视角：读取本体本地玩家状态并比对视角类型。 */
    @Override
    public boolean isLocalOperatorInScopeView() {
        // 调用本体客户端状态类，读取本地玩家是否正在操作载具及其视角类型。
        LocalVehiclePlayer player = LocalVehiclePlayer.instance;
        return player != null
                && player.onVehicle()
                && player.viewType == LocalVehiclePlayer.ViewType.SCOPE;
    }
}
