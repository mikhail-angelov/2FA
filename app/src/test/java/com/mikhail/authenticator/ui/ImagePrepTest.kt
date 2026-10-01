package com.mikhail.authenticator.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrepTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `яркость чёрного и белого`() {
        val gray = ImagePrep.luminance(intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255)))
        assertArrayEquals(intArrayOf(0, 255), gray)
    }

    @Test
    fun `яркость серого не зависит от канала`() {
        val grey = ImagePrep.luminance(intArrayOf(rgb(128, 128, 128)))
        assertEquals(128, grey[0])
    }

    @Test
    fun `порог Оцу делит две группы`() {
        val gray = IntArray(100) { if (it < 50) 20 else 230 }
        val t = ImagePrep.otsuThreshold(gray)
        assertTrue("порог $t должен лежать между 20 и 230", t in 20..230)
    }

    @Test
    fun `бинаризация даёт ровно два цвета`() {
        val out = ImagePrep.binarize(intArrayOf(10, 200, 128), ImagePrep.otsuThreshold(intArrayOf(10, 200, 128)))
        for (v in out) {
            assertTrue("пиксель должен быть чёрным или белым", v == ImagePrep.BLACK || v == ImagePrep.WHITE)
        }
    }

    @Test
    fun `бинаризация не инвертирует картинку`() {
        // Светлый фон должен остаться белым, тёмный код — чёрным.
        val gray = IntArray(64) { if (it < 8) 30 else 240 }
        val out = ImagePrep.binarize(gray, ImagePrep.otsuThreshold(gray))
        assertEquals(ImagePrep.BLACK, out[0])
        assertEquals(ImagePrep.WHITE, out[63])
    }

    @Test
    fun `поле вокруг картинки белое и размеры растут`() {
        val src = IntArray(4) { ImagePrep.BLACK }
        val out = ImagePrep.addWhiteBorder(src, 2, 2, 1)
        assertEquals(4 * 4, out.size)
        // углы и рамка — белые, центр — исходные чёрные пиксели
        assertEquals(ImagePrep.WHITE, out[0])
        assertEquals(ImagePrep.WHITE, out[3])
        assertEquals(ImagePrep.WHITE, out[12])
        assertEquals(ImagePrep.WHITE, out[15])
        assertEquals(ImagePrep.BLACK, out[5])
        assertEquals(ImagePrep.BLACK, out[10])
    }

    @Test
    fun `нулевое поле ничего не меняет`() {
        val src = IntArray(4) { 7 }
        assertArrayEquals(src, ImagePrep.addWhiteBorder(src, 2, 2, 0))
    }

    @Test
    fun `несовпадение размеров — ошибка, а не молчаливая порча`() {
        val failed = runCatching { ImagePrep.addWhiteBorder(IntArray(3), 2, 2, 1) }.isFailure
        assertTrue("ожидали исключение", failed)
    }
}
