import { buildPreviewReplay } from './scenario.js';

/** 当前场景 JSON 可直接导入工作台；它与真实仿真记录使用不同的识别条件。 */
export default {
  /** 插件接口版本和目录标识。 */
  apiVersion: 1, id: 'projectile-scenario',
  /** 仅接管场景输入；带 format 的结果记录交给对应回放插件。 */
  accepts: data => !data?.format && data?.schemaVersion === 1 && typeof data.weapon === 'string',
  /** 调用几何预览构造器，主体的播放与相机无需知道场景字段。 */
  adapt: buildPreviewReplay,
  /** 箭头局部朝 +Z，统一主体负责按航向与俯仰旋转。 */
  renderers: { arrow({ THREE, object }) {
    return { root: new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(), object.size[0] * 6, object.color) };
  } },
};
