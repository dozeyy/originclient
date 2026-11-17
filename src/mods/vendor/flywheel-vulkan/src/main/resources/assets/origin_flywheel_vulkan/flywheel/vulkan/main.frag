// origin:vulkan pipeline fragment main. Adapted from Flywheel's internal/instancing/main.frag
// (Flywheel, MIT License, Copyright (c) 2021-2024 Jozufozu; see LICENSE_flywheel-vulkan).
// The packed material comes from the push-constant block shared with main.vert, and light
// is read from SSBOs (indirect/light.glsl) instead of GL texture buffers.
#include "flywheel:internal/common.frag"
#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/light.glsl"

layout(push_constant) uniform _FlwVulkanPush {
    mat4 modelMatrix;
    mat3 normalMatrix;
    uvec2 packedMaterial;
    uint baseVertex;
} _flw_push;

void main() {
    _flw_unpackUint2x16(_flw_push.packedMaterial.x, _flw_uberFogIndex, _flw_uberCutoutIndex);
    _flw_unpackMaterialProperties(_flw_push.packedMaterial.y, flw_material);

    _flw_main();
}
