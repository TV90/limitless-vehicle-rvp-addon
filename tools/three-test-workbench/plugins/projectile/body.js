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
  const flameMaterial = new THREE.MeshBasicMaterial({ color: '#ff9f43', transparent: true, opacity: 0.85 });
  const flame = new THREE.Mesh(new THREE.ConeGeometry(0.26, 1.8, 8), flameMaterial);
  flame.rotation.x = -Math.PI / 2; flame.position.z = -2.2; flame.visible = false; root.add(flame);
  return {
    /** 图形树由主体统一释放几何与材质。 */
    root,
    /** 仅按当前采样状态显示尾焰，倒拖到点火前立即熄灭。 */
    update({ sample }) { flame.visible = ['主发动机燃烧', '第二脉冲燃烧'].includes(sample.values.motorStage); },
  };
}
