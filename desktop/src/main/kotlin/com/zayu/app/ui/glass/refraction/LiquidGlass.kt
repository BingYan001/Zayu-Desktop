package com.zayu.app.ui.glass.refraction

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * 彩色折射液态玻璃（对齐手机端 Kyant0/AndroidLiquidGlass 的光学观感）：
 *
 * 原理说明——为什么是 Skia RuntimeEffect 而不是 D3D11：
 * 桌面端跑在 Compose(Skia) 上，玻璃面板直接用 SKSL 片元着色器画：
 *   1) 圆角矩形 SDF → 面板内任意点到边缘的有向距离；
 *   2) SDF 数值梯度 = 玻璃表面法线，边缘处法线外倾 → 折射随边缘增强（真实透镜行为）；
 *   3) 折射采样：背景图按与背景层完全一致的 cover 裁剪映射重采样，中心轻微放大、
 *      边缘沿法线偏移 → 背景在玻璃内产生真实的扭曲/位移；
 *   4) 色散：R/G/B 三通道按不同偏移量分别采样，边缘出现彩色 fringe；
 *   5) 菲涅尔：视角法向 nz = sqrt(1-k·f)，边缘 fresnel 高光 + 定向镜面高光 + 顶部厚度亮线；
 *   6) 内阴影 + 轻磨砂（5 点采样）+ 亮度/暗化，给玻璃立体感。
 * 折射源 = 应用背景层（泳池壁纸/渐变），与手机端 GlassBackdropHost 同思路；
 * 面板区域之外完全透明，不影响 DWM 桌面亚克力背板。
 */

/** SKSL 着色器源码（uniform 与 [glassShader] 的 builder 一一对应） */
private val LIQUID_GLASS_SKSL = """
uniform float2 uWin;        // 根布局尺寸（px）
uniform float4 uRect;       // 面板矩形 (x,y,w,h)，根布局坐标系
uniform float uRadius;      // 圆角半径 px
uniform float uDisperse;    // 色散强度
uniform float uHighlight;   // 高光强度
uniform float uDark;        // 1=深色主题
uniform float uDim;         // 背景暗化（与背景层一致）
uniform float2 uImg;        // 背景图原始尺寸
uniform shader uBg;         // 背景图着色器

// ★ 尺寸自适应折射参数（由 GlassRefraction.resolve 按面板短边算好再传进来）
//   为什么不在 shader 里自己乘百分比：带宽/位移的上下限裁剪要用到 dp 概念，
//   放在 Kotlin 侧算一次更清晰，也让桌面端与手机端共用同一份比例常量。
uniform float uRimHeight;   // 边缘折射带宽 px
uniform float uAmount;      // 边缘位移量 px
uniform float uRefract;     // 折射总强度倍率（档位 boost）

float sdRRect(float2 p, float2 h, float r) {
    float2 q = abs(p) - h + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

// 圆角矩形法线：边直段直接给轴向，圆角段给径向（与文析 roundedRectNormal 同构）
float2 rrNormal(float2 p, float2 h, float r) {
    float2 corner = abs(p) - h + float2(r);
    if (corner.x >= 0.0 || corner.y >= 0.0) {
        float2 c = max(corner, float2(0.0));
        float len = max(length(c), 0.0001);
        return sign(p) * (c / len);
    }
    float x = step(corner.y, corner.x);
    return sign(p) * float2(x, 1.0 - x);
}

half4 main(float2 fc) {
    float2 h = uRect.zw * 0.5;
    float2 c = uRect.xy + h;
    float2 p = fc - c;
    float r = min(uRadius, min(h.x, h.y));
    float d = sdRRect(p, h, r);
    if (d > 0.5) { return half4(0.0); }

    // ── 文析 / Kyant 的透镜模型 ─────────────────────────────────────────────
    // 1) 面板内距边缘的距离（负值取反，中心最大）
    float t = clamp(1.0 + min(d, 0.0) / max(uRimHeight, 0.0001), 0.0, 1.0);
    // 2) 圆的二次曲线：边缘处位移最大，向中心按 1-sqrt(1-t^2) 平滑收敛
    float displacement = (1.0 - sqrt(max(1.0 - t * t, 0.0))) * uAmount * uRefract;
    // 3) 法线：圆角段用径向、直边段用轴向；再加一点向心分量让中心也有轻微放大
    float2 n = normalize(rrNormal(p, h, min(r * 1.5, min(h.x, h.y) * 0.5)) +
                         0.06 * normalize(p + float2(0.0001, 0.0001)));

    // 背景层同款 cover 裁剪映射
    float s = max(uWin.x / uImg.x, uWin.y / uImg.y);
    float2 crop = (uWin - uImg * s) * 0.5;

    // 折射后的采样点：沿法线**反向**偏移（把边缘外侧的内容"吸"进玻璃里）
    float2 refracted = fc - displacement * n;

    // 4) 色散：R/B 通道沿法线反向采样，位移量受色散强度与短边 4% 双重约束
    float shortest = min(uRect.z, uRect.w);
    float2 sep = min(displacement * uDisperse, shortest * 0.04) * n;

    float2 mC = (refracted - crop) / s;
    float2 mR = (refracted + sep - crop) / s;
    float2 mB = (refracted - sep - crop) / s;

    // 5) 轻磨砂：中心采样叠两个对角微偏移，压掉背景里的高频噪点
    float2 m1 = (refracted + float2(2.5, 1.5) - crop) / s;
    float2 m2 = (refracted - float2(2.5, 1.5) - crop) / s;

    float3 col = float3(
        (uBg.eval(mR).r + uBg.eval(m1).r * 0.35 + uBg.eval(m2).r * 0.35) / 1.7,
        (uBg.eval(mC).g + uBg.eval(m1).g * 0.35 + uBg.eval(m2).g * 0.35) / 1.7,
        (uBg.eval(mB).b + uBg.eval(m1).b * 0.35 + uBg.eval(m2).b * 0.35) / 1.7
    );

    // 与背景层一致的可读性处理：主题色 scrim 洗色 + 暗化，保证玻璃与背景观感连续
    float3 scrim = mix(float3(0.929, 0.941, 0.965), float3(0.051, 0.063, 0.090), uDark);
    col = mix(col, scrim, 0.54);
    col = col * (1.0 - clamp(uDim, 0.0, 0.7));
    col += float3(0.08) * (1.0 - uDark * 0.55);

    // 内阴影（贴边变暗 → 厚度感），宽度跟着自适应带宽走，不再写死
    float inner = smoothstep(uRimHeight * 0.5, 0.0, max(-d, 0.0)) * 0.16;
    col -= float3(inner);

    // 菲涅尔边缘高光 + 定向镜面高光 + 顶部厚度亮线
    float nz = sqrt(clamp(1.0 - t * 0.5, 0.0, 1.0));
    float fres = pow(1.0 - nz, 3.0);
    float3 L = normalize(float3(-0.45, -0.65, 0.62));
    float spec = pow(max(dot(normalize(float3(n * t * 0.9, nz)), L), 0.0), 26.0);
    float hl = clamp(fres * 0.85 * uHighlight + spec * 1.3 * uHighlight, 0.0, 1.0);
    col += float3(hl);
    float topEdge = 1.0 - smoothstep(0.0, 2.5, p.y + h.y);
    col += float3(topEdge * 0.10 * uHighlight);

    return half4(clamp(col, 0.0, 1.0), 1.0);
}
""".trimIndent()

/** 背景层数据（由根布局提供；image 为 null 或 level=0 时玻璃回退为磨砂底色） */
data class GlassBackdropData(
    val image: Image?,
    val imageWidth: Float,
    val imageHeight: Float,
    val winSize: Size,
    val dark: Boolean,
    /** 0=关闭折射 1=标准 2=增强 */
    val level: Int,
    /** 背景暗化（与背景层 dim 一致，保证玻璃与背景观感连续） */
    val dim: Float,
)

val LocalGlassBackdrop = staticCompositionLocalOf {
    GlassBackdropData(null, 0f, 0f, Size.Zero, false, 0, 0f)
}

private object ShaderHolder {
    val builder: RuntimeShaderBuilder by lazy {
        RuntimeShaderBuilder(org.jetbrains.skia.RuntimeEffect.Companion.makeForShader(LIQUID_GLASS_SKSL))
    }
}

/**
 * 液态玻璃面板：内部用 SKSL 画折射/色散/高光，内容叠在玻璃之上。
 * 背景数据缺失或折射关闭时回退为纯色磨砂底（fallbackColor）。
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    /**
     * 是否按**胶囊控件**的折射比例绘制（默认 false = 面板/卡片比例）。
     * 胶囊长宽比极端（如 120×36dp），用面板比例算出来整个按钮内部都在位移、文字发飘，
     * 所以给一个明确开关，由调用方按已知形态传。
     */
    capsule: Boolean = false,
    fallbackColor: Color = Color.White.copy(alpha = 0.35f),
    content: @Composable () -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    var rect by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }

    Box(
        modifier = modifier
            .onGloballyPositioned { rect = it.boundsInRoot() }
            .drawBehind {
                val img = backdrop.image
                if (backdrop.level > 0 && img != null &&
                    backdrop.winSize.width > 0 && rect.width > 0f && rect.height > 0f
                ) {
                    val b = ShaderHolder.builder
                    b.uniform("uWin", backdrop.winSize.width, backdrop.winSize.height)
                    b.uniform("uRect", rect.left, rect.top, rect.width, rect.height)
                    b.uniform("uRadius", radiusPx)
                    // ★ 尺寸自适应：按本面板实测短边算带宽与位移（与手机端同一套比例）
                    //   面板过小（< 48px）时 resolve 返回 (0,0)，此时位移为 0、只剩模糊 + 高光。
                    val (rimPx, amountPx) = GlassRefraction.resolve(
                        shortestPx = minOf(rect.width, rect.height),
                        capsule = capsule
                    )
                    val boost = if (backdrop.level >= 2) 1.6f else 1.0f
                    b.uniform("uRimHeight", rimPx)
                    b.uniform("uAmount", amountPx)
                    b.uniform("uRefract", boost)
                    b.uniform("uDisperse", if (backdrop.level >= 2) 0.06f else 0.04f)
                    b.uniform("uHighlight", boost)
                    b.uniform("uDark", if (backdrop.dark) 1f else 0f)
                    b.uniform("uDim", backdrop.dim)
                    b.uniform("uImg", backdrop.imageWidth, backdrop.imageHeight)
                    // 背景图着色器：CLAMP 平铺（cover 映射在 SKSL 内完成）
                    b.child("uBg", img.makeShader())
                    val shader = b.makeShader(null)
                    drawRect(brush = object : ShaderBrush() {
                        override fun createShader(size: Size): Shader = shader
                    })
                } else {
                    drawRoundRect(
                        color = fallbackColor,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx)
                    )
                }
            }
    ) {
        content()
    }
}
