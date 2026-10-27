#version 150

// 全屏三角形的位置已经是标准化屏幕坐标，不重复应用地形的观察或投影矩阵。
in vec3 Position;
out vec2 screenUV;

void main() {
    gl_Position = vec4(Position, 1.0);
    screenUV = Position.xy * 0.5 + 0.5;
}
