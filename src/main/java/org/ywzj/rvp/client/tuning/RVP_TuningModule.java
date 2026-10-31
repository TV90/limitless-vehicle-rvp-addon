package org.ywzj.rvp.client.tuning;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 调参扩展接口；新增模块只需注册描述与读写绑定，不需要修改通用界面或保存器。 */
public interface RVP_TuningModule {
    /** 稳定的分类键，不随显示名称变化。 */
    String id();
    /** 左侧分类栏使用的显示名称。 */
    String title();
    /** 分类说明，包括作用范围和生效时机。 */
    String description();
    /** 按展示顺序返回不可变的分组定义。 */
    List<Group> groups();

    /** 分组只描述内容，折叠与滚动状态由界面持有。 */
    record Group(
            /** 分类内稳定的分组键。 */ String id,
            /** 分组标题。 */ String title,
            /** 组内所有参数共用的说明与生效条件。 */ String description,
            /** 顺序固定的参数定义。 */ List<RVP_TuningOption> options,
            /** 可选的本地预览等操作，不由界面判断业务类型。 */ List<Action> actions) {
        public Group {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(description);
            options = List.copyOf(options);
            actions = List.copyOf(actions);
        }
    }

    /** 操作返回可读反馈；异常由通用界面展示，不能静默失败。 */
    record Action(
            /** 按钮显示名称。 */ String title,
            /** 按钮悬停说明。 */ String description,
            /** 执行本地业务并返回结果；不能在此隐式发送服务端管理指令。 */ Supplier<String> execute,
            /** 成功后临时收起面板观察画面；普通操作保持 false。 */ boolean showWorldAfter) {
        public Action(String title, String description, Supplier<String> execute) {
            this(title, description, execute, false);
        }
        public Action {
            Objects.requireNonNull(title);
            Objects.requireNonNull(description);
            Objects.requireNonNull(execute);
        }
    }
}
