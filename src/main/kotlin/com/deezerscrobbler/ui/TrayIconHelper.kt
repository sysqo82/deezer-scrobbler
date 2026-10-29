package com.deezerscrobbler.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object TrayIconHelper {
    fun createTrayIconPainter(): Painter {
        val size = 64
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        // Background circle
        g2d.color = AwtColor(230, 0, 0) // Last.fm Red
        g2d.fillOval(2, 2, size - 4, size - 4)

        // Text 'd'
        g2d.color = AwtColor.WHITE
        g2d.font = Font("SansSerif", Font.BOLD, 38)
        val fm = g2d.fontMetrics
        val x = (size - fm.stringWidth("d")) / 2
        val y = (size - fm.height) / 2 + fm.ascent
        g2d.drawString("d", x, y)

        g2d.dispose()

        return BitmapPainter(image.toComposeImageBitmap())
    }
}
