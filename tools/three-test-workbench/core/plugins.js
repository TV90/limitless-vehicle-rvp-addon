import { compileReplay, isId } from './replay.js';

/** 插件只实现数据适配与可选绘制/面板；主体不按插件 id 分支。 */
export class PluginRegistry {
  /** 当前已加载且合同有效的插件，以 manifest 中的 id 为键。 */
  #plugins = new Map();

  /** 注册默认导出；拒绝重名，防止一个插件覆盖另一插件。 */
  add(plugin, expectedId) {
    if (!plugin || plugin.apiVersion !== 1 || !isId(plugin.id) || plugin.id !== expectedId
      || typeof plugin.accepts !== 'function' || typeof plugin.adapt !== 'function'
      || (plugin.inspect !== undefined && typeof plugin.inspect !== 'function')) throw new Error(`插件 ${expectedId} 的接口无效`);
    if (this.#plugins.has(plugin.id)) throw new Error(`插件 id 重复：${plugin.id}`);
    if (plugin.renderers !== undefined && (!plugin.renderers || Array.isArray(plugin.renderers)
      || typeof plugin.renderers !== 'object')) throw new Error(`插件 ${plugin.id} 的 renderers 必须是对象`);
    for (const [name, factory] of Object.entries(plugin.renderers ?? {})) {
      if (!isId(name) || typeof factory !== 'function') throw new Error(`插件 ${plugin.id} 的绘制器 ${name} 无效`);
    }
    this.#plugins.set(plugin.id, plugin);
  }

  /** 供页面判定某个目录是否已成功载入。 */
  has(id) { return this.#plugins.has(id); }

  /** 可选指定插件；自动识别发生冲突时明确拒绝，不靠加载顺序猜测。 */
  async decode(data, pluginId) {
    const candidates = [...this.#plugins.values()].filter(plugin => (!pluginId || plugin.id === pluginId) && plugin.accepts(data));
    if (candidates.length !== 1) throw new Error(candidates.length
      ? '多个插件能读取此文件，请在“导入格式”中指定插件。' : '没有已加载的插件能读取此文件，请检查格式与插件提示。');
    const plugin = candidates[0];
    // 插件负责解释业务字段；主体只校验统一的观察数据。
    const compiled = compileReplay(await plugin.adapt(data));
    return { plugin, compiled };
  }

  /** 绘制器用“插件 id:局部名称”引用，不占用内置 box/sphere 名称。 */
  renderer(name) {
    const [id, localName] = name.split(':');
    if (!localName) return undefined;
    const renderers = this.#plugins.get(id)?.renderers;
    return renderers && Object.hasOwn(renderers, localName) ? renderers[localName] : undefined;
  }
}
