#version 150
// 自有屏幕三角形只承载逐像素计算，世界烟的位置由片元程序重建。
in vec3 Position;
out vec2 screenUV;
void main() {
    gl_Position = vec4(Position, 1.0);
    screenUV = Position.xy * 0.5 + 0.5;
}
