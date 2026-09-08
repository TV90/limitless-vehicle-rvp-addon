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
    }
}
