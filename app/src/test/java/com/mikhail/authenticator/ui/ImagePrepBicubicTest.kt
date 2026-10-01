package com.mikhail.authenticator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Увеличение бикубикой — чистая арифметика, поэтому проверяется без Android и без картинок:
 * важно, что оно не портит однородные поля и не съезжает по геометрии. Именно из-за геометрии
 * и гладкости декодер и терял мелкий код.
 */
class ImagePrepBicubicTest {

    @Test
    fun `без увеличения массив возвращается как есть`() {
        val gray = intArrayOf(0, 10, 20, 30)
        assertSame(gray, ImagePrep.upscaleBicubic(gray, 2, 2, 1))
    }

    @Test
    fun `размеры увеличиваются ровно в множитель`() {
        val gray = IntArray(4 * 3) { 128 }
        for (factor in 2..6) {
            val out = ImagePrep.upscaleBicubic(gray, 4, 3, factor)
            assertEquals(4 * factor * 3 * factor, out.size)
        }
    }

    @Test
    fun `однородное поле остаётся однородным`() {
        // Веса интерполяции нормируются, поэтому среднее по однородному полю — та же яркость.
        val gray = IntArray(5 * 5) { 200 }
        val out = ImagePrep.upscaleBicubic(gray, 5, 5, 4)
        assertTrue("появились пиксели вне исходной яркости: ${out.min()}..${out.max()}",
            out.all { it in 199..201 })
    }

    @Test
    fun `чёрно-белая граница остаётся границей, а не размазывается в ноль`() {
        // Слева чёрное, справа белое: после увеличения край обязан остаться контрастным —
        // именно этого не даёт билинейное сглаживание, и именно из-за него терялся код.
        val w = 8
        val h = 8
        val gray = IntArray(w * h) { i -> if (i % w < w / 2) 0 else 255 }
        val out = ImagePrep.upscaleBicubic(gray, w, h, 6)
        val nw = w * 6
        val middle = (h * 6 / 2) * nw
        val left = out[middle + 2]
        val right = out[middle + nw - 3]
        assertTrue("слева должно быть тёмное, получено $left", left < 40)
        assertTrue("справа должно быть светлое, получено $right", right > 215)
    }

    @Test
    fun `яркости не выходят за пределы диапазона`() {
        val gray = IntArray(6 * 6) { i -> if (i % 2 == 0) 0 else 255 }
        val out = ImagePrep.upscaleBicubic(gray, 6, 6, 5)
        assertTrue("есть значения вне 0..255", out.all { it in 0..255 })
    }

    @Test
    fun `разделяемое увеличение совпадает с прямым перебором`() {
        // Большое увеличение (×16) считается разделяемым способом — он вчетверо дешевле прямого
        // перебора 4×4 на каждый пиксель. Способы считают веса в разном порядке, поэтому
        // значения могут отличаться на единицу младшего разряда: сверяем, что расхождение
        // не выходит за один уровень яркости, иначе распознавание поедет молча.
        val w = 21
        val h = 17
        val gray = IntArray(w * h) { (it * 37 + 11) % 256 }
        for (factor in 2..6) {
            val direct = ImagePrep.upscaleBicubic(gray, w, h, factor)
            val fast = ImagePrep.upscaleBicubicBytes(gray, w, h, factor)
            assertEquals("размер не совпал при ×$factor", direct.size, fast.size)
            val worst = direct.indices.maxOf { kotlin.math.abs(direct[it] - (fast[it].toInt() and 0xFF)) }
            assertTrue("расхождение при ×$factor дошло до $worst уровней яркости", worst <= 1)
        }
    }

    @Test
    fun `без увеличения быстрое увеличение возвращает исходную яркость`() {
        val gray = IntArray(4 * 3) { it * 3 }
        val bytes = ImagePrep.upscaleBicubicBytes(gray, 4, 3, 1)
        assertEquals(gray.size, bytes.size)
        gray.forEachIndexed { i, v -> assertEquals(v, bytes[i].toInt() and 0xFF) }
    }
}
