package org.ywzj.rvp.client.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 有序的调参目录、跨分类搜索与快照恢复；不依赖 Minecraft，便于直接验证。 */
public final class RVP_TuningRegistry {
    /** 按注册顺序展示的扩展模块。 */
    private final Map<String, RVP_TuningModule> modules = new LinkedHashMap<>();
    /** 完整稳定键到业务绑定的索引，供保存器使用。 */
    private final Map<String, RVP_TuningOption> options = new LinkedHashMap<>();
    /** 注册阶段结束后锁定目录，避免保存器与界面看到不同结构。 */
    private boolean sealed;

    /** 一次搜索命中的分组；保留原模块身份，便于全局搜索时显示来源。 */
    public record Section(
            /** 此组所属的分类。 */ RVP_TuningModule module,
            /** 原始分组定义。 */ RVP_TuningModule.Group group,
            /** 当前搜索真正匹配到的参数。 */ List<RVP_TuningOption> options) { }

    /** 注册必须整体成功；重复分类、分组和参数键在启动时立即报错。 */
    public void register(RVP_TuningModule module) {
        Objects.requireNonNull(module);
        if (sealed) { throw new IllegalStateException("调参目录注册阶段已经结束"); }
        // 调用稳定键检查，避免名称中出现保存路径的分隔符。
        checkId(module.id());
        if (modules.containsKey(module.id())) { throw new IllegalArgumentException("重复的调参分类：" + module.id()); }
        Map<String, RVP_TuningOption> added = new LinkedHashMap<>();
        var groups = new java.util.HashSet<String>();
        for (RVP_TuningModule.Group group : module.groups()) {
            checkId(group.id());
            if (!groups.add(group.id())) { throw new IllegalArgumentException("重复的调参分组：" + group.id()); }
            for (RVP_TuningOption option : group.options()) {
                checkId(option.id());
                String key = module.id() + "/" + group.id() + "/" + option.id();
                if (added.putIfAbsent(key, option) != null) { throw new IllegalArgumentException("重复的调参参数：" + key); }
                // 调用默认值校验，阻止坏的扩展定义进入界面。
                option.validate(option.defaultValue());
            }
        }
        modules.put(module.id(), module);
        options.putAll(added);
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_]+")) { throw new IllegalArgumentException("无效的调参稳定键：" + id); }
    }

    /** 只允许在扩展注册事件完成后调用。 */
    public void seal() { sealed = true; }
    /** 返回不可修改且顺序稳定的分类清单。 */
    public List<RVP_TuningModule> modules() { return List.copyOf(modules.values()); }
    /** 返回用于预设校验的只读参数索引。 */
    public Map<String, RVP_TuningOption> options() { return java.util.Collections.unmodifiableMap(options); }

    /** 空搜索查看选中分类；输入搜索词后跨全部分类查找，多个词必须全部命中。 */
    public List<Section> search(String selectedModule, String query) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        List<Section> result = new ArrayList<>();
        for (RVP_TuningModule module : modules.values()) {
            if (words.length == 0 && !module.id().equals(selectedModule)) { continue; }
            for (RVP_TuningModule.Group group : module.groups()) {
                List<RVP_TuningOption> matches = new ArrayList<>();
                for (RVP_TuningOption option : group.options()) {
                    String text = (module.id() + " " + module.title() + " " + group.id() + " " + group.title()
                            + " " + group.description() + " " + option.id() + " " + option.title() + " "
                            + option.description() + " " + option.unit()).toLowerCase(Locale.ROOT);
                    boolean matchesAll = true;
                    for (String word : words) { if (!text.contains(word)) { matchesAll = false; break; } }
                    if (matchesAll) { matches.add(option); }
                }
                if (!matches.isEmpty() || words.length == 0) { result.add(new Section(module, group, List.copyOf(matches))); }
            }
        }
        return List.copyOf(result);
    }

    /** 仅记录真正存在的覆盖，保留“沿用用户配置”与“显式设置成默认值”的区别。 */
    public Map<String, Double> snapshot() {
        Map<String, Double> values = new LinkedHashMap<>();
        options.forEach((key, option) -> option.overrideValue().ifPresent(value -> values.put(key, value)));
        return Map.copyOf(values);
    }

    /** 先校验全部值再恢复；扩展写入失败时回滚，不能把半份预设留给渲染器。 */
    public void restore(Map<String, Double> values) {
        for (var entry : values.entrySet()) {
            RVP_TuningOption option = options.get(entry.getKey());
            if (option == null) { throw new IllegalArgumentException("未知的调参参数：" + entry.getKey()); }
            option.validate(Objects.requireNonNull(entry.getValue()));
        }
        // 调用同一份覆盖快照，在实际业务校验失败时完整恢复之前的状态。
        Map<String, Double> previous = snapshot();
        try {
            apply(values);
        } catch (RuntimeException failure) {
            try { apply(previous); } catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        }
    }

    private void apply(Map<String, Double> values) {
        // 调用每个扩展自己的清理和写入方法，不在通用目录中维护第二套参数状态。
        options.values().forEach(RVP_TuningOption::reset);
        values.forEach((key, value) -> options.get(key).set(value));
    }
}
