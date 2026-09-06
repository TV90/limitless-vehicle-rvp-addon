package org.ywzj.rvp.firesupport.api;

import java.util.ArrayList;
import java.util.List;

/** 汇总一次候选快照的全部配置问题，便于日志精确定位且支持整批原子拒绝。 */
public final class RVP_FireSupportProblemCollector {
    /** 已发现的路径化错误。 */
    private final List<String> problems = new ArrayList<>();

    /** 记录一条配置错误。 */
    public void add(String path, String message) { problems.add(path + ": " + message); }

    /** @return 是否存在任何错误。 */
    public boolean hasProblems() { return !problems.isEmpty(); }

    /** @return 不可变错误列表。 */
    public List<String> problems() { return List.copyOf(problems); }

    /** 有错误时抛出聚合异常。 */
    public void throwIfAny() {
        if (hasProblems()) throw new IllegalArgumentException(String.join("; ", problems));
    }
}
