package eu.kanade.tachiyomi.extension.all.lezhin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object LezhinDescrambler {

    fun descramble(input: ByteArray, episodeId: Int, numColAndRows: Int = 5): ByteArray {
        val bmp = try {
            BitmapFactory.decodeByteArray(input, 0, input.size)
        } catch (e: Throwable) {
            null
        } ?: return input

        val width = bmp.width
        val height = bmp.height

        // generate order
        val order = try { generateOrder(episodeId.toLong(), numColAndRows) } catch (_: Throwable) { null }
        if (order == null || order.isEmpty()) {
            bmp.recycle()
            return input
        }

        val pieces = try { calculatePieces(width, height, numColAndRows, order) } catch (_: Throwable) { emptyList() }

        val unscrambled = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            bmp.recycle()
            return input
        }

        val canvas = Canvas(unscrambled)

        for (p in pieces) {
            try {
                val from = p.from
                val to = p.to
                // Validate piece rects
                if (from.width <= 0 || from.height <= 0 || to.width <= 0 || to.height <= 0) continue
                if (from.left < 0 || from.top < 0) continue
                if (from.left + from.width > width || from.top + from.height > height) continue
                // Safely create bitmap slice
                val pieceBitmap = Bitmap.createBitmap(bmp, from.left, from.top, from.width, from.height)
                canvas.drawBitmap(pieceBitmap, to.left.toFloat(), to.top.toFloat(), null)
                pieceBitmap.recycle()
            } catch (_: Throwable) {
                // If any piece fails, abort descramble and return original input
                unscrambled.recycle()
                bmp.recycle()
                return input
            }
        }

        val os = ByteArrayOutputStream()
        val ok = unscrambled.compress(Bitmap.CompressFormat.PNG, 100, os)
        val out = if (ok) os.toByteArray() else input
        os.close()
        unscrambled.recycle()
        bmp.recycle()
        return out
    }

    private fun generateOrder(seed: Long, numColAndRows: Int): IntArray {
        val numPieces = numColAndRows * numColAndRows
        val order = IntArray(numPieces) { it }
        var state = seed and -1L
        fun rnd(t: Int): Int {
            var e = state
            e = e xor (e ushr 12)
            e = e xor ((e shl 25) and -1L)
            e = e xor (e ushr 27)
            state = e and -1L
            return (((e ushr 32) % t).toInt()).let { if (it < 0) -it else it }
        }
        for (i in order.indices) {
            val s = rnd(numPieces)
            val u = order[i]
            order[i] = order[s]
            order[s] = u
        }
        return order
    }

    private data class PieceRect(val left: Int, val top: Int, val width: Int, val height: Int)
    private data class Piece(val from: PieceRect, val to: PieceRect)

    private fun calculatePieces(imageW: Int, imageH: Int, numColAndRows: Int, scrambleTable: IntArray): List<Piece> {
        val arr = scrambleTable.toMutableList()
        arr.add(arr.size)
        arr.add(arr.size + 1)
        val arrayLength = max(1, kotlin.math.floor(sqrt(arr.size.toDouble())).toInt())
        val pieces = mutableListOf<Piece>()
        for (idx in arr.indices) {
            val fromIndex = idx
            val toIndex = arr[idx]
            val fromRect = computePieceRect(imageW, imageH, arrayLength, fromIndex) ?: continue
            val toRect = computePieceRect(imageW, imageH, arrayLength, toIndex) ?: continue
            pieces.add(Piece(fromRect, toRect))
        }
        return pieces
    }

    private fun computePieceRect(w: Int, h: Int, num: Int, pieceIndex: Int): PieceRect? {
        val numPieces = num * num
        return when {
            pieceIndex < numPieces -> {
                val pw = w / num
                val ph = h / num
                val left = (pieceIndex % num) * pw
                val top = (pieceIndex / num) * ph
                if (pw <= 0 || ph <= 0) return null
                PieceRect(left, top, pw, ph)
            }
            pieceIndex == numPieces -> {
                val remW = w % num
                if (remW == 0) null else PieceRect(w - remW, 0, remW, h)
            }
            else -> {
                val remH = h % num
                if (remH == 0) null else PieceRect(0, h - remH, w - w % num, remH)
            }
        }
    }
}
