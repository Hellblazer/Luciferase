__kernel void complex_compute(__global const float* a, __global const float* b, __global float* result) {
    int i = get_global_id(0);
    float valA = a[i];
    float valB = b[i];
    result[i] = sqrt(valA * valA + valB * valB) * sin(valA) + cos(valB);
}