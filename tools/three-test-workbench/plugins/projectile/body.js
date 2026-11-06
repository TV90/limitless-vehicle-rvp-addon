import { DIRECTION_ARROWS, directionTimeline } from './directions.js';

/** 浏览器中给箭头加文字；Node三维几何测试不依赖DOM或canvas。 */
function directionLabel(THREE, definition) {
  if (typeof document === 'undefined') return null;
  const canvas = document.createElement('canvas'); canvas.width = 256; canvas.height = 64;
  const context = canvas.getContext('2d');
  context.font = '22px "Microsoft YaHei UI", sans-serif'; context.textAlign = 'center'; context.textBaseline = 'middle';
  context.fillStyle = '#0d1920df'; context.fillRect(0, 0, 256, 64);
  context.fillStyle = definition.color; context.fillText(definition.name, 128, 32, 245);
  const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, depthTest: false, sizeAttenuation: false }));
  sprite.scale.set(0.13, 0.0325, 1); sprite.position.y = definition.length + 0.7; sprite.renderOrder = 21;
  return sprite;
}

/** 弹体局部示意图；正 Z 为机头，位置与姿态统一由工作台驱动。 */
export function createProjectile({ THREE, object }) {
  const root = new THREE.Group();
  const material = new THREE.MeshStandardMaterial({ color: object.color, roughness: 0.5 });
  const body = new THREE.Mesh(new THREE.CylinderGeometry(0.3, 0.3, 2.8, 10), material);
  body.rotation.x = Math.PI / 2; root.add(body);
  const nose = new THREE.Mesh(new THREE.ConeGeometry(0.3, 1, 10), material);
  nose.rotation.x = Math.PI / 2; nose.position.z = 1.9; root.add(nose);
  for (const angle of [0, Math.PI / 2]) {
    const fin = new THREE.Mesh(new THREE.BoxGeometry(1.4, 0.05, 0.7), material);
    fin.position.z = -1; fin.rotation.z = angle; root.add(fin);
  }
  /** 方向箭头共用弹体中心；单独抵消模型旋转，保证箭头表示世界坐标方向。 */
  const directions = new THREE.Group(); directions.name = 'projectile-direction-arrows'; root.add(directions);
  /** 三根箭头保持不同长度，冷发射时完全重叠也能看出各自颜色。 */
  const arrows = DIRECTION_ARROWS.map(definition => {
    const arrow = new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(), definition.length, definition.color, 1.2, 0.55);
    arrow.name = definition.key; arrow.line.material.depthTest = false; arrow.cone.material.depthTest = false;
    const label = directionLabel(THREE, definition); if (label) arrow.add(label);
    arrow.line.renderOrder = arrow.cone.renderOrder = 20; directions.add(arrow); return arrow;
  });
  /** 固定显示FPS采样器与临时旋转对象；本项目工具调用只读取记录，不推进游戏算法。 */
  const timeline = directionTimeline(object), physicalRotation = new THREE.Quaternion(), displayedRotation = new THREE.Quaternion();
  const euler = new THREE.Euler(0, 0, 0, 'YXZ');
  const flameMaterial = new THREE.MeshBasicMaterial({ color: '#ff9f43', transparent: true, opacity: 0.85 });
  const flame = new THREE.Mesh(new THREE.ConeGeometry(0.26, 1.8, 8), flameMaterial);
  flame.rotation.x = -Math.PI / 2; flame.position.z = -2.2; flame.visible = false; root.add(flame);
  return {
    /** 图形树由主体统一释放几何与材质。 */
    root,
    /** 仅按当前采样状态显示尾焰，倒拖到点火前立即熄灭。 */
    update({ sample, tick }) {
      flame.visible = ['主发动机燃烧', '第二脉冲燃烧'].includes(sample.values.motorStage);
      directions.visible = !!timeline;
      if (!timeline) return;
      // 调用本插件纯采样器；同一播放时刻的速度、机头与模型显示估算从同一份记录查询。
      const state = timeline.sample(tick ?? sample.values.directionDiagnostics?.tick ?? 0);
      physicalRotation.setFromEuler(euler.set(-THREE.MathUtils.degToRad(sample.pitch), -THREE.MathUtils.degToRad(sample.yaw), THREE.MathUtils.degToRad(sample.roll ?? 0), 'YXZ'));
      displayedRotation.setFromEuler(euler.set(-THREE.MathUtils.degToRad(state.displayPitch), -THREE.MathUtils.degToRad(state.displayYaw), THREE.MathUtils.degToRad(sample.roll ?? 0), 'YXZ'));
      // 工作台父节点已经应用真实姿态；模型只补充到估算显示姿态的相对旋转。
      root.quaternion.copy(physicalRotation).invert().multiply(displayedRotation);
      directions.quaternion.copy(displayedRotation).invert();
      DIRECTION_ARROWS.forEach((definition, index) => {
        const vector = new THREE.Vector3(...state[definition.key]);
        arrows[index].visible = vector.lengthSq() > 1e-12;
        if (arrows[index].visible) arrows[index].setDirection(vector.normalize());
      });
    },
  };
}
