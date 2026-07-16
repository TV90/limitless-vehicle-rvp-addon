#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec3 vNormal;
out vec3 vPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    vNormal = normalize(mat3(ModelViewMat) * Normal);
    vPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
}
