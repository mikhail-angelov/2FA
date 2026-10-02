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
        require(w > 0 && h > 0) { "image dimensions are required" }
        require(gray.size == w * h) { "array size does not match $w x $h" }
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

    /**
     * Увеличение с бикубической интерполяцией (Catmull-Rom) — по серому массиву.
     *
     * Почему не [android.graphics.Bitmap.createScaledBitmap]: он умеет только билинейное
     * сглаживание, а на мелком коде (в разборе — 238×236 при 853 знаках, около трёх пикселей
     * на модуль) разница решающая. Проверено на живом образце: по цветной картинке декодер не
     * находит код ни в одном из 84 вариантов подготовки, а по серой картинке, увеличенной ×4
     * бикубикой, — находит сразу. Поэтому увеличение делаем сами, чистой арифметикой: тогда
     * оно и проверяется юнит-тестами.
     *
     * @param gray яркость пикселей (0..255), [w] × [h]
     * @param factor целочисленный множитель (1 — без увеличения)
     * @return яркость новой картинки, [w] * [factor] × [h] * [factor]
     */
    fun upscaleBicubic(gray: IntArray, w: Int, h: Int, factor: Int): IntArray {
        require(w > 0 && h > 0) { "image dimensions are required" }
        require(gray.size == w * h) { "array size does not match $w x $h" }
        require(factor >= 1) { "the factor must be at least one" }
        if (factor == 1) return gray

        val nw = w * factor
        val nh = h * factor
        val out = IntArray(nw * nh)

        for (y in 0 until nh) {
            // центр исходного пикселя, соответствующий центру нового
            val sy = (y + 0.5) / factor - 0.5
            val y0 = kotlin.math.floor(sy).toInt()
            val fy = sy - y0
            for (x in 0 until nw) {
                val sx = (x + 0.5) / factor - 0.5
                val x0 = kotlin.math.floor(sx).toInt()
                val fx = sx - x0

                var acc = 0.0
                var weight = 0.0
                for (j in -1..2) {
                    val wy = cubicWeight(fy - j)
                    val yy = (y0 + j).coerceIn(0, h - 1)
                    for (i in -1..2) {
                        val wx = cubicWeight(fx - i)
                        val xx = (x0 + i).coerceIn(0, w - 1)
                        val t = wx * wy
                        acc += t * gray[yy * w + xx]
                        weight += t
                    }
                }
                val v = if (weight != 0.0) acc / weight else acc
                out[y * nw + x] = v.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Вес бикубической интерполяции (Catmull-Rom, a = -0,5) для расстояния [t]. */
    private fun cubicWeight(t: Double): Double {
        val a = -0.5
        val at = kotlin.math.abs(t)
        return when {
            at <= 1.0 -> ((a + 2) * at - (a + 3)) * at * at + 1
            at < 2.0 -> (((at - 5) * at + 8) * at - 4) * a
            else -> 0.0
        }
    }

    /**
     * Бикубическое увеличение ×[factor] с разделением по осям — для больших множителей.
     *
     * Зачем отдельный вариант: проверено на живом образце, что код в мелкой картинке читается
     * только при увеличении ×16 (3808 px), а ×4, ×6, ×8 и ×12 не читаются. На таких размерах
     * прямой перебор 4×4 на каждый пиксель — это 230 млн умножений; ядро разделяется по осям,
     * и работа сокращается вчетверо: сначала проход по горизонтали, затем по вертикали.
     *
     * Возвращает яркость **байтами**: 14 МБ вместо 57 МБ на `IntArray` того же размера.
     * Промежуточные значения держатся в Double (одна строка — десятки килобайт), поэтому
     * результат совпадает с [upscaleBicubic] до последнего бита, а не «примерно».
     */
    fun upscaleBicubicBytes(gray: IntArray, w: Int, h: Int, factor: Int): ByteArray {
        require(w > 0 && h > 0) { "image dimensions are required" }
        require(gray.size == w * h) { "array size does not match $w x $h" }
        require(factor >= 1) { "the factor must be at least one" }
        if (factor == 1) return ByteArray(gray.size) { gray[it].toByte() }

        val nw = w * factor
        val nh = h * factor

        // Смещение и веса зависят только от номера пикселя внутри шага, поэтому таблица
        // считается один раз на множитель — иначе веса пересчитывались бы миллионы раз.
        // Сумма весов нужна для нормировки: у краёв часть соседей поджимается к границе, и без
        // деления на сумму края уходят в темноту (проверено сверкой с прямым перебором).
        val offset = IntArray(factor)
        val wts = Array(factor) { DoubleArray(4) }
        val sums = DoubleArray(factor)
        for (r in 0 until factor) {
            val s = (r + 0.5) / factor - 0.5
            val base = kotlin.math.floor(s).toInt()
            val frac = s - base
            offset[r] = base
            var sum = 0.0
            for (j in -1..2) {
                wts[r][j + 1] = cubicWeight(frac - j)
                sum += wts[r][j + 1]
            }
            sums[r] = if (sum != 0.0) sum else 1.0
        }

        // Проход по горизонтали: каждая исходная строка растягивается до nw значений.
        val rows = Array(h) { DoubleArray(nw) }
        for (y in 0 until h) {
            val src = y * w
            for (x in 0 until nw) {
                val r = x % factor
                val i0 = x / factor + offset[r]
                val k = wts[r]
                var acc = 0.0
                for (j in 0 until 4) {
                    val sx = (i0 + j - 1).coerceIn(0, w - 1)
                    acc += k[j] * gray[src + sx]
                }
                rows[y][x] = acc / sums[r]
            }
        }

        // Проход по вертикали: собираем итоговую яркость байтами.
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val r = y % factor
            val j0 = y / factor + offset[r]
            val k = wts[r]
            val norm = sums[r]
            val row0 = rows[(j0 - 1).coerceIn(0, h - 1)]
            val row1 = rows[j0.coerceIn(0, h - 1)]
            val row2 = rows[(j0 + 1).coerceIn(0, h - 1)]
            val row3 = rows[(j0 + 2).coerceIn(0, h - 1)]
            val k0 = k[0]
            val k1 = k[1]
            val k2 = k[2]
            val k3 = k[3]
            val base = y * nw
            for (x in 0 until nw) {
                val v = (k0 * row0[x] + k1 * row1[x] + k2 * row2[x] + k3 * row3[x]) / norm
                out[base + x] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
