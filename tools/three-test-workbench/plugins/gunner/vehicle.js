/** Gunner 专属简化模型；所有 Three.js 依赖由工作台传入，不耦合页面状态。 */
export function createVehicle({ THREE, object }) {
  const root = new THREE.Group();
  const [width, height, length] = object.size;
  const aircraft = object.attributes.mode === 'fixed_wing';
  const material = new THREE.MeshStandardMaterial({ color: object.color, roughness: 0.65, metalness: 0.1 });
  const body = new THREE.Mesh(new THREE.BoxGeometry(width * (aircraft ? 0.3 : 0.85), height * 0.6, length * 0.85), material);
  body.position.y = height * 0.35; root.add(body);
  if (aircraft) {
    const wing = new THREE.Mesh(new THREE.BoxGeometry(width * 1.5, height * 0.1, length * 0.28), material);
    wing.position.y = height * 0.55; root.add(wing);
    const tail = new THREE.Mesh(new THREE.BoxGeometry(width * 0.65, height * 0.1, length * 0.16), material);
    tail.position.set(0, height * 0.5, -length * 0.38); root.add(tail);
  } else {
    const turret = new THREE.Mesh(new THREE.BoxGeometry(width * 0.55, height * 0.25, length * 0.45), material);
    turret.position.y = height * 0.75; root.add(turret);
    const barrel = new THREE.Mesh(new THREE.BoxGeometry(width * 0.1, height * 0.1, length * 0.7), material);
    barrel.position.set(0, height * 0.78, length * 0.4); root.add(barrel);
  }
  root.add(new THREE.ArrowHelper(new THREE.Vector3(0, 0, 1), new THREE.Vector3(0, 0.3, 0), length * 2, object.color));
  return {
    /** root 的位移/姿态由主体统一应用；业务只改变局部模型外观。 */
    root,
    /** 模拟开火时发亮，倒拖后立即按当前快照恢复。 */
    update({ sample }) { material.emissive.set(sample.values.fireAttempt ? '#866124' : '#000000'); },
  };
}
