#version 430 core

in vec3 vNormal;
in vec3 vWorldPos;
in flat int vInstanceId;

out vec4 fragColor;

void main() {
    // Simple directional lighting
    vec3 lightDir = normalize(vec3(1.0, 1.0, 1.0));
    vec3 normal = normalize(vNormal);

    float diffuse = max(0.2, dot(normal, lightDir));

    // Vary color based on position for visual variety
    vec3 color = vec3(
        0.5 + 0.5 * sin(vWorldPos.x * 0.1),
        0.7 + 0.3 * sin(vWorldPos.y * 0.1),
        0.9 + 0.1 * sin(vWorldPos.z * 0.1)
    );

    // Color also based on instance ID
    uint id = uint(vInstanceId);
    if ((id % 2) == 0) {
        color = vec3(0.3, 0.6, 1.0);  // Blue for even entities
    } else {
        color = vec3(1.0, 0.4, 0.2);  // Orange for odd entities
    }

    fragColor = vec4(color * diffuse, 1.0);
}
