#version 150
// 屏幕三角形承载体积计算；爆心仍在世界中，不是一张贴在HUD上的动画。
in vec3 Position;
out vec2 screenUV;
void main() {
    gl_Position = vec4(Position, 1.0);
    screenUV = Position.xy * 0.5 + 0.5;
}
