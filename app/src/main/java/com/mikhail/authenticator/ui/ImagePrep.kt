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
     * Во сколько раз увеличить картинку перед распознаванием.
     *
     * Детекторы спотыкаются на мелких снимках. Проверенный образец — 238×236: он читается
     * только после увеличения вчетверо, а на двух кратах (476 px) ещё нет. Поэтому целимся
     * в 1600 px по длинной стороне; крупные снимки не трогаем вовсе.
     */
    fun upscaleTarget(longest: Int): Float = when {
        longest <= 0 -> 1f
        longest < 800 -> 1600f / longest
        else -> 1f
    }

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}
