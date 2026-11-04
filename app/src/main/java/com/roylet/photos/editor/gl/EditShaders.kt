package com.roylet.photos.editor.gl

import com.roylet.photos.editor.recipe.PipelineConstants as K
import com.roylet.photos.editor.recipe.RenderParams
import java.util.Locale

/**
 * GLSL ES 3.00 source of the editor pipeline. It mirrors
 * [com.roylet.photos.editor.cpu.ReferenceRenderer] function for function; tuning constants come
 * from [K] so the two cannot drift.
 *
 * The source is split so the colour maths can be reused for video in M2:
 * - [COLOR_PIPELINE] declares the colour uniforms and `vec3 shade(vec3 c, vec3 blur, vec3 nb,
 *   vec2 outUv)` — display-referred sRGB in, sRGB out, no geometry and no texture fetches except
 *   the curve/LUT tables. A Media3 `GlEffect`/`GlShaderProgram` (e.g. a
 *   `BaseGlShaderProgram` subclass) can prepend `#version 300 es`, include this block, sample its
 *   input frame and call `shade(c, c, c, uv)` (local-contrast effects need a blurred input
 *   texture; passing `c` disables them), setting the same uniforms from [RenderParams].
 *   Media3 hands effects linear or sRGB input depending on its configuration: use sRGB/SDR.
 * - [VERTEX] applies the geometry (crop/rotate/straighten/flip/perspective) as a homography
 *   evaluated per vertex in homogeneous coordinates; the fragment shader divides.
 * - [FRAGMENT] = [COLOR_PIPELINE] + source/blur sampling + bounds test.
 */
object EditShaders {
    // Attributes
    const val A_POS = "aPos"

    // Geometry / tiling
    const val U_OUT_TO_SRC = "uOutToSrc"
    const val U_TILE = "uTile"
    const val U_CLIP = "uClip"
    const val U_SOURCE = "uSource"
    const val U_SRC_RECT = "uSrcRect"
    const val U_SRC_TEXEL = "uSrcTexel"
    const val U_BLUR = "uBlur"

    // Colour
    const val U_EXPOSURE = "uExposure"
    const val U_CONTRAST = "uContrast"
    const val U_HIGHLIGHTS = "uHighlights"
    const val U_SHADOWS = "uShadows"
    const val U_WHITES = "uWhites"
    const val U_BLACKS = "uBlacks"
    const val U_TEMPERATURE = "uTemperature"
    const val U_TINT = "uTint"
    const val U_VIBRANCE = "uVibrance"
    const val U_SATURATION = "uSaturation"
    const val U_HSL = "uHsl"
    const val U_HSL_ON = "uHslOn"
    const val U_CURVE = "uCurve"
    const val U_CURVE_ON = "uCurveOn"
    const val U_LUT = "uLut"
    const val U_LUT_SIZE = "uLutSize"
    const val U_LUT_AMOUNT = "uLutAmount"
    const val U_CLARITY = "uClarity"
    const val U_DEHAZE = "uDehaze"
    const val U_SHARPEN = "uSharpen"
    const val U_VIGNETTE = "uVignette"
    const val U_GRAIN = "uGrain"
    const val U_GRAIN_SCALE = "uGrainScale"

    val VERTEX_UNIFORMS = listOf(U_OUT_TO_SRC, U_TILE, U_CLIP)
    val COLOR_UNIFORMS = listOf(
        U_EXPOSURE, U_CONTRAST, U_HIGHLIGHTS, U_SHADOWS, U_WHITES, U_BLACKS,
        U_TEMPERATURE, U_TINT, U_VIBRANCE, U_SATURATION, U_HSL, U_HSL_ON,
        U_CURVE, U_CURVE_ON, U_LUT, U_LUT_SIZE, U_LUT_AMOUNT,
        U_CLARITY, U_DEHAZE, U_SHARPEN, U_VIGNETTE, U_GRAIN, U_GRAIN_SCALE,
    )
    val FRAGMENT_UNIFORMS = COLOR_UNIFORMS + listOf(U_SOURCE, U_SRC_RECT, U_SRC_TEXEL, U_BLUR)
    val ALL_UNIFORMS = VERTEX_UNIFORMS + FRAGMENT_UNIFORMS

    private fun f(v: Float): String = String.format(Locale.ROOT, "%.8f", v)

    val VERTEX: String = """
#version 300 es
in vec2 $A_POS;
uniform mat3 $U_OUT_TO_SRC;
uniform vec4 $U_TILE;  // output-uv rect of this draw: x0, y0, x1, y1
uniform vec4 $U_CLIP;  // clip = (aPos * 2 - 1) * xy + zw
out vec3 vSrc;
out vec2 vOut;
void main() {
    vec2 o = mix($U_TILE.xy, $U_TILE.zw, $A_POS);
    vOut = o;
    vSrc = $U_OUT_TO_SRC * vec3(o, 1.0);
    gl_Position = vec4(($A_POS * 2.0 - 1.0) * $U_CLIP.xy + $U_CLIP.zw, 0.0, 1.0);
}
""".trimStart()

    /** Colour uniforms + `shade()`. Needs `precision highp float/int/sampler3D` before it. */
    val COLOR_PIPELINE: String = """
uniform float $U_EXPOSURE;
uniform float $U_CONTRAST;
uniform float $U_HIGHLIGHTS;
uniform float $U_SHADOWS;
uniform float $U_WHITES;
uniform float $U_BLACKS;
uniform float $U_TEMPERATURE;
uniform float $U_TINT;
uniform float $U_VIBRANCE;
uniform float $U_SATURATION;
uniform vec3 $U_HSL[8];
uniform bool $U_HSL_ON;
uniform sampler2D $U_CURVE;   // 256x1, rgb = composite channel curves
uniform bool $U_CURVE_ON;
uniform sampler3D $U_LUT;
uniform float $U_LUT_SIZE;
uniform float $U_LUT_AMOUNT;
uniform float $U_CLARITY;
uniform float $U_DEHAZE;
uniform float $U_SHARPEN;
uniform float $U_VIGNETTE;
uniform float $U_GRAIN;
uniform vec2 $U_GRAIN_SCALE;

const vec3 LUMA = vec3(${f(K.LUMA_R)}, ${f(K.LUMA_G)}, ${f(K.LUMA_B)});
const float HSL_CENTRES[8] = float[8](${RenderParams.HSL_CENTRES.joinToString { f(it) }});

float luma(vec3 c) { return dot(c, LUMA); }
float max3(vec3 c) { return max(c.r, max(c.g, c.b)); }
float min3(vec3 c) { return min(c.r, min(c.g, c.b)); }

float srgbToLinear1(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
float linearToSrgb1(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
vec3 srgbToLinear(vec3 c) { return vec3(srgbToLinear1(c.r), srgbToLinear1(c.g), srgbToLinear1(c.b)); }
vec3 linearToSrgb(vec3 c) { return vec3(linearToSrgb1(c.r), linearToSrgb1(c.g), linearToSrgb1(c.b)); }

vec3 localContrast(vec3 c, vec3 blur, vec3 nb) {
    float l = luma(c);
    if ($U_SHARPEN > 0.0) c += (l - luma(nb)) * $U_SHARPEN * ${f(K.SHARPEN_GAIN)};
    if ($U_CLARITY != 0.0) {
        float m = 2.0 * l - 1.0;
        float mid = max(0.0, 1.0 - m * m);
        c += (l - luma(blur)) * mid * $U_CLARITY * ${f(K.CLARITY_GAIN)};
    }
    if ($U_DEHAZE > 0.0) {
        float dark = min3(blur);
        float t = max(1.0 - ${f(K.DEHAZE_GAIN)} * $U_DEHAZE * dark, ${f(K.DEHAZE_MIN_T)});
        c = (c - 1.0) / t + 1.0;
    } else if ($U_DEHAZE < 0.0) {
        float haze = luma(blur) * 0.5 + 0.5;
        c += (vec3(haze) - c) * (-$U_DEHAZE * ${f(K.HAZE_GAIN)});
    }
    return clamp(c, 0.0, 1.0);
}

float toneCurve(float l0) {
    float b0 = -${f(K.BLACKS_GAIN)} * $U_BLACKS;
    float w0 = 1.0 - ${f(K.WHITES_GAIN)} * $U_WHITES;
    float l = clamp((l0 - b0) / (w0 - b0), 0.0, 1.0);
    float sa = $U_SHADOWS > 0.0 ? $U_SHADOWS * ${f(K.SHADOWS_UP)} : $U_SHADOWS * ${f(K.SHADOWS_DOWN)};
    float ha = $U_HIGHLIGHTS > 0.0 ? $U_HIGHLIGHTS * ${f(K.HIGHLIGHTS_UP)} : $U_HIGHLIGHTS * ${f(K.HIGHLIGHTS_DOWN)};
    float inv = 1.0 - l;
    l += sa * l * inv * inv + ha * l * l * inv;
    l = clamp(l, 0.0, 1.0);
    float k = 1.0 + ${f(K.CONTRAST_GAIN)} * $U_CONTRAST;
    l = l < 0.5 ? 0.5 * pow(2.0 * l, k) : 1.0 - 0.5 * pow(2.0 - 2.0 * l, k);
    return clamp(l, 0.0, 1.0);
}

float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 applyHsl(vec3 c) {
    float mx = max3(c);
    float mn = min3(c);
    float d = mx - mn;
    if (d < 0.000001) return c;
    float l = (mx + mn) * 0.5;
    float s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    float h;
    if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    h /= 6.0;
    float deg = h * 360.0;
    vec3 adj = $U_HSL[0];
    for (int i = 0; i < 8; i++) {
        float c0 = HSL_CENTRES[i];
        float c1 = i == 7 ? 360.0 : HSL_CENTRES[min(i + 1, 7)];
        if (deg >= c0 && deg < c1) {
            float t = smoothstep(0.0, 1.0, (deg - c0) / (c1 - c0));
            adj = mix($U_HSL[i], $U_HSL[(i + 1) % 8], t);
        }
    }
    h = fract(h + adj.x * ${f(K.HSL_HUE_DEGREES)} / 360.0);
    s = clamp(s * (1.0 + adj.y), 0.0, 1.0);
    l = clamp(l + adj.z * ${f(K.HSL_LUM_GAIN)} * d, 0.0, 1.0);
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(hue2rgb(p, q, h + 1.0 / 3.0), hue2rgb(p, q, h), hue2rgb(p, q, h - 1.0 / 3.0));
}

uint hashU(uint x) {
    x ^= x >> 16u; x *= 0x7feb352du;
    x ^= x >> 15u; x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}
float hash2(uint x, uint y) { return float(hashU(x ^ hashU(y + 0x9e3779b9u)) & 0xffffu) / 65535.0; }
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    uint x = uint(i.x);
    uint y = uint(i.y);
    float a = hash2(x, y);
    float b = hash2(x + 1u, y);
    float c = hash2(x, y + 1u);
    float d = hash2(x + 1u, y + 1u);
    float top = a + (b - a) * f.x;
    float bottom = c + (d - c) * f.x;
    return top + (bottom - top) * f.y;
}

// c: display-referred sRGB; blur: blurred copy at the same point; nb: mean of the 4 neighbours;
// outUv: position in the output image (vignette, grain).
vec3 shade(vec3 c, vec3 blur, vec3 nb, vec2 outUv) {
    c = localContrast(c, blur, nb);
    vec3 lin = srgbToLinear(c);
    lin *= vec3(1.0 + ${f(K.TEMP_GAIN)} * $U_TEMPERATURE, 1.0 - ${f(K.TINT_GAIN)} * $U_TINT,
                1.0 - ${f(K.TEMP_GAIN)} * $U_TEMPERATURE);
    lin *= exp2($U_EXPOSURE);
    float y = luma(lin);
    float y2 = srgbToLinear1(toneCurve(linearToSrgb1(min(y, 1.0))));
    lin = y < ${f(K.TONE_EPSILON)} ? lin + (y2 - y) : lin * (y2 / y);
    c = linearToSrgb(clamp(lin, 0.0, 1.0));
    float yp = luma(c);
    if ($U_VIBRANCE != 0.0) {
        float chroma = max3(c) - min3(c);
        c = yp + (c - yp) * (1.0 + $U_VIBRANCE * (1.0 - chroma));
    }
    if ($U_SATURATION != 0.0) c = yp + (c - yp) * (1.0 + $U_SATURATION);
    c = clamp(c, 0.0, 1.0);
    if ($U_HSL_ON) c = applyHsl(c);
    if ($U_CURVE_ON) {
        vec3 x = c * (255.0 / 256.0) + 0.5 / 256.0;
        c = vec3(texture($U_CURVE, vec2(x.r, 0.5)).r, texture($U_CURVE, vec2(x.g, 0.5)).g,
                 texture($U_CURVE, vec2(x.b, 0.5)).b);
    }
    if ($U_LUT_AMOUNT > 0.0) {
        vec3 l = texture($U_LUT, c * (($U_LUT_SIZE - 1.0) / $U_LUT_SIZE) + 0.5 / $U_LUT_SIZE).rgb;
        c += (l - c) * $U_LUT_AMOUNT;
    }
    if ($U_VIGNETTE != 0.0) {
        vec2 p = (outUv - 0.5) * 2.0;
        float w = smoothstep(${f(K.VIGNETTE_START)}, 1.0, dot(p, p) * 0.5);
        if ($U_VIGNETTE < 0.0) c *= 1.0 + $U_VIGNETTE * ${f(K.VIGNETTE_DARKEN)} * w;
        else c += (1.0 - c) * ($U_VIGNETTE * ${f(K.VIGNETTE_LIGHTEN)} * w);
    }
    if ($U_GRAIN > 0.0) {
        float n = valueNoise(outUv * $U_GRAIN_SCALE) - 0.5;
        float l = luma(c) * 2.0 - 1.0;
        c += n * ($U_GRAIN * ${f(K.GRAIN_GAIN)} * (1.0 - 0.6 * l * l));
    }
    return clamp(c, 0.0, 1.0);
}
"""

    val FRAGMENT: String = """
#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
$COLOR_PIPELINE
uniform sampler2D $U_SOURCE;
uniform vec4 $U_SRC_RECT;   // source-uv rect covered by uSource: x, y, w, h
uniform vec2 $U_SRC_TEXEL;  // 1 / uSource size, in uSource uv
uniform sampler2D $U_BLUR;  // blurred copy of the whole source (source uv)
in vec3 vSrc;
in vec2 vOut;
out vec4 fragColor;
void main() {
    vec2 uv = vSrc.xy / vSrc.z;
    vec2 tuv = (uv - $U_SRC_RECT.xy) / $U_SRC_RECT.zw;
    // All fetches happen in uniform control flow (implicit derivatives stay defined).
    vec3 c = texture($U_SOURCE, tuv).rgb;
    vec3 nb = 0.25 * (texture($U_SOURCE, tuv + vec2($U_SRC_TEXEL.x, 0.0)).rgb +
                      texture($U_SOURCE, tuv - vec2($U_SRC_TEXEL.x, 0.0)).rgb +
                      texture($U_SOURCE, tuv + vec2(0.0, $U_SRC_TEXEL.y)).rgb +
                      texture($U_SOURCE, tuv - vec2(0.0, $U_SRC_TEXEL.y)).rgb);
    vec3 blur = texture($U_BLUR, uv).rgb;
    bool inside = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
    vec3 outC = shade(c, blur, nb, vOut);
    fragColor = inside ? vec4(outC, 1.0) : vec4(0.0, 0.0, 0.0, 1.0);
}
""".trimStart()
}
