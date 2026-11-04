package org.ywzj.rvp.client.compat.thermal;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 暂借地形着色器表；重载期间被隐藏的旧资源仍须恰好释放一次。 */
final class RVP_RetainedPrograms {
    /** Oculus 原有着色器表，不能换成另一套所有权不明的 GPU 池。 */
    private final Map<Object, Object> target;
    /** 当前帧借走的句柄；退出时恢复，原所有者销毁后则补做释放。 */
    private final Map<Object, Object> saved;
    /** 调用原 GlProgram.delete，只处理原所有者未能见到的句柄。 */
    private final Consumer<Object> delete;
    /** 防止退出、登出与异常收尾重复恢复或释放。 */
    private boolean closed;

    RVP_RetainedPrograms(Map<Object, Object> target, Consumer<Object> delete) {
        this.target = target;
        this.saved = new HashMap<>(target);
        this.delete = delete;
        target.clear();
    }

    boolean restore(boolean sameOwner) {
        if (closed) return false;
        closed = true;
        if (sameOwner && target.isEmpty()) {
            target.putAll(saved);
            return true;
        }
        Set<Object> released = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object program : saved.values()) {
            // 新一代着色器表可能已重建；不能覆盖新表，也不能删除它仍持有的对象。
            if (program != null && target.values().stream().noneMatch(value -> value == program) && released.add(program)) {
                delete.accept(program);
            }
        }
        return false;
    }
}
