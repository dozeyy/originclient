// origin:vulkan pipeline vertex main. Adapted from Flywheel's internal/indirect/main.vert and
// internal/instancing/main.vert (Flywheel, MIT License, Copyright (c) 2021-2024 Jozufozu; see
// LICENSE_flywheel-vulkan). Differences: instance data comes from the SSBO at
// gl_InstanceIndex (the draw's firstInstance selects the instancer's region), and the loose
// uniforms (packed material, base vertex, embedding matrices) live in one push-constant block,
// because Vulkan GLSL has no loose uniforms.
#include "flywheel:internal/common.vert"
#include "flywheel:internal/packed_material.glsl"
#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/light.glsl"

layout(push_constant) uniform _FlwVulkanPush {
    mat4 modelMatrix;
    mat3 normalMatrix;
    uvec2 packedMaterial;
    uint baseVertex;
} _flw_push;

void main() {
    _flw_unpackMaterialProperties(_flw_push.packedMaterial.y, flw_material);

    uint instanceIndex = uint(gl_InstanceIndex);
    FlwInstance instance = _flw_unpackInstance(instanceIndex);

    #ifdef FLW_EMBEDDED
    _flw_modelMatrix = _flw_push.modelMatrix;
    _flw_normalMatrix = _flw_push.normalMatrix;
    #endif

    _flw_main(instance, instanceIndex, _flw_push.baseVertex);
}
