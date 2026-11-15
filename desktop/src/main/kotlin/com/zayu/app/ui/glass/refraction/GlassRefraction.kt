package com.zayu.app.ui.glass.refraction

/**
 * 折射参数的**尺寸自适应**计算（与手机端 `com.zayu.app.ui.glass.GlassRefraction` 同一套比例）。
 *
 * ★ 为什么需要它：折射位移吃的是绝对像素，而玻璃面板从 32dp 的小胶囊到整屏容器都有一份。
 *   写死像素的结果是——大面板上卷边只占 3%（几乎看不见），小胶囊上又占满整个高度（内容被推歪）。
 *   这是"有的地方像液态玻璃、有的地方只像毛玻璃"的根源。
 *
 * 参考实现（文析 AsterLink 的 liquid_glass.frag，改自 Kyant AndroidLiquidGlass 的 Backdrop）传入
 * 的是 `refractionHeight / shortest`（占短边百分比），本对象把同一套思路搬到桌面端：
 * 只在**绘制期**按组件实测短边算一次，不改任何调用方参数。
 *
 * 比例取值（对齐文析 + iOS 26 Liquid Glass 观感）：
 * - 边缘带宽 ≈ 短边的 [HEIGHT_RATIO]（12%），命中"卷边清晰但不吃正文"的甜区；
 * - 位移量   ≈ 短边的 [AMOUNT_RATIO]（20%），比带宽大才能形成"透镜"把边缘内容吸进来。
 */
object GlassRefraction {

    /** 边缘折射带宽占组件短边的比例 */
    const val HEIGHT_RATIO = 0.12f

    /** 边缘位移量占组件短边的比例（必须大于带宽比例才像"透镜"） */
    const val AMOUNT_RATIO = 0.20f

    /**
     * 胶囊类控件（按钮 / chip / 小开关）的**独立比例**（与手机端 GlassRefraction 保持一致）。
     *
     * ★ 为什么必须分开：胶囊是"小圆角 + 大长宽比"，用面板的比例算出来边缘带太宽，
     *   整个按钮内部都在位移，字被推得发飘；而面板需要更宽的带宽才有卷边感。
     *   这两个结构在光学上不是一回事，一套参数喂两边必然有一边不像。
     */
    const val CAPSULE_HEIGHT_RATIO = 0.22f
    const val CAPSULE_AMOUNT_RATIO = 0.30f

    /** 带宽绝对值上限（px）：超过这个宽度，观感收益趋零、开销持续上涨 */
    const val MAX_HEIGHT_PX = 96f

    /** 位移绝对值上限（px） */
    const val MAX_AMOUNT_PX = 160f

    /** 带宽下限（px）：再小就看不出卷边，等于没做 */
    const val MIN_HEIGHT_PX = 12f

    /** 胶囊位移绝对值上限（px）：36dp 按钮上超过这个量文字就开始糊 */
    const val CAPSULE_MAX_AMOUNT_PX = 30f

    /** 小于这个短边（px）的极小组件不折射，只保留模糊 + 高光 */
    const val MIN_SIZE_PX = 48f

    /**
     * 按组件短边算折射参数。
     *
     * @param shortestPx 组件短边像素（绘制期从 `rect` / `size` 取）
     * @param capsule 胶囊类控件传 true，走独立比例
     * @return Pair(带宽 px, 位移 px)；组件过小时返回 (0, 0) 表示本次不折射
     */
    fun resolve(shortestPx: Float, capsule: Boolean = false): Pair<Float, Float> {
        if (shortestPx <= 0f) return 0f to 0f
        if (shortestPx < MIN_SIZE_PX) return 0f to 0f
        if (!capsule) {
            val height = (shortestPx * HEIGHT_RATIO)
                .coerceIn(MIN_HEIGHT_PX, minOf(MAX_HEIGHT_PX, shortestPx * 0.35f))
            val amount = (shortestPx * AMOUNT_RATIO)
                .coerceIn(MIN_HEIGHT_PX, minOf(MAX_AMOUNT_PX, shortestPx * 0.55f))
            return height to amount
        }
        val height = (shortestPx * CAPSULE_HEIGHT_RATIO)
            .coerceIn(MIN_HEIGHT_PX, minOf(MAX_HEIGHT_PX, shortestPx * 0.32f))
        val amount = (shortestPx * CAPSULE_AMOUNT_RATIO)
            .coerceIn(8f, minOf(CAPSULE_MAX_AMOUNT_PX, shortestPx * 0.45f))
        return height to amount
    }
}
