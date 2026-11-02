# Three.js 测试工作台

独立的新工具，参考 `tools/gunner-replay` 的观察方式，通过插件接入不同测试。**新增普通场景只加数据；新增特殊测试只加插件目录，播放、时间轴和相机主体都不用改。**

在项目根目录运行：

```powershell
cd tools/three-test-workbench
npm ci
npm start
```

打开 <http://127.0.0.1:4174>。需要 Node.js 22+；依赖固定为 Three.js 0.180.0，安装后页面完全使用本地文件。按 `Ctrl+C` 停止服务，`$env:PORT='4175'` 可以更换端口。

自带解析抛体示例，可直接使用。要观察项目真实 Gunner 仿真，在项目根目录先执行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew gunnerSimulation
```

点击“刷新目录”即可发现结果。`RVP_REPLAY_DIR` 可覆盖 Gunner 插件的数据目录；缺少 Gunner 输出时会显示提示，独立示例仍可使用。

- 播放/暂停、逐刻前后、倍速、时间轴跳转、事件跳转；空格和方向键也可操作。
- 选择或点击对象、跟随、俯视、三维视角、已走轨迹、范围与目标连线。
- 插件专属观察面板、原始观察值、测试范围、整场统计与测试进程提供的断言结果。
- 手动导入 JSON 自动识别插件；发生格式识别冲突时可指定“导入格式”。单文件上限 50 MB。

扩展入口：`plugins/<id>/plugin.json`。服务自动扫描该目录，没有需要修改的插件列表。完整的数据格式、插件接口、示例和边界见 [扩展指南](../ThreeJS测试工作台扩展指南.md)。

```powershell
npm run check
npm test
```

测试覆盖格式校验、时间采样、动态新增插件/测试、当前 Java 记录适配与 HTTP 读取边界。`npm run demo` 重新生成解析示例，保留索引中其他测试条目。Java 算法仍由各自的测试任务执行，工作台负责读取结果并回放。
