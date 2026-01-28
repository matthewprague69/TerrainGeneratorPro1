varying vec4 vShadowCoord;
varying vec2 vTexCoord;
varying vec4 vColor;
varying vec3 vNormal;
varying float vViewDepth;

uniform sampler2D uDiffuse;
uniform sampler2D uShadowMap;
uniform int uUseTexture;
uniform vec3 uLightDir;
uniform float uLightStrength;
uniform mat4 uViewMatrix;
uniform float uFogStart;
uniform float uFogEnd;
uniform vec3 uFogColor;

float computeShadow(vec4 shadowCoord, vec3 normal, vec3 lightDir) {
    vec3 proj = shadowCoord.xyz / shadowCoord.w;
    if (proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0) {
        return 1.0;
    }
    if (proj.z < 0.0 || proj.z > 1.0) {
        return 1.0;
    }
    float bias = max(0.0015, 0.006 * (1.0 - dot(normal, -lightDir)));
    float shadow = 0.0;
    float texel = 1.0 / 2048.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 offset = vec2(float(x), float(y)) * texel;
            float depth = texture2D(uShadowMap, proj.xy + offset).r;
            shadow += (proj.z - bias) > depth ? 0.0 : 1.0;
        }
    }
    return shadow / 9.0;
}

void main() {
    vec4 baseColor = vColor;
    if (uUseTexture == 1) {
        baseColor *= texture2D(uDiffuse, vTexCoord);
    }
    vec3 normal = normalize(vNormal);
    vec3 lightDir = normalize((uViewMatrix * vec4(uLightDir, 0.0)).xyz);
    float ndl = max(dot(normal, -lightDir), 0.0);
    float ambient = mix(0.25, 0.7, uLightStrength);
    float diffuse = ndl * mix(0.1, 0.8, uLightStrength);
    float lighting = ambient + diffuse;
    float shadow = computeShadow(vShadowCoord * 0.5 + 0.5, normal, lightDir);
    float shadowStrength = mix(0.15, 0.55, uLightStrength);
    float shadowed = mix(1.0, shadow, shadowStrength);
    float minShadow = mix(0.75, 0.55, uLightStrength);
    shadowed = max(shadowed, minShadow);
    vec3 litColor = baseColor.rgb * lighting * shadowed;
    float fogFactor = clamp((uFogEnd - vViewDepth) / max(0.001, uFogEnd - uFogStart), 0.0, 1.0);
    vec3 fogged = mix(uFogColor, litColor, fogFactor);
    gl_FragColor = vec4(fogged, baseColor.a);
}
