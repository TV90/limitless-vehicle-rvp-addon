package org.ywzj.rvp.client.firesupport;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.network.firesupport.RVP_FireSupportClientEndpoint;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;
import org.ywzj.rvp.network.firesupport.S2CFireSupportProfileSnapshot;
import org.ywzj.rvp.network.firesupport.S2CFireSupportRequestResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 阶段 C 客户端不可变快照；阶段 D 地图工具将只读取这里的权威状态。 */
public final class RVP_ClientFireSupportState implements RVP_FireSupportClientEndpoint.Listener {
    /** 单例监听器，由客户端 bootstrap 安装。 */ public static final RVP_ClientFireSupportState INSTANCE = new RVP_ClientFireSupportState();
    /** 当前服务端 profile revision。 */ private volatile long revision;
    /** 规范化 profile JSON；String 与 Map 均不可变，阶段 D 读取时再解析。 */ private volatile Map<ResourceLocation, String> profiles = Map.of();
    /** 最近一次请求结果。 */ private volatile S2CFireSupportRequestResult lastResult;
    /** 当前玩家可见的任务状态。 */ private final Map<UUID, S2CFireSupportMissionUpdate> missions = new LinkedHashMap<>();

    private RVP_ClientFireSupportState() {}

    @Override
    public void onProfiles(S2CFireSupportProfileSnapshot message) {
        profiles = Map.copyOf(message.profiles());
        revision = message.revision();
    }

    @Override
    public void onRequestResult(S2CFireSupportRequestResult message) {
        lastResult = message;
    }

    @Override
    public synchronized void onMissionUpdate(S2CFireSupportMissionUpdate message) {
        missions.put(message.missionId(), message);
    }

    /** @return 当前权威 revision。 */ public long revision() { return revision; }
    /** @return 当前规范化 profile JSON 不可变视图。 */ public Map<ResourceLocation, String> profiles() { return profiles; }
    /** @return 最近一次请求结果，可为空。 */ public S2CFireSupportRequestResult lastResult() { return lastResult; }
    /** @return 当前任务状态不可变副本。 */ public synchronized Map<UUID, S2CFireSupportMissionUpdate> missions() { return Map.copyOf(missions); }
}
