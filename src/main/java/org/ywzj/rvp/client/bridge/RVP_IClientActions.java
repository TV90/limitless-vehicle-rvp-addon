package org.ywzj.rvp.client.bridge;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 公共弹体可安全调用的客户端动作接口；服务端实现不引用任何纯客户端类型。 */
public interface RVP_IClientActions {

    /** 按客户端实体 Tick 更新纯粒子弹体表现。 */
    void tickParticleProjectile(RVP_BaseBullet projectile);

    /** 请求客户端打开炮火支援终端上下文；阶段 D 安装实际地图工具前允许安全无操作。 */
    void openFireSupportTerminal();

    /** 查询客户端已同步的 profile 是否仍可用；同步完成前返回 true，避免旧物品短暂闪烁失效。 */
    boolean isFireSupportProfileAvailable(ResourceLocation profileId);
}
