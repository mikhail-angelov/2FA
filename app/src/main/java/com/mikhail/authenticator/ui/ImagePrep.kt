package com.mikhail.authenticator.ui

/**
 * Подготовка картинки к распознаванию QR.
 *
 * Детектор (ML Kit, как и любой другой) читает код уверенно только когда модули контрастны
 * и вокруг кода есть поле. Скриншоты и пересланные в мессенджере картинки приходят
 * маленькими, размытыми и обрезанными «в край» — на них он молча возвращает «ничего не
 * найдено», и пользователь видит только общую ошибку. Поэтому готовим несколько вариантов
 * одной картинки: как есть, с белым полем и бинаризованный.
 *
 * Здесь только арифметика над пикселями (без Android), поэтому её проверяют юнит-тесты.
 */
object ImagePrep {

    /** Яркость пикселей по Rec. 601: целые 0..255, без плавающей точки. */
    fun luminance(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (77 * r + 150 * g + 29 * b) shr 8
    }

    /**
     * Порог Оцу: делит гистограмму так, чтобы разброс между двумя группами был максимален.
     * Для QR этого достаточно — код всегда почти чёрно-белый.
     */
    fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size
        if (total == 0) return 128

        var sum = 0L
        for (v in 0..255) sum += v.toLong() * hist[v]

        var sumB = 0L
        var wB = 0L
        var best = 0.0
        var threshold = 128
        for (v in 0..255) {
            wB += hist[v]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += v.toLong() * hist[v]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = v
            }
        }
        return threshold
    }

    /** Яркость → чёрное/белое по порогу. */
    fun binarize(gray: IntArray, threshold: Int): IntArray = IntArray(gray.size) { i ->
        if (gray[i] > threshold) WHITE else BLACK
    }

    /**
     * Белое поле вокруг картинки: детектору нужна граница, чтобы найти углы кода,
     * а обрезанный «в край» скриншот её не даёт.
     * @return пиксели новой картинки, ширина и высота — считаются как [w] + 2 * [pad].
     */
    fun addWhiteBorder(gray: IntArray, w: Int, h: Int, pad: Int): IntArray {
        require(w > 0 && h > 0) { "нужны размеры картинки" }
        require(gray.size == w * h) { "размер массива не совпадает с $w x $h" }
        if (pad <= 0) return gray
        val nw = w + pad * 2
        val nh = h + pad * 2
        val out = IntArray(nw * nh) { WHITE }
        for (y in 0 until h) {
            val from = y * w
            val to = (y + pad) * nw + pad
            gray.copyInto(out, to, from, from + w)
        }
        return out
    }

    /**
     * Во сколько раз увеличить картинку перед распознаванием — набором, а не одним числом.
     *
     * Детекторы капризны к обе стороны: на проверенном образце 238×236 локальный детектор
     * читает код при ×4 (952 px) и не читает ни при ×2 (476 px), ни на 1600 px. Другой
     * детектор (ML Kit в приложении) ведёт себя иначе, но правило то же — запас по размерам
     * нужен. Поэтому даём два целевых размера: 952 px (геометрия, на которой код заведомо
     * читается) и 1600 px (запас для размытых снимков). Крупные снимки не растягиваем.
     */
    fun upscaleTargets(longest: Int): List<Float> {
        if (longest <= 0) return listOf(1f)
        val targets = LinkedHashSet<Float>()
        for (side in intArrayOf(952, 1600)) {
            if (longest < side) targets += side.toFloat() / longest
        }
        if (targets.isEmpty()) targets += 1f
        return targets.toList()
    }

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}
