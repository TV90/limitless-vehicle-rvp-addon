#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 sceneColor = texture(DiffuseSampler, texCoord);
    float g = luma(sceneColor.rgb);
    fragColor = vec4(vec3(g), 1.0);
}

