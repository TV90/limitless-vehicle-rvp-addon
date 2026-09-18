package org.ywzj.rvp.sight;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家观瞄视角（SCOPE）状态侧表（服务端）。
 *
 * <p>客户端进入/退出观瞄或换操作站时上行 {@code C2SScopeViewSync}（观瞄中每 30t 心跳续期），
 * 服务端按玩家 UUID 记录「载具 + 武器站 + 有效期」。出弹覆盖
 * （{@code RVP_ProjectileSpawner}）按此表判定「该玩家当前是否处于本站观瞄视角」——
 * 本体没有任何观瞄状态同步，这是唯一上行通道；条目过期自动失效，
 * 出弹时查不到即回退普通炮口出弹（优雅降级，不阻塞开火）。</p>
 */
public final class RVP_ScopeViewStateTable {

    /** 一条观瞄状态：玩家在指定载具的指定武器站上处于观瞄视角，有效期至 expireGameTime。 */
    public record ScopeState(int vehicleId, int partUnitIndex, long expireGameTime) {
    }

    /** 状态有效期（tick）：覆盖心跳间隔（30t）与点射/连发的后续弹出膛窗口。 */
    private static final long EXPIRE_TICKS = 90L;

    private static final Map<UUID, ScopeState> STATES = new ConcurrentHashMap<>();

    private RVP_ScopeViewStateTable() {
    }

    /** 上行入口：inScope=false 或换车/换站即覆盖/清除旧状态。 */
    public static void update(ServerPlayer player, int vehicleId, int partUnitIndex, boolean inScope,
                              long gameTime) {
        if (!inScope || partUnitIndex < 0) {
            STATES.remove(player.getUUID());
            return;
        }
        STATES.put(player.getUUID(), new ScopeState(vehicleId, partUnitIndex, gameTime + EXPIRE_TICKS));
    }

    /** 查询玩家的有效观瞄状态；缺失或过期返回 null（过期条目顺带清理）。 */
    public static ScopeState get(ServerPlayer player, long gameTime) {
        ScopeState state = STATES.get(player.getUUID());
        if (state == null) {
            return null;
        }
        if (state.expireGameTime() < gameTime) {
            STATES.remove(player.getUUID());
            return null;
        }
        return state;
    }

    public static void clear(UUID playerId) {
        STATES.remove(playerId);
    }
}
