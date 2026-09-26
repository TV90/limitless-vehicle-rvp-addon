package org.ywzj.rvp.client.state;

import org.ywzj.rvp.network.S2CEngineDamageState;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] 引擎部件档位客户端侧表（2026-09-26 新增）：按实体 id 存
 * {@code 引擎骨名 → 档位（0 正常 / 1 受损 / 2 瘫痪）}，由 {@link S2CEngineDamageState}
 * 差分推送。辅助设备面板 ENGINE 栏目消费（受损黄注记 / 瘫痪红框走失效表）。
 *
 * <p>与 {@link RVP_ClientBoneModuleState} 同构：条目按实体 id 覆盖式更新，
 * 不主动清理（实体 id 复用时下包覆盖，无陈旧展示路径）。</p>
 */
public final class RVP_ClientEngineDamageState {

    private static final Map<Integer, Map<String, Integer>> STAGES = new HashMap<>();

    private RVP_ClientEngineDamageState() {
    }

    /** 单骨档位：0=正常 / 1=受损 / 2=瘫痪；无条目视为 0。 */
    public static int getStage(int entityId, String boneName) {
        Map<String, Integer> boneMap = STAGES.get(entityId);
        if (boneMap == null) {
            return 0;
        }
        Integer stage = boneMap.get(boneName);
        return stage == null ? 0 : stage;
    }

    /** 全量应用一包（服务端差分推送，整表覆盖该实体）。 */
    public static void apply(S2CEngineDamageState msg) {
        if (msg.stages == null || msg.stages.isEmpty()) {
            STAGES.remove(msg.entityId);
        } else {
            STAGES.put(msg.entityId, new HashMap<>(msg.stages));
        }
    }
}
