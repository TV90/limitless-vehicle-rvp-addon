#version 150

// 夜视仪光晕模糊 pass（2026-10-31）：5 抽样线性加权近似 9 抽样高斯，
// 由 BlurDir（水平/垂直单位向量）分别执行两遍，成本低于逐像素 9 tap。
uniform sampler2D DiffuseSampler;
uniform vec2 Resolution;
uniform vec2 BlurDir;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // 变量名不用 step：与 GLSL 内置函数同名，部分驱动会拒绝该 shader
    vec2 texStep = BlurDir / max(Resolution, vec2(1.0));
    vec3 c = texture(DiffuseSampler, texCoord).rgb * 0.2270270;
    c += texture(DiffuseSampler, texCoord + texStep * 1.3846154).rgb * 0.3162162;
    c += texture(DiffuseSampler, texCoord - texStep * 1.3846154).rgb * 0.3162162;
    c += texture(DiffuseSampler, texCoord + texStep * 3.2307692).rgb * 0.0702703;
    c += texture(DiffuseSampler, texCoord - texStep * 3.2307692).rgb * 0.0702703;
    fragColor = vec4(c, 1.0);
}
