varying vec4 vShadowCoord;
varying vec2 vTexCoord;
varying vec4 vColor;
varying vec3 vNormal;

uniform mat4 uLightMatrix;
uniform mat4 uViewInverse;

void main() {
    vec4 eyePos = gl_ModelViewMatrix * gl_Vertex;
    vec4 worldPos = uViewInverse * eyePos;
    vShadowCoord = uLightMatrix * worldPos;
    vTexCoord = gl_MultiTexCoord0.st;
    vColor = gl_Color;
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
