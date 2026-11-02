package org.ywzj.rvp.client.hitl;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;

/** 复用原版客户端区块光照应用，避免复制光照协议或依赖光影实现。 */
public interface RVP_HitlClientLightAccess {
    void rvp$applyRemoteLight(int x, int z, ClientboundLightUpdatePacketData data);
    void rvp$enableRemoteLight(LevelChunk chunk, int x, int z);
}
