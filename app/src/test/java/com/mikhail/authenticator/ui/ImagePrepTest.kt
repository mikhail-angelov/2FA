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
    fun `мелкий снимок увеличивается до обоих целевых размеров`() {
        assertEquals(listOf(952f / 238f, 1600f / 238f), ImagePrep.upscaleTargets(238))
    }

    @Test
    fun `увеличения вдвое не хватает — проверено на живом образце 238 px`() {
        // 476 px — это ровно то, что получалось бы при вдвое меньшем масштабе, и код на нём
        // ещё не читается; значит наименьший целевой коэффициент обязан быть больше двух.
        assertTrue("наименьший коэффициент должен быть > 2", ImagePrep.upscaleTargets(238).min() > 2f)
    }

    @Test
    fun `крупные картинки не растягиваются`() {
        assertEquals(listOf(1f), ImagePrep.upscaleTargets(1600))
        assertEquals(listOf(1f), ImagePrep.upscaleTargets(4000))
    }

    @Test
    fun `средняя картинка доводится только до большего размера`() {
        assertEquals(listOf(1600f / 1000f), ImagePrep.upscaleTargets(1000))
    }

    @Test
    fun `нулевой размер не приводит к делению на ноль`() {
        assertEquals(listOf(1f), ImagePrep.upscaleTargets(0))
    }

    @Test
    fun `несовпадение размеров — ошибка, а не молчаливая порча`() {
        val failed = runCatching { ImagePrep.addWhiteBorder(IntArray(3), 2, 2, 1) }.isFailure
        assertTrue("ожидали исключение", failed)
    }
}
