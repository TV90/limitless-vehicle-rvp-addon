package org.ywzj.rvp.hitl;

/** 双端共用的远程窗口硬上限；武器与服务端配置仍各自控制实际采用值。 */
public final class RVP_HitlRemoteLimits {
    /** 水平半径硬上限，单位区块；32 对应 65×65=4225 个观察区块。 */
    public static final int MAX_RADIUS_CHUNKS = 32;
    /** 单会话及全服配置可允许的每 tick 新票据中心硬上限。 */
    public static final int MAX_LOAD_CHUNKS_PER_TICK = 32;
    /** 单会话可允许的每 tick 完整区块首发包硬上限。 */
    public static final int MAX_SEND_CHUNKS_PER_TICK = 32;

    private RVP_HitlRemoteLimits() {}
}
