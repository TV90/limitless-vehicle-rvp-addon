#version 150
// 顶点已经是标准化屏幕坐标，不重复应用世界矩阵。
in vec3 Position;
out vec2 screenUV;
void main() {
    gl_Position = vec4(Position, 1.0);
    screenUV = Position.xy * 0.5 + 0.5;
}
