
package com.lumina.hdr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Copies AAC audio into the HDR MP4 without re-encoding.
 * Stops at the end of the encoded video.
 */
class AudioTrackCopier(
    context: Context,
    audioUri: Uri
) : Closeable {

    private val extractor = MediaExtractor()
    val format: MediaFormat

    init {
        try {
            context.contentResolver
                .openFileDescriptor(audioUri, "r")!!.use {
                    extractor.setDataSource(it.fileDescriptor)
                }

            val track = (0 until extractor.trackCount)
                .firstOrNull {
                    extractor.getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME) ==
                        MediaFormat.MIMETYPE_AUDIO_AAC
                }

            require(track != null) {
                "Audio mesti menggunakan codec AAC. " +
                "MP3/WAV belum disokong."
            }

            format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    fun copyTo(
        muxer: MediaMuxer,
        muxerTrack: Int,
        videoDurationUs: Long,
        progress: (String) -> Unit
    ) {
        require(videoDurationUs > 0L)

        val maxSize = maxOf(
            1024 * 1024,
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            else 0
        )

        val buffer = ByteBuffer.allocateDirect(maxSize)
        val info = MediaCodec.BufferInfo()
        val firstPts = extractor.sampleTime
        if (firstPts < 0L) return

        var count = 0

        progress("Menyalin audio AAC...")

        while (true) {
            val pts = extractor.sampleTime
            if (pts < 0L) break

            val relativePts = pts - firstPts
            if (relativePts >= videoDurationUs) break

            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            require(size <= buffer.capacity()) {
                "Saiz sampel audio melebihi buffer"
            }

            buffer.position(0)
            buffer.limit(size)

            info.set(
                0,
                size,
                relativePts,
                extractor.sampleFlags and
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
            )

            muxer.writeSampleData(muxerTrack, buffer, info)
            count++
            extractor.advance()
        }

        progress("Audio selesai: $count sampel")
    }

    override fun close() {
        extractor.release()
    }
}
