@file:JvmName("IOUtil")

package rxhttp.wrapper.utils

import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * User: ljx
 * Date: 2016/11/15
 * Time: 15:31
 */
private const val LENGTH_BYTE = 8 * 1024 //一次性读写的字节个数，用于字节读取

@Throws(IOException::class)
internal fun InputStream.writeTo(
    outStream: OutputStream,
    progress: ((Long) -> Unit)? = null
): Boolean {
    return try {
        val bytes = ByteArray(LENGTH_BYTE)
        var totalReadLength: Long = 0
        var readLength: Int
        while (read(bytes, 0, bytes.size).also { readLength = it } != -1) {
            outStream.write(bytes, 0, readLength)
            progress?.apply {
                totalReadLength += readLength
                invoke(totalReadLength)
            }
        }
        true
    } finally {
        close(this, outStream)
    }
}

@Throws(IOException::class)
internal fun InputStream.writeToSpeed(
    speed: Int,
    outStream: OutputStream,
    progress: ((Long) -> Unit)? = null
): Boolean {
    return try {
        val bytes = ByteArray(LENGTH_BYTE)
        var totalReadLength: Long = 0 //累计下载大小
        var readLength: Int //读取大小

        var speedLimitLastTime: Long = System.currentTimeMillis() //记录上次限速的时间戳
        var speedLastDownloadSize: Long = 0 //记录上次限速下载的字节数
        while (read(bytes, 0, bytes.size).also { readLength = it } != -1) {
            outStream.write(bytes, 0, readLength)
            totalReadLength += readLength
            if (speed > 0) {//开启限速模式
                if ((totalReadLength - speedLastDownloadSize) > speed * 1024L) {//累计下载字节-上次记录的下载字节数
                    //累计下载字节-上次记录的下载字节数
                    val offsetTime = System.currentTimeMillis() - speedLimitLastTime
                    LogUtil.log("limit speed.sleep " + (1000 - offsetTime) + "ms")
                    //间隔时间小于单位时间，等待剩余的时间
                    if (offsetTime < 1000) {
                        SystemClock.sleep(1000 - offsetTime.coerceAtLeast(0))
                    }
                    speedLastDownloadSize = totalReadLength //记录限速下载的字节数
                    speedLimitLastTime = System.currentTimeMillis() //记录限速的时间戳
                }
            }
            progress?.apply {
                invoke(totalReadLength)
            }
        }
        true
    } finally {
        close(this, outStream)
    }
}

internal fun close(vararg closeables: Closeable?) {
    for (closeable in closeables) {
        if (closeable == null) continue
        try {
            closeable.close()
        } catch (ignored: IOException) {
        }
    }
}