import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { indexAt } from './replay.js';

/** 内置图形只依赖统一尺寸；特殊图形由插件提供工厂。位置原点在底部中心。 */
const builtinRenderers = {
  box({ object }) {
    const root = new THREE.Mesh(new THREE.BoxGeometry(...object.size), new THREE.MeshStandardMaterial({ color: object.color, roughness: 0.6 }));
    root.position.y = object.size[1] / 2;
    return { root };
  },
  sphere({ object }) {
    const root = new THREE.Mesh(new THREE.SphereGeometry(0.5, 20, 12), new THREE.MeshStandardMaterial({ color: object.color, roughness: 0.4 }));
    root.scale.set(...object.size); root.position.y = object.size[1] / 2;
    return { root };
  },
};

/** 自动释放图形工厂返回的树；共享材质、几何和纹理每次只释放一次。 */
function disposeTree(root) {
  const resources = new Set();
  root.traverse(node => {
    if (node.geometry) resources.add(node.geometry);
    for (const material of Array.isArray(node.material) ? node.material : node.material ? [node.material] : []) {
      resources.add(material);
      for (const value of Object.values(material)) if (value?.isTexture) resources.add(value);
    }
  });
  for (const resource of resources) resource.dispose();
  root.clear();
}

/** 文字画到本地 canvas；记录内容始终不解释成 HTML。 */
function labelSprite(name) {
  const canvas = document.createElement('canvas'); canvas.width = 384; canvas.height = 64;
  const context = canvas.getContext('2d');
  context.fillStyle = '#122632e6'; context.fillRect(0, 0, canvas.width, canvas.height);
  context.font = '24px "Microsoft YaHei UI", sans-serif'; context.textAlign = 'center'; context.textBaseline = 'middle';
  context.fillStyle = '#d4e7e4'; context.fillText(name, 192, 32, 370);
  const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, sizeAttenuation: false, depthTest: false }));
  sprite.scale.set(0.18, 0.03, 1); sprite.renderOrder = 10;
  return sprite;
}

/** 已走轨迹按连续在场区间分段，不把两次出场位置连成跨场景直线。 */
function buildTrail(compiled, objectIndex, color) {
  const points = [], ends = [];
  for (let i = 0; i < compiled.frames.length - 1; i++) {
    const a = compiled.frames[i].objects[objectIndex], b = compiled.frames[i + 1].objects[objectIndex];
    if (!a.active || !b.active) continue;
    for (const sample of [a, b]) points.push(sample.position[0], sample.position[1] - compiled.replay.groundY + 0.08, sample.position[2]);
    ends.push({ tick: compiled.frames[i + 1].tick });
  }
  const geometry = new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
  const material = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.7 });
  const complete = new THREE.LineSegments(geometry, material);
  const partial = new THREE.Line(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(6), 3)), material);
  return { complete, partial, ends };
}

/** Three.js 观察器；没有业务类型分支，不运行被测算法。 */
export class WorkbenchView {
  /** 页面容器、绘制器查找和对象选择/错误回调。 */
  constructor(host, { resolveRenderer, onSelect, onError }) {
    this.resolveRenderer = resolveRenderer; this.onError = onError;
    /** renderer/scene/camera/controls 只在此类维护；插件拿不到页面播放状态。 */
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.scene = new THREE.Scene(); this.scene.background = new THREE.Color('#0d1920');
    this.camera = new THREE.PerspectiveCamera(48, 1, 0.1, 1000000);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true; this.controls.maxPolarAngle = Math.PI * 0.495; this.controls.minDistance = 2;
    const light = new THREE.DirectionalLight('#dbefea', 2.2); light.position.set(-100, 220, 90);
    this.scene.add(light, new THREE.HemisphereLight('#c3dee5', '#1a2631', 2));
    /** stage 是一次成功载入的全部资源；previousFollow 只用于相机连续跟随。 */
    this.stage = null; this.previousFollow = null; this.mode = 'iso';
    host.append(this.renderer.domElement);
    this.resizeObserver = new ResizeObserver(() => {
      const { width, height } = host.getBoundingClientRect();
      this.camera.aspect = width / Math.max(height, 1); this.camera.updateProjectionMatrix(); this.renderer.setSize(width, height);
    });
    this.resizeObserver.observe(host);
    /** 点击对象可以选择，拖动相机不会误选。 */
    let pointerStart = null;
    this.renderer.domElement.addEventListener('pointerdown', event => { pointerStart = [event.clientX, event.clientY]; });
    this.renderer.domElement.addEventListener('pointerup', event => {
      if (!this.stage || !pointerStart || Math.hypot(event.clientX - pointerStart[0], event.clientY - pointerStart[1]) > 5) return;
      const bounds = this.renderer.domElement.getBoundingClientRect(), ray = new THREE.Raycaster();
      ray.setFromCamera(new THREE.Vector2((event.clientX - bounds.left) / bounds.width * 2 - 1, -(event.clientY - bounds.top) / bounds.height * 2 + 1), this.camera);
      const hit = ray.intersectObjects([...this.stage.visuals.values()].filter(v => v.root.visible).map(v => v.root), true)
        .find(value => value.object.userData.objectId);
      if (hit) onSelect(hit.object.userData.objectId);
    });
  }

  /** 在旧场景之外准备资源；工厂或数据出错时旧场景仍然可用。 */
  prepare(compiled) {
    const stage = { world: new THREE.Group(), visuals: new Map(), compiled, center: new THREE.Vector3(), extent: 40 };
    try {
      const replay = compiled.replay, bounds = new THREE.Box3();
      for (const [index, object] of replay.objects.entries()) {
        const factory = Object.hasOwn(builtinRenderers, object.renderer) ? builtinRenderers[object.renderer] : this.resolveRenderer(object.renderer);
        if (!factory) throw new Error(`缺少绘制器 ${object.renderer}，请加载对应插件。`);
        const root = new THREE.Group(), attitude = new THREE.Group(); root.add(attitude); stage.world.add(root);
        const custom = factory({ THREE, object });
        if (!custom?.root?.isObject3D) throw new Error(`${object.renderer} 未返回有效的 root`);
        attitude.add(custom.root);
        // 先登记清理钩子，后续标签、轨迹或钩子校验失败时也能释放插件资源。
        stage.visuals.set(object.id, { custom });
        if ((custom.update !== undefined && typeof custom.update !== 'function')
          || (custom.dispose !== undefined && typeof custom.dispose !== 'function')) throw new Error(`${object.renderer} 未返回有效的 root/update/dispose`);
        const trail = buildTrail(compiled, index, object.color); stage.world.add(trail.complete, trail.partial);
        const radius = Math.max(object.size[0], object.size[2]);
        const ring = new THREE.Mesh(new THREE.RingGeometry(radius * 0.8, radius * 0.86, 40),
          new THREE.MeshBasicMaterial({ color: '#e4f3ee', side: THREE.DoubleSide }));
        ring.rotation.x = -Math.PI / 2; ring.position.y = 0.08; root.add(ring);
        const range = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(Array.from({ length: 96 }, (_, i) =>
          new THREE.Vector3(Math.cos(i / 96 * Math.PI * 2) * (object.range ?? 0), 0.12, Math.sin(i / 96 * Math.PI * 2) * (object.range ?? 0)))),
        new THREE.LineBasicMaterial({ color: object.color, transparent: true, opacity: 0.3 })); root.add(range);
        const label = labelSprite(object.name); label.position.y = object.size[1] + radius; root.add(label);
        const link = new THREE.Line(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(6), 3)),
          new THREE.LineBasicMaterial({ color: '#e99d86', transparent: true, opacity: 0.7 })); stage.world.add(link);
        root.traverse(node => { node.userData.objectId = object.id; });
        stage.visuals.set(object.id, { object, root, attitude, custom, ring, range, link, trail, hookFailed: false });
        for (const frame of compiled.frames) {
          const sample = frame.objects[index];
          if (sample.active) {
            const point = new THREE.Vector3(...sample.position); point.y -= replay.groundY;
            bounds.expandByPoint(point); bounds.expandByPoint(point.clone().add(new THREE.Vector3(...object.size)));
          }
        }
      }
      for (const box of replay.obstacles) {
        const min = new THREE.Vector3(...box.min), max = new THREE.Vector3(...box.max), size = max.clone().sub(min);
        const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size.toArray()), new THREE.MeshStandardMaterial({ color: box.color, roughness: 0.85 }));
        mesh.position.copy(min.clone().add(max).multiplyScalar(0.5)); mesh.position.y -= replay.groundY; stage.world.add(mesh);
        min.y -= replay.groundY; max.y -= replay.groundY; bounds.expandByPoint(min); bounds.expandByPoint(max);
      }
      if (!bounds.isEmpty()) bounds.getCenter(stage.center);
      const size = bounds.getSize(new THREE.Vector3()); stage.extent = Math.max(40, size.x, size.z, size.y * 1.4);
      const ground = new THREE.Mesh(new THREE.PlaneGeometry(stage.extent * 4, stage.extent * 4), new THREE.MeshStandardMaterial({ color: '#11262e', roughness: 1 }));
      ground.rotation.x = -Math.PI / 2; ground.position.set(stage.center.x, -0.15, stage.center.z); stage.world.add(ground);
      const grid = new THREE.GridHelper(stage.extent * 3, 60, '#36545d', '#223e46'); grid.position.set(stage.center.x, 0, stage.center.z); stage.world.add(grid);
      return stage;
    } catch (error) { this.release(stage); throw error; }
  }

  /** 提交成功准备的场景；旧场景只在这一刻释放。 */
  commit(stage) {
    if (this.stage) { this.scene.remove(this.stage.world); this.release(this.stage); }
    this.stage = stage; this.scene.add(stage.world); this.resetCamera();
  }

  /** 插件只清理自己的非图形资源；图形树统一释放。 */
  release(stage) {
    for (const visual of stage.visuals.values()) {
      try { visual.custom.dispose?.(); } catch (error) { this.onError(`插件清理失败：${error.message}`); }
    }
    disposeTree(stage.world);
  }

  /** 跳转、换对象与开关跟随时重置相机参考点。 */
  resetFollow() { this.previousFollow = null; }

  /** 用同一个场景范围切换三维与俯视，不累积姿态。 */
  resetCamera(mode = this.mode) {
    this.mode = mode; this.resetFollow();
    if (!this.stage) return;
    const { center, extent } = this.stage;
    this.controls.target.copy(center);
    this.camera.position.copy(center).add(mode === 'top' ? new THREE.Vector3(0, extent * 1.6, 0.01)
      : new THREE.Vector3(extent * 0.85, extent * 0.9, -extent * 0.95));
    this.camera.lookAt(center); this.controls.update();
  }

  /** 所有绘制都根据绝对时间快照重建，没有 Gunner/武器等专用判断。 */
  update(sample, { selected, trails, ranges, links, follow }) {
    if (!this.stage) return;
    const byId = new Map(sample.objects.map(object => [object.id, object])), { compiled } = this.stage;
    for (const [objectIndex, current] of sample.objects.entries()) {
      const visual = this.stage.visuals.get(current.id), height = visual.object.size[1];
      visual.root.visible = current.active;
      visual.root.position.set(current.position[0], current.position[1] - compiled.replay.groundY, current.position[2]);
      visual.attitude.rotation.set(-THREE.MathUtils.degToRad(current.pitch), -THREE.MathUtils.degToRad(current.yaw), THREE.MathUtils.degToRad(current.roll), 'YXZ');
      visual.ring.visible = current.id === selected; visual.range.visible = ranges && current.id === selected && (visual.object.range ?? 0) > 0;
      visual.trail.complete.visible = trails; visual.trail.complete.geometry.setDrawRange(0, (indexAt(visual.trail.ends, sample.tick) + 1) * 2);
      const frameObject = compiled.frames[sample.index].objects[objectIndex];
      const next = compiled.frames[sample.index + 1]?.objects[objectIndex];
      visual.trail.partial.visible = trails && current.active && !!next?.active;
      if (visual.trail.partial.visible) {
        const values = visual.trail.partial.geometry.attributes.position;
        for (const [i, point] of [frameObject.position, current.position].entries()) values.setXYZ(i, point[0], point[1] - compiled.replay.groundY + 0.08, point[2]);
        values.needsUpdate = true; visual.trail.partial.geometry.computeBoundingSphere();
      }
      const target = byId.get(current.targetId);
      visual.link.visible = links && current.active && !!target?.active;
      if (visual.link.visible) {
        const values = visual.link.geometry.attributes.position;
        values.setXYZ(0, current.position[0], current.position[1] - compiled.replay.groundY + height / 2, current.position[2]);
        values.setXYZ(1, target.position[0], target.position[1] - compiled.replay.groundY + this.stage.visuals.get(target.id).object.size[1] / 2, target.position[2]);
        values.needsUpdate = true; visual.link.geometry.computeBoundingSphere();
      }
      if (!visual.hookFailed) {
        try { visual.custom.update?.({ sample: current, object: visual.object, selected: current.id === selected, tick: sample.tick }); }
        catch (error) { visual.hookFailed = true; this.onError(`绘制器 ${visual.object.renderer} 更新失败：${error.message}`); }
      }
    }
    const selectedObject = byId.get(selected);
    if (follow && selectedObject?.active) {
      const position = new THREE.Vector3(...selectedObject.position); position.y -= compiled.replay.groundY;
      if (this.previousFollow) this.camera.position.add(position.clone().sub(this.previousFollow));
      else {
        const direction = this.camera.position.clone().sub(this.controls.target).normalize();
        this.camera.position.copy(position).add(direction.multiplyScalar(Math.max(25, ...this.stage.visuals.get(selected).object.size.map(v => v * 12))));
      }
      this.controls.target.copy(position); this.previousFollow = position;
    } else this.resetFollow();
  }

  /** 相机平滑与最终渲染独立于测试播放速度。 */
  render() { this.controls.update(); this.renderer.render(this.scene, this.camera); }

  /** 页面退出时也走统一生命周期。 */
  dispose() {
    this.resizeObserver.disconnect(); if (this.stage) this.release(this.stage);
    this.controls.dispose(); this.renderer.dispose();
  }
}
