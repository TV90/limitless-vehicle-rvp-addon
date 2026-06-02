---
name: "no-http-telemetry"
description: "约束调试/测试插桩不侵入业务代码，禁止HTTP遥测与高频IO。Invoke when planning instrumentation, logging, debugging, or adding any diagnostics."
---

# No HTTP Telemetry

## 何时调用

- 用户要求“调试/定位问题/加日志/加插桩/自动化测试”
- 你准备在 `tick`、渲染事件、网络包 handler 等高频路径新增任何调试逻辑
- 你准备引入外部服务、网络请求、写文件等“调试基础设施”

## 必须遵守的规范

- 禁止在业务代码中加入任何 HTTP 遥测/上报（包括 `127.0.0.1`）。
- 禁止在高频路径做网络请求、文件 IO、或阻塞操作。
- 优先使用项目现有 `LOGGER`（并且节流/只在状态变化时输出）。
- 插桩必须：
  - 体积小、可回滚
  - 默认关闭或仅开发环境启用
  - 不输出敏感信息

## 推荐做法

- 需要观测状态：加 `LOGGER.debug/info`，并使用计数器或状态变化触发做节流。
- 需要复现：提供最小复现步骤，必要时写测试或提供命令触发打印。
- 需要长期可观测：通过配置项/命令开关启用调试日志，默认关闭。
