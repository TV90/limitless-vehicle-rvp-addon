package org.ywzj.rvp.client.bridge;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.DistExecutor;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 双端安全的客户端动作入口；专用服务端始终使用 NOOP 实现。 */
public final class RVP_ClientActionsAccess {

    /** 按物理端选择的动作实现，服务端对象不加载客户端粒子类。 */
    private static final RVP_IClientActions INSTANCE = DistExecutor.safeRunForDist(
            () -> RVP_ClientActions::new,
            () -> NoopClientActions::new);

    private RVP_ClientActionsAccess() {}

    public static void tickParticleProjectile(RVP_BaseBullet projectile) {
        INSTANCE.tickParticleProjectile(projectile);
    }

    /** 经物理侧桥请求打开终端，公共物品类不会加载客户端 Screen。 */
    public static void openFireSupportTerminal() {
        INSTANCE.openFireSupportTerminal();
    }

    /** 经双端安全桥查询 profile 生命周期，公共物品类不直接引用客户端状态。 */
    public static boolean isFireSupportProfileAvailable(ResourceLocation profileId) {
        return INSTANCE.isFireSupportProfileAvailable(profileId);
    }

    /**
     * 经双端安全桥生成一个"可指定渲染尺寸"的粒子（尾迹用）。
     * 服务端为 NOOP——粒子是纯客户端表现，公共弹体类不引用客户端粒子类。
     */
    public static void addScaledParticle(net.minecraft.core.particles.ParticleOptions options,
                                         double x, double y, double z, float scale) {
        INSTANCE.addScaledParticle(options, x, y, z, scale);
    }

    /**
     * 经双端安全桥生成 MCHR 风格尾迹烟团（尺寸倍率可配）。
     * 服务端为 NOOP——粒子是纯客户端表现。
     */
    public static void addTrailSmokeParticle(double x, double y, double z, float sizeScale) {
        INSTANCE.addTrailSmokeParticle(x, y, z, sizeScale);
    }

    /**
     * 经双端安全桥生成 HBM 风格火箭尾焰尾迹粒子（先火后烟膨胀柱）。
     * 服务端为 NOOP——粒子是纯客户端表现。
     */
    public static void addRocketFlameTrailParticle(double x, double y, double z,
                                                   double mx, double my, double mz, float sizeScale) {
        INSTANCE.addRocketFlameTrailParticle(x, y, z, mx, my, mz, sizeScale);
    }

    /**
     * 经双端安全桥生成 HBM 风格发射地面烟浪粒子（贴地横向冲刷灰烟）。
     * 服务端为 NOOP——粒子是纯客户端表现。
     */
    public static void addLaunchWashParticle(double x, double y, double z, float sizeScale) {
        INSTANCE.addLaunchWashParticle(x, y, z, sizeScale);
    }

    /** 专用服务端空实现。 */
    private static final class NoopClientActions implements RVP_IClientActions {
        @Override
        public void tickParticleProjectile(RVP_BaseBullet projectile) {
            // 服务端不生成客户端粒子。
        }

        @Override
        public void openFireSupportTerminal() {
            // 专用服务端没有界面；阶段 D 的真实客户端实现不会进入此分支。
        }

        @Override
        public boolean isFireSupportProfileAvailable(ResourceLocation profileId) {
            // 服务端不负责客户端显示；公共物品名称在服务端不应被标成失效。
            return true;
        }

        @Override
        public void addScaledParticle(net.minecraft.core.particles.ParticleOptions options,
                                      double x, double y, double z, float scale) {
            // 服务端无粒子渲染管线，粒子由各客户端自行生成（尾迹只在客户端 tick 中产生）。
        }

        @Override
        public void addTrailSmokeParticle(double x, double y, double z, float sizeScale) {
            // 同上：尾迹烟团是纯客户端表现。
        }

        @Override
        public void addRocketFlameTrailParticle(double x, double y, double z,
                                                double mx, double my, double mz, float sizeScale) {
            // 服务端无粒子渲染管线，火箭尾焰尾迹只在客户端实体 Tick 中产生。
        }

        @Override
        public void addLaunchWashParticle(double x, double y, double z, float sizeScale) {
            // 同上：发射地面烟浪是纯客户端表现。
        }
    }
}
