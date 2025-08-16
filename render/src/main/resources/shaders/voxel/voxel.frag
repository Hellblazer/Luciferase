#version 450 core

in vec3 FragPos;
in vec3 Normal;
in vec3 VoxelColor;
in float VoxelDensity;

uniform vec3 uCameraPos;
uniform vec3 uLightPos;
uniform vec3 uLightColor;

out vec4 FragColor;

void main() {
    // Ambient
    vec3 ambient = 0.15 * VoxelColor;
    
    // Diffuse
    vec3 norm = normalize(Normal);
    vec3 lightDir = normalize(uLightPos - FragPos);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * uLightColor * VoxelColor;
    
    // Specular
    vec3 viewDir = normalize(uCameraPos - FragPos);
    vec3 reflectDir = reflect(-lightDir, norm);
    float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32);
    vec3 specular = spec * uLightColor * 0.5;
    
    vec3 result = ambient + diffuse + specular;
    FragColor = vec4(result, VoxelDensity);
}