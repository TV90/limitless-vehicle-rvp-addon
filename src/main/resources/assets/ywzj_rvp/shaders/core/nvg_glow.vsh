#version 150

// 夜视仪干扰机光斑顶点着色（2026-10-31）。
// 顶点直接给 NDC，四边形的屏幕位置由 Java 侧按投影换算，这里只负责把局部 UV 传下去。
in vec3 Position;
out vec2 quadUV;

void main() {
    gl_Position = vec4(Position, 1.0);
    quadUV = Position.xy * 0.5 + 0.5;
}
