#version 150

// 顶点位置与外法线沿用 RVP 球壳网格；不读取贴图或背景颜色。
in vec3 Position;
in vec3 Normal;
// 当前模型观察矩阵、投影矩阵和相对相机的球心。
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uCenter;
// 观察空间中的表面坐标、外法线和球心，供片元阶段计算穿过球壳的长度。
out vec3 vPos;
out vec3 vNormal;
out vec3 vCenter;

void main() {
    vPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    vNormal = normalize(mat3(ModelViewMat) * Normal);
    vCenter = (ModelViewMat * vec4(uCenter, 1.0)).xyz;
    gl_Position = ProjMat * vec4(vPos, 1.0);
}
