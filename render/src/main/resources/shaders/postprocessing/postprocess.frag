#version 450 core

in vec2 TexCoord;

uniform sampler2D uColorTexture;
uniform float uExposure;
uniform float uGamma;

out vec4 FragColor;

void main() {
    vec3 color = texture(uColorTexture, TexCoord).rgb;
    
    // Tone mapping
    color = vec3(1.0) - exp(-color * uExposure);
    
    // Gamma correction
    color = pow(color, vec3(1.0 / uGamma));
    
    FragColor = vec4(color, 1.0);
}