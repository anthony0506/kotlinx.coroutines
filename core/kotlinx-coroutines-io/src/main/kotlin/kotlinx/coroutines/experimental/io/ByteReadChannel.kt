package kotlinx.coroutines.experimental.io

import kotlinx.coroutines.experimental.io.internal.*
import kotlinx.coroutines.experimental.io.packet.*

/**
 * Channel for asynchronous reading of sequences of bytes.
 * This is a **single-reader channel**.
 *
 * Operations on this channel cannot be invoked concurrently.
 */
public interface ByteReadChannel {
    /**
     * Returns number of bytes that can be read without suspension. Read operations do no suspend and return
     * immediately when this number is at least the number of bytes requested for read.
     */
    public val availableForRead: Int

    /**
     * Returns `true` if the channel is closed and no remaining bytes are available for read.
     * It implies that [availableForRead] is zero.
     */
    public val isClosedForRead: Boolean

    public val isClosedForWrite: Boolean

    /**
     * Byte order that is used for multi-byte read operations
     * (such as [readShort], [readInt], [readLong], [readFloat], and [readDouble]).
     */
    public var readByteOrder: ByteOrder

    /**
     * Number of bytes read from the channel.
     * It is not guaranteed to be atomic so could be updated in the middle of long running read operation.
     */
    public val totalBytesRead: Long

    /**
     * Reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
     * @return number of bytes were read or `-1` if the channel has been closed
     */
    suspend fun readAvailable(dst: ByteArray, offset: Int, length: Int): Int
    suspend fun readAvailable(dst: ByteArray) = readAvailable(dst, 0, dst.size)
    suspend fun readAvailable(dst: ByteBuffer): Int

    /**
     * Reads all [length] bytes to [dst] buffer or fails if channel has been closed.
     * Suspends if not enough bytes available.
     */
    suspend fun readFully(dst: ByteArray, offset: Int, length: Int)
    suspend fun readFully(dst: ByteArray) = readFully(dst, 0, dst.size)
    suspend fun readFully(dst: ByteBuffer): Int

    /**
     * Reads the specified amount of bytes and makes a byte packet from them. Fails if channel has been closed
     * and not enough bytes available. Accepts [headerSizeHint] to be provided, see [WritePacket].
     */
    suspend fun readPacket(size: Int, headerSizeHint: Int = 0): ByteReadPacket

    /**
     * Reads a long number (suspending if not enough bytes available) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readLong(): Long

    /**
     * Reads an int number (suspending if not enough bytes available) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readInt(): Int

    /**
     * Reads a short number (suspending if not enough bytes available) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readShort(): Short

    /**
     * Reads a byte (suspending if no bytes available yet) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readByte(): Byte

    /**
     * Reads a boolean value (suspending if no bytes available yet) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readBoolean(): Boolean

    /**
     * Reads double number (suspending if not enough bytes available) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readDouble(): Double

    /**
     * Reads float number (suspending if not enough bytes available) or fails if channel has been closed
     * and not enough bytes.
     */
    suspend fun readFloat(): Float

    /**
     * For every available bytes range invokes [visitor] function until it return false or end of stream encountered
     */
    suspend fun consumeEachBufferRange(visitor: (buffer: ByteBuffer, last: Boolean) -> Boolean)

    fun <R> lookAhead(visitor: LookAheadSession.() -> R): R
    suspend fun <R> lookAheadSuspend(visitor: suspend LookAheadSuspendSession.() -> R): R

    /**
     * Reads a line of UTF-8 characters to the specified [out] buffer up to [limit] characters.
     * Supports both CR-LF and LF line endings.
     * Throws an exception if the specified [limit] has been exceeded.
     *
     * @return `true` if line has been read (possibly empty) or `false` if channel has been closed
     * and no characters were read.
     */
    suspend fun <A : Appendable> readUTF8LineTo(out: A, limit: Int = Int.MAX_VALUE): Boolean
}

/**
 * Reads up to [limit] bytes from receiver channel and writes them to [dst] channel.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
suspend fun ByteReadChannel.copyTo(dst: ByteWriteChannel, limit: Long = Long.MAX_VALUE): Long {
    val buffer = BufferPool.borrow()
    try {
        var copied = 0L

        while (copied < limit) {
            buffer.clear()
            if (limit - copied < buffer.limit()) {
                buffer.limit((limit - copied).toInt())
            }
            val size = readAvailable(buffer)
            if (size == -1) break

            buffer.flip()
            dst.writeFully(buffer)
            copied += size
        }
        return copied
    } catch (t: Throwable) {
        dst.close(t)
        throw t
    } finally {
        BufferPool.recycle(buffer)
    }
}

/**
 * Reads all the bytes from receiver channel and writes them to [dst] channel and then closes it.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
suspend fun ByteReadChannel.copyAndClose(dst: ByteWriteChannel): Long {
    val count = copyTo(dst)
    dst.close()
    return count
}

/**
 * Reads all the bytes from receiver channel and builds a packet that is returned unless the specified [limit] exceeded.
 * It will simply stop reading and return packet of size [limit] in this case
 */
suspend fun ByteReadChannel.readRemaining(limit: Int = Int.MAX_VALUE): ByteReadPacket {
    val buffer = BufferPool.borrow()
    val packet = WritePacket()

    try {
        var copied = 0L

        while (copied < limit) {
            buffer.clear()
            if (limit - copied < buffer.limit()) {
                buffer.limit((limit - copied).toInt())
            }
            val size = readAvailable(buffer)
            if (size == -1) break

            buffer.flip()
            packet.writeFully(buffer)
            copied += size
        }

        return packet.build()
    } catch (t: Throwable) {
        packet.release()
        throw t
    } finally {
        BufferPool.recycle(buffer)
    }
}
