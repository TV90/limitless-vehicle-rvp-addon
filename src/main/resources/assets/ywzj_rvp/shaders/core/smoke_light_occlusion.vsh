#version 150
// 和烟雾正常绘制相同的相机相对坐标；显式使用粒子前保存的世界矩阵，避开光影最终阶段的屏幕矩阵。
uniform mat4 Projection;
uniform mat4 SmokeView;
in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 texCoord;
out vec4 smokeColor;
out vec3 smokePosition;
void main() {
    vec4 position = SmokeView * vec4(Position, 1.0);
    smokePosition = position.xyz;
    texCoord = UV0;
    smokeColor = Color;
    gl_Position = Projection * position;
}
