package org.ywzj.rvp.entity.projectile;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * Bomb entity with gravity-first motion for {@code rvp:bomb}.
 */
public class RVP_BombEntity extends RVP_BaseBullet {

    /**
     * 客户端插值步数，对齐原版移动包请求的 3 步。
     *
     * <p>基类 {@code AmmoEntity#lerpTo} 把所有弹体固定成 1 步（收到位置包当刻直接瞬移），
     * 对子弹/导弹是精度优先；炸弹飞行时间长、下落慢，客户端刻与网络包相位错开时会
     * 出现「包到才动、没包就停」的一顿一顿。这里恢复原版 3 步平滑，与普通实体一致。</p>
     */
    private static final int CLIENT_LERP_STEPS = 3;
    /**
     * 目标点前瞻刻数 = 步数 − 1：3 步指数平滑在稳态下会滞后 2 刻，
     * 按估计速度把目标点前移同样的量，炸弹模型才不会明显落在真实弹道/爆炸点后面。
     */
    private static final int CLIENT_PREDICT_TICKS = CLIENT_LERP_STEPS - 1;
    /** 位移速度估计的安全上限（格/刻）：超过按传送处理，不做前瞻。 */
    private static final double MAX_PREDICT_SPEED = 32.0D;
    /** 两次位置包间隔超过该刻数视为断流，丢弃速度估计。 */
    private static final long MAX_SAMPLE_INTERVAL_TICKS = 4L;

    private double clientLerpX;
    private double clientLerpY;
    private double clientLerpZ;
    private float clientLerpYRot;
    private float clientLerpXRot;
    private int clientLerpSteps;
    private double clientPredictX;
    private double clientPredictY;
    private double clientPredictZ;
    private double clientSampleX;
    private double clientSampleY;
    private double clientSampleZ;
    private long clientSampleTick;
    private boolean clientHasSample;

    /**
     * 划破空气哨音（客户端专属，服务端永不触碰）。
     * 移植自本体 {@code AerialBombEntity#tickSound} 的 whistle 分支：RVP 炸弹继承
     * {@link RVP_BaseBullet} 而非 {@code AerialBombEntity}，本体那套音效不会生效。
     */
    private VehicleSound soundWhistle;

    /**
     * 临近命中的来袭轰鸣（客户端专属，服务端永不触碰），仅徒步玩家可闻，见 {@link #tickSound()}。
     *
     * <p>注：本体的同位置字段名叫 {@code soundWhistle} 却装 {@code BOMBS_INCOMING}、
     * {@code soundIncoming} 反而装 {@code BOMB_WHISTLE}——字段名是反的。此处按语义正确命名。</p>
     */
    private VehicleSound soundIncoming;

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_BombEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_BombEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_BOMB.get(), level);
    }

    @Override
    public void tick() {
        super.tick();
        // 基类 tick 在客户端分支提前 return，音效与插值需在其后独立驱动
        if (level().isClientSide()) {
            tickClientLerp();
            tickSound();
        }
    }

    /**
     * 客户端位置/姿态插值：接管 {@code AmmoEntity#lerpTo} 的 1 步瞬移语义。
     *
     * <p>不调用 {@code super.lerpTo}，避免基类私有 lerp 状态被同时激活；
     * 服务端不会收到此调用，保留原路径以防未来调用方误用。</p>
     *
     * <p>目标点按最近两次位置包的位移估计做 {@link #CLIENT_PREDICT_TICKS} 刻前瞻，
     * 抵消指数平滑的稳态滞后；传送包/断流/异常速度不做前瞻。</p>
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (!level().isClientSide()) {
            super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
            return;
        }
        updateClientPredict(x, y, z, teleport);
        this.clientLerpX = x + clientPredictX * CLIENT_PREDICT_TICKS;
        this.clientLerpY = y + clientPredictY * CLIENT_PREDICT_TICKS;
        this.clientLerpZ = z + clientPredictZ * CLIENT_PREDICT_TICKS;
        this.clientLerpYRot = yRot;
        this.clientLerpXRot = xRot;
        this.clientLerpSteps = CLIENT_LERP_STEPS;
    }

    /** 用相邻位置包的时间差估计每刻位移；同一客户端刻内的多个包共用同一样本基线。 */
    private void updateClientPredict(double x, double y, double z, boolean teleport) {
        long now = level().getGameTime();
        if (teleport) {
            clientHasSample = false;
            clientPredictX = 0.0D;
            clientPredictY = 0.0D;
            clientPredictZ = 0.0D;
        } else if (clientHasSample) {
            long elapsed = now - clientSampleTick;
            if (elapsed >= 1L && elapsed <= MAX_SAMPLE_INTERVAL_TICKS) {
                clientPredictX = (x - clientSampleX) / elapsed;
                clientPredictY = (y - clientSampleY) / elapsed;
                clientPredictZ = (z - clientSampleZ) / elapsed;
                clientSampleX = x;
                clientSampleY = y;
                clientSampleZ = z;
                clientSampleTick = now;
            } else if (elapsed > MAX_SAMPLE_INTERVAL_TICKS) {
                clientPredictX = 0.0D;
                clientPredictY = 0.0D;
                clientPredictZ = 0.0D;
                clientSampleX = x;
                clientSampleY = y;
                clientSampleZ = z;
                clientSampleTick = now;
            }
        } else {
            clientHasSample = true;
            clientSampleX = x;
            clientSampleY = y;
            clientSampleZ = z;
            clientSampleTick = now;
        }
        double speedSq = clientPredictX * clientPredictX
                + clientPredictY * clientPredictY
                + clientPredictZ * clientPredictZ;
        if (speedSq > MAX_PREDICT_SPEED * MAX_PREDICT_SPEED) {
            clientPredictX = 0.0D;
            clientPredictY = 0.0D;
            clientPredictZ = 0.0D;
        }
    }

    private void tickClientLerp() {
        if (clientLerpSteps <= 0) {
            return;
        }
        double nextX = getX() + (clientLerpX - getX()) / clientLerpSteps;
        double nextY = getY() + (clientLerpY - getY()) / clientLerpSteps;
        double nextZ = getZ() + (clientLerpZ - getZ()) / clientLerpSteps;
        float nextYRot = getYRot() + (float) Mth.wrapDegrees(clientLerpYRot - getYRot()) / clientLerpSteps;
        float nextXRot = getXRot() + (clientLerpXRot - getXRot()) / clientLerpSteps;
        --clientLerpSteps;
        setPos(nextX, nextY, nextZ);
        setRot(nextYRot, nextXRot);
    }

    /**
     * 炸弹飞行音效（移植自本体 {@code AerialBombEntity#tickSound}，两个声部）：
     * <ul>
     *   <li>{@code bomb_whistle}——本地玩家 32 格内播放一次划破空气的哨音（渐入渐出各 50 tick），
     *       无附加条件，投弹者与旁观者都听得见。</li>
     *   <li>{@code bombs_incoming}——8 格内<b>且玩家不在载具上</b>时播放一次临近命中的来袭轰鸣，
     *       条件与本体一致：只作用于地面上将被命中的<b>徒步目标</b>，投弹者自己在座舱里不会响。</li>
     * </ul>
     * 落地时全部停止；弹药被移除时由 {@code VehicleSound#updateRelativePos} 自行停止。
     * 与本体一致每个声部只播放一次（字段非空后不再重放），避免多枚炸弹并发时反复触发。
     */
    @OnlyIn(Dist.CLIENT)
    public void tickSound() {
        if (onGround()) {
            if (soundWhistle != null) {
                soundWhistle.stop();
            }
            if (soundIncoming != null) {
                soundIncoming.stop();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }
        double distance = player.distanceTo(this);
        // 来袭轰鸣：仅步兵（不在载具上）——投弹者自身不响
        if (soundIncoming == null && distance < 8 && !(player.getVehicle() instanceof AbstractVehicle)) {
            soundIncoming = new VehicleSound(RVP_Sounds.BOMBS_INCOMING.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
            soundIncoming.play();
        }
        // 划破空气哨音：任何人 32 格内都听得到
        if (soundWhistle == null && distance < 32) {
            soundWhistle = new VehicleSound(RVP_Sounds.BOMB_WHISTLE.get(), 1f, 2f, 1f, false, 50, true, true, this.getId());
            soundWhistle.play();
        }
    }

    @Override
    protected void tickMotion() {
        // 仅简化弹道且未配置 gravity 时补默认重力；推进模式在 tickPropulsionMotion 内处理，避免重复叠加 G
        if (rvpData != null && !rvpData.usesPropulsion() && rvpData.getGravity() == 0f) {
            setDeltaMovement(getDeltaMovement().add(0, -PhysicsEngine.G, 0));
        }
        super.tickMotion();
    }
}
