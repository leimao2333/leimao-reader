package com.novelreader.core.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * WD14 模型的**推理**部分（v1.8.1.4）。
 *
 * 预处理与 SmilingWolf 官方口径一致：
 * 1. 封面按**白底补成正方形**（不裁切，短边两侧补白）；
 * 2. 缩放到 [Wd14TaggerSpec.INPUT_SIZE]（448）；
 * 3. float32、NHWC、取值 0~255、**BGR** 通道序（模型由 Keras 导出，RGB→BGR 是它吃的格式）。
 *
 * 一个 [Wd14Inference] 实例持有一个 [OrtSession]，可在多个协程间复用；
 * ORT 的 run 是线程安全的，但为省内存这里串行调用（每次 ~几百毫秒）。
 */
class Wd14Inference(modelFile: File, private val labels: Wd14Labels) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.absolutePath)

    val modelInputSize: Int = Wd14TaggerSpec.INPUT_SIZE

    /**
     * 对一张封面推理，返回 (danbooru 名, 概率) 列表 —— 只含 general / character
     * 且概率 ≥ [threshold] 的行，**未做受控词表映射**（调用方过 [Wd14Mapper]）。
     */
    fun tag(bitmap: Bitmap, threshold: Float = Wd14TaggerSpec.GENERAL_THRESHOLD): List<Pair<String, Float>> {
        val size = modelInputSize
        val input = preprocess(bitmap, size)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, size.toLong(), size.toLong(), 3))
        tensor.use {
            session.run(mapOf(inputName to it)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as Array<FloatArray>
                val probs = output[0]
                val out = ArrayList<Pair<String, Float>>(32)
                for (index in labels.usableIndices) {
                    if (index >= probs.size) continue
                    val p = probs[index]
                    if (p >= threshold) out += labels.row(index).name to p
                }
                return out
            }
        }
    }

    private val inputName: String by lazy { session.inputNames.first() }

    override fun close() {
        session.close()
    }

    companion object {

        /** 白底补正方形 + 缩放 + 展平为 BGR float 序列 */
        fun preprocess(src: Bitmap, size: Int = Wd14TaggerSpec.INPUT_SIZE): FloatArray {
            val side = maxOf(src.width, src.height).coerceAtLeast(1)
            val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(square)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, (side - src.width) / 2f, (side - src.height) / 2f, null)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== square) scaled.recycle()
            square.recycle()

            val out = FloatArray(size * size * 3)
            var i = 0
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                out[i++] = b.toFloat()
                out[i++] = g.toFloat()
                out[i++] = r.toFloat()
            }
            return out
        }

        /**
         * 把网络 / 本地封面字节解码成适合推理的位图（最长边 ≤ [maxSide]，
         * 用 inSampleSize 降采样，避免一张 2000px 封面占 30MB 内存）。
         */
        fun decodeScaled(bytes: ByteArray, maxSide: Int = 512): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
}
