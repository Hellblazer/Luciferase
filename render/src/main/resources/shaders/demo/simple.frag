#version 410 core
in vec3 FragPos;
in vec3 Normal;

out vec4 FragColor;

void main() {
    vec3 lightDir = normalize(vec3(1, 1, 1));
    float diff = max(dot(normalize(Normal), lightDir), 0.0);
    vec3 color = vec3(0.5, 0.7, 1.0) * (0.3 + 0.7 * diff);
    FragColor = vec4(color, 1.0);
}