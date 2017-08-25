@file:Suppress("UsePropertyAccessSyntax") // for ByteBuffer.getShort/getInt/etc

package kotlinx.coroutines.experimental.io

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.internal.*
import kotlinx.coroutines.experimental.io.packet.*
import java.nio.*
import java.util.concurrent.atomic.*

// implementation for ByteChannel
internal class ByteBufferChannel(
    override val autoFlush: Boolean,
    private val pool: ObjectPool<ReadWriteBufferState.Initial> = BufferObjectPool,
    private val reservedSize: Int = RESERVED_SIZE
) : ByteChannel {
    // internal constructor for reading of byte buffers
    constructor(content: ByteBuffer) : this(false, BufferObjectNoPool, 0) {
        state = ReadWriteBufferState.Initial(content.slice(), 0).apply {
            capacity.resetForRead()
        }.startWriting()
        restoreStateAfterWrite()
        close()
        tryTerminate()
    }

    @Volatile
    private var state: ReadWriteBufferState = ReadWriteBufferState.IdleEmpty

    @Volatile
    private var closed: ClosedElement? = null

    @Volatile
    private var readOp: CancellableContinuation<Boolean>? = null

    @Volatile
    private var writeOp: CancellableContinuation<Unit>? = null

    private var readPosition = 0
    private var writePosition = 0

    override var readByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
    override var writeByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

    override val availableForRead: Int
        get() = state.capacity.availableForRead

    override val availableForWrite: Int
        get() = state.capacity.availableForWrite

    override val isClosedForRead: Boolean
        get() = state === ReadWriteBufferState.Terminated

    override val isClosedForWrite: Boolean
        get() = closed != null

    override fun close(cause: Throwable?): Boolean {
        if (closed != null) return false
        val newClosed = if (cause == null) ClosedElement.EmptyCause else ClosedElement(cause)
        if (!Closed.compareAndSet(this, null, newClosed)) return false
        flush()
        if (state.capacity.isEmpty() || cause != null) tryTerminate()
        resumeClosed(cause)
        return true
    }

    override fun flush() {
        if (!state.capacity.flush()) return
        resumeReadOp()
        if (availableForWrite > 0) resumeWriteOp()
    }

    private fun ByteBuffer.prepareBuffer(order: ByteOrder, position: Int, available: Int) {
        require(position >= 0)
        require(available >= 0)

        val bufferLimit = capacity() - reservedSize
        val virtualLimit = position + available

        order(order)
        limit(virtualLimit.coerceAtMost(bufferLimit))
        position(position)
    }

    private fun setupStateForWrite(): ByteBuffer {
        var _allocated: ReadWriteBufferState.Initial? = null
        val (old, newState) = updateState { state ->
            when (state) {
                ReadWriteBufferState.IdleEmpty -> {
                    val allocated = _allocated ?: newBuffer().also { _allocated = it }
                    allocated.startWriting()
                }
                ReadWriteBufferState.Terminated -> throw closed!!.sendException
                else -> {
                    state.startWriting()
                }
            }
        }
        val buffer = newState.writeBuffer

        _allocated?.let { allocated ->
            if (old !== ReadWriteBufferState.IdleEmpty) {
                releaseBuffer(allocated)
            }
        }
        return buffer.apply {
            prepareBuffer(writeByteOrder, writePosition, newState.capacity.availableForWrite)
        }
    }

    private fun restoreStateAfterWrite() {
        var toRelease: ReadWriteBufferState.IdleNonEmpty? = null

        val (_, newState) = updateState {
            val writeStopped = it.stopWriting()
            if (writeStopped is ReadWriteBufferState.IdleNonEmpty && writeStopped.capacity.isEmpty()) {
                toRelease = writeStopped
                ReadWriteBufferState.IdleEmpty
            } else {
                writeStopped
            }
        }

        if (newState === ReadWriteBufferState.IdleEmpty) {
            toRelease?.let { releaseBuffer(it.initial) }
        }
    }

    private fun setupStateForRead(): ByteBuffer? {
        val (_, newState) = updateState { state ->
            when (state) {
                ReadWriteBufferState.Terminated -> closed!!.cause?.let { throw it } ?: return null
                ReadWriteBufferState.IdleEmpty -> closed?.cause?.let { throw it } ?: return null
                else -> {
                    if (state.capacity.availableForRead == 0) return null
                    state.startReading()
                }
            }
        }

        return newState.readBuffer.apply {
            prepareBuffer(readByteOrder, readPosition, newState.capacity.availableForRead)
        }
    }

    private fun restoreStateAfterRead() {
        var toRelease: ReadWriteBufferState.IdleNonEmpty? = null

        val (_, newState) = updateState { state ->
            toRelease?.let {
                it.capacity.resetForWrite()
                resumeWriteOp()
                toRelease = null
            }

            val readStopped = state.stopReading()

            if (readStopped is ReadWriteBufferState.IdleNonEmpty) {
                if (this.state === state && readStopped.capacity.tryLockForRelease()) {
                    toRelease = readStopped
                    ReadWriteBufferState.IdleEmpty
                } else {
                    readStopped
                }
            } else {
                readStopped
            }
        }

        if (newState === ReadWriteBufferState.IdleEmpty) {
            toRelease?.let { releaseBuffer(it.initial) }
            resumeWriteOp()
        }
    }

    private fun tryTerminate() {
        val closed = closed ?: return
        var toRelease: ReadWriteBufferState.Initial? = null

        updateState { state ->
            when {
                state === ReadWriteBufferState.IdleEmpty -> ReadWriteBufferState.Terminated
                closed.cause != null && state is ReadWriteBufferState.IdleNonEmpty -> {
                    toRelease = state.initial
                    ReadWriteBufferState.Terminated
                }
                else -> return
            }
        }

        toRelease?.let { buffer ->
            if (state === ReadWriteBufferState.Terminated) {
                releaseBuffer(buffer)
            }
        }

        WriteOp.getAndSet(this, null)?.resumeWithException(closed.sendException)
        ReadOp.getAndSet(this, null)?.apply {
            if (closed.cause != null) resumeWithException(closed.cause) else resume(false)
        }
    }

    private fun ByteBuffer.carryIndex(idx: Int) = if (idx >= capacity() - reservedSize) idx - (capacity() - reservedSize) else idx

    private inline fun writing(block: ByteBuffer.(RingBufferCapacity) -> Unit) {
        val buffer = setupStateForWrite()
        val capacity = state.capacity
        try {
            closed?.let { throw it.sendException }
            block(buffer, capacity)
        } finally {
            if (capacity.isFull() || autoFlush) flush()
            restoreStateAfterWrite()
            tryTerminate()
        }
    }

    private inline fun reading(block: ByteBuffer.(RingBufferCapacity) -> Boolean): Boolean {
        val buffer = setupStateForRead() ?: return false
        val capacity = state.capacity
        try {
            if (capacity.availableForRead == 0) return false

            return block(buffer, capacity)
        } finally {
            restoreStateAfterRead()
            tryTerminate()
        }
    }

    private fun readAsMuchAsPossible(dst: ByteBuffer, consumed0: Int = 0): Int {
        var consumed = 0

        val rc = reading {
            val part = it.tryReadAtMost(minOf(remaining(), dst.remaining()))
            if (part > 0) {
                consumed += part

                if (dst.remaining() >= remaining()) {
                    dst.put(this)
                } else {
                    repeat(part) {
                        dst.put(get())
                    }
                }

                bytesRead(it, part)
                true
            } else {
                false
            }
        }

        return if (rc && dst.hasRemaining() && state.capacity.availableForRead > 0)
            readAsMuchAsPossible(dst, consumed0 + consumed)
        else consumed + consumed0
    }

    private fun readAsMuchAsPossible(dst: ByteArray, offset: Int, length: Int, consumed0: Int = 0): Int {
        var consumed = 0

        val rc = reading {
            val part = it.tryReadAtMost(minOf(remaining(), length))
            if (part > 0) {
                consumed += part
                get(dst, offset, part)

                bytesRead(it, part)
                true
            } else {
                false
            }
        }

        return if (rc && consumed < length && state.capacity.availableForRead > 0)
            readAsMuchAsPossible(dst, offset + consumed, length - consumed, consumed0 + consumed)
        else consumed + consumed0
    }

    suspend override fun readFully(dst: ByteArray, offset: Int, length: Int) {
        val consumed = readAsMuchAsPossible(dst, offset, length)

        if (consumed < length) {
            readFullySuspend(dst, offset + consumed, length - consumed)
        }
    }

    suspend override fun readFully(dst: ByteBuffer): Int {
        val rc = readAsMuchAsPossible(dst)
        if (!dst.hasRemaining()) return rc

        return readFullySuspend(dst, rc)
    }

    private suspend fun readFullySuspend(dst: ByteBuffer, rc0: Int): Int {
        var copied = rc0

        while (dst.hasRemaining()) {
            if (!readSuspend(1)) throw ClosedReceiveChannelException("Unexpected EOF: expected ${dst.remaining()} more bytes")
            copied += readAsMuchAsPossible(dst)
        }

        return copied
    }

    private suspend tailrec fun readFullySuspend(dst: ByteArray, offset: Int, length: Int) {
        if (!readSuspend(1)) throw ClosedReceiveChannelException("Unexpected EOF: expected $length more bytes")

        val consumed = readAsMuchAsPossible(dst, offset, length)

        if (consumed < length) {
            readFullySuspend(dst, offset + consumed, length - consumed)
        }
    }

    suspend override fun readAvailable(dst: ByteArray, offset: Int, length: Int): Int {
        val consumed = readAsMuchAsPossible(dst, offset, length)

        return when {
            consumed == 0 && closed != null -> -1
            consumed > 0 || length == 0 -> consumed
            else -> readAvailableSuspend(dst, offset, length)
        }
    }

    suspend override fun readAvailable(dst: ByteBuffer): Int {
        val consumed = readAsMuchAsPossible(dst)

        return when {
            consumed == 0 && closed != null -> -1
            consumed > 0 || !dst.hasRemaining() -> consumed
            else -> readAvailableSuspend(dst)
        }
    }

    private suspend fun readAvailableSuspend(dst: ByteArray, offset: Int, length: Int): Int {
        if (!readSuspend(1)) return -1
        return readAvailable(dst, offset, length)
    }

    private suspend fun readAvailableSuspend(dst: ByteBuffer): Int {
        if (!readSuspend(1)) return -1
        return readAvailable(dst)
    }

    suspend override fun readPacket(size: Int): ByteReadPacket {
        closed?.cause?.let { throw it }

        if (size == 0) return ByteReadPacketEmpty

        val builder = ByteWritePacketImpl(BufferPool)
        val buffer = BufferPool.borrow()

        try {
            var remaining = size
            while (remaining > 0) {
                buffer.clear()
                if (buffer.remaining() > remaining) {
                    buffer.limit(remaining)
                }

                val rc = readFully(buffer)
                buffer.flip()
                builder.writeFully(buffer)

                remaining -= rc
            }

            return builder.build()
        } catch (t: Throwable) {
            builder.release()
            throw t
        } finally {
            BufferPool.recycle(buffer)
        }
    }

    suspend override fun readByte(): Byte {
        var b: Byte = 0

        val rc = reading {
            if (it.tryReadExact(1)) {
                b = get()
                bytesRead(it, 1)
                true
            } else false
        }

        return if (rc) {
            b
        } else {
            readByteSuspend()
        }
    }

    private suspend fun readByteSuspend(): Byte {
        if (!readSuspend(1)) throw ClosedReceiveChannelException("EOF: one byte required")
        return readByte()
    }

    suspend override fun readBoolean(): Boolean {
        var b = false

        val rc = reading {
            if (it.tryReadExact(1)) {
                b = get() != 0.toByte()
                bytesRead(it, 1)
                true
            } else false
        }

        return if (rc) {
            b
        } else {
            readBooleanSuspend()
        }
    }

    private suspend fun readBooleanSuspend(): Boolean {
        if (!readSuspend(1)) throw ClosedReceiveChannelException("EOF: one byte required")
        return readBoolean()
    }

    suspend override fun readShort(): Short {
        var sh: Short = 0

        val rc = reading {
            if (it.tryReadExact(2)) {
                if (remaining() < 2) rollBytes(2)
                sh = getShort()
                bytesRead(it, 2)
                true
            } else false
        }

        return if (rc) {
            sh
        } else {
            readShortSuspend()
        }
    }

    private suspend fun readShortSuspend(): Short {
        if (!readSuspend(2)) throw ClosedReceiveChannelException("EOF while byte expected")
        return readShort()
    }

    suspend override fun readInt(): Int {
        var i = 0

        val rc = reading {
            if (it.tryReadExact(4)) {
                if (remaining() < 4) rollBytes(4)
                i = getInt()
                bytesRead(it, 4)
                true
            } else false
        }

        return if (rc) {
            i
        } else {
            readIntSuspend()
        }
    }

    private suspend fun readIntSuspend(): Int {
        if (!readSuspend(4)) throw ClosedReceiveChannelException("EOF while an int expected")
        return readInt()
    }

    suspend override fun readLong(): Long {
        var i = 0L

        val rc = reading {
            if (it.tryReadExact(8)) {
                if (remaining() < 8) rollBytes(8)
                i = getLong()
                bytesRead(it, 8)
                true
            } else false
        }

        return if (rc) {
            i
        } else {
            readLongSuspend()
        }
    }

    private suspend fun readLongSuspend(): Long {
        if (!readSuspend(8)) throw ClosedReceiveChannelException("EOF while a long expected")
        return readLong()
    }

    suspend override fun readDouble(): Double {
        var d = 0.0

        val rc = reading {
            if (it.tryReadExact(8)) {
                if (remaining() < 8) rollBytes(8)
                d = getDouble()
                bytesRead(it, 8)
                true
            } else false
        }

        return if (rc) {
            d
        } else {
            readDoubleSuspend()
        }
    }

    private suspend fun readDoubleSuspend(): Double {
        if (!readSuspend(8)) throw ClosedReceiveChannelException("EOF while a double expected")
        return readDouble()
    }

    suspend override fun readFloat(): Float {
        var f = 0.0f

        val rc = reading {
            if (it.tryReadExact(4)) {
                if (remaining() < 4) rollBytes(4)
                f = getFloat()
                bytesRead(it, 4)
                true
            } else false
        }

        return if (rc) {
            f
        } else {
            readFloatSuspend()
        }
    }

    private suspend fun readFloatSuspend(): Float {
        if (!readSuspend(4)) throw ClosedReceiveChannelException("EOF while an int expected")
        return readFloat()
    }

    private fun ByteBuffer.rollBytes(n: Int) {
        limit(position() + n)
        for (i in 1..n - remaining()) {
            put(capacity() + ReservedLongIndex + i, get(i))
        }
    }

    private fun ByteBuffer.carry() {
        val base = capacity() - reservedSize
        for (i in base until position()) {
            put(i - base, get(i))
        }
    }

    private fun ByteBuffer.bytesWritten(c: RingBufferCapacity, n: Int) {
        require(n >= 0)

        writePosition = carryIndex(writePosition + n)
        c.completeWrite(n)
    }

    private fun ByteBuffer.bytesRead(c: RingBufferCapacity, n: Int) {
        require(n >= 0)

        readPosition = carryIndex(readPosition + n)
        c.completeRead(n)
        resumeWriteOp()
    }

    suspend override fun writeByte(b: Byte) {
        writing {
            tryWriteByte(b, it)
        }
    }

    private suspend fun ByteBuffer.tryWriteByte(b: Byte, c: RingBufferCapacity) {
        if (c.tryWriteExact(1)) {
            put(b)
            bytesWritten(c, 1)
        } else {
            writeByteSuspend(b, c)
        }
    }

    private suspend fun ByteBuffer.writeByteSuspend(b: Byte, c: RingBufferCapacity) {
        writeSuspend(1)
        tryWriteByte(b, c)
    }

    suspend override fun writeShort(s: Short) {
        writing {
            if (!tryWriteShort(s, it)) {
                writeShortSuspend(s, it)
            }
        }
    }

    private suspend fun ByteBuffer.writeShortSuspend(s: Short, c: RingBufferCapacity) {
        writeSuspend(2)
        tryWriteShort(s, c)
    }

    private fun ByteBuffer.tryWriteShort(s: Short, c: RingBufferCapacity): Boolean {
        if (c.tryWriteExact(2)) {
            if (remaining() < 2) {
                limit(capacity())
                putShort(s)
                carry()
            } else {
                putShort(s)
            }

            bytesWritten(c, 2)
            return true
        }

        return false
    }

    private fun ByteBuffer.tryWriteInt(i: Int, c: RingBufferCapacity): Boolean {
        if (c.tryWriteExact(4)) {
            if (remaining() < 4) {
                limit(capacity())
                putInt(i)
                carry()
            } else {
                putInt(i)
            }

            bytesWritten(c, 4)
            return true
        }

        return false
    }

    suspend override fun writeInt(i: Int) {
        writing {
            if (!tryWriteInt(i, it)) {
                writeIntSuspend(i, it)
            }
        }
    }

    private suspend fun ByteBuffer.writeIntSuspend(i: Int, c: RingBufferCapacity) {
        writeSuspend(4)
        tryWriteInt(i, c)
    }

    private fun ByteBuffer.tryWriteLong(l: Long, c: RingBufferCapacity): Boolean {
        if (c.tryWriteExact(8)) {
            if (remaining() < 8) {
                limit(capacity())
                putLong(l)
                carry()
            } else {
                putLong(l)
            }

            bytesWritten(c, 8)
            return true
        }

        return false
    }

    suspend override fun writeLong(l: Long) {
        writing {
            if (!tryWriteLong(l, it)) {
                writeLongSuspend(l, it)
            }
        }
    }

    private suspend fun ByteBuffer.writeLongSuspend(l: Long, c: RingBufferCapacity) {
        writeSuspend(8)
        tryWriteLong(l, c)
    }

    suspend override fun writeDouble(d: Double) {
        writeLong(java.lang.Double.doubleToRawLongBits(d))
    }

    suspend override fun writeFloat(f: Float) {
        writeInt(java.lang.Float.floatToRawIntBits(f))
    }

    suspend override fun writeAvailable(src: ByteBuffer): Int {
        val copied = writeAsMuchAsPossible(src)
        if (copied > 0) return copied

        return writeLazySuspend(src)
    }

    private suspend fun writeLazySuspend(src: ByteBuffer): Int {
        while (true) {
            writeSuspend(1)
            val copied = writeAvailable(src)
            if (copied > 0) return copied
        }
    }

    suspend override fun writeFully(src: ByteBuffer) {
        writeAsMuchAsPossible(src)
        if (!src.hasRemaining()) return

        return writeFullySuspend(src)
    }

    private suspend fun writeFullySuspend(src: ByteBuffer) {
        while (src.hasRemaining()) {
            writeSuspend(1)
            writeAsMuchAsPossible(src)
        }
    }

    private fun writeAsMuchAsPossible(src: ByteBuffer): Int {
        writing {
            var written = 0

            do {
                val possibleSize = it.tryWriteAtMost(minOf(src.remaining(), remaining()))
                if (possibleSize == 0) break
                require(possibleSize > 0)

                if (remaining() >= src.remaining()) {
                    put(src)
                } else {
                    repeat(possibleSize) {
                        put(src.get())
                    }
                }

                written += possibleSize

                prepareBuffer(writeByteOrder, carryIndex(writePosition + written), it.availableForWrite)
            } while (true)

            bytesWritten(it, written)

            return written
        }

        return 0
    }

    private fun writeAsMuchAsPossible(src: ByteArray, offset: Int, length: Int): Int {
        writing {
            var written = 0

            do {
                val possibleSize = it.tryWriteAtMost(minOf(length - written, remaining()))
                if (possibleSize == 0) break
                require(possibleSize > 0)

                put(src, offset + written, possibleSize)
                written += possibleSize

                prepareBuffer(writeByteOrder, carryIndex(writePosition + written), it.availableForWrite)
            } while (true)

            bytesWritten(it, written)

            return written
        }

        return 0
    }

    suspend override fun writeFully(src: ByteArray, offset: Int, length: Int) {
        var rem = length
        var off = offset

        while (rem > 0) {
            val s = writeAsMuchAsPossible(src, off, rem)
            if (s == 0) break

            off += s
            rem -= s
        }

        if (rem == 0) return

        writeFullySuspend(src, off, rem)
    }

    private tailrec suspend fun writeFullySuspend(src: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val copied = writeAvailable(src, offset, length)
        return writeFullySuspend(src, offset + copied, length - copied)
    }

    suspend override fun writeAvailable(src: ByteArray, offset: Int, length: Int): Int {
        val size = writeAsMuchAsPossible(src, offset, length)
        if (size > 0) return size
        return writeSuspend(src, offset, length)
    }

    private suspend fun writeSuspend(src: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            writeSuspend(1)
            val size = writeAsMuchAsPossible(src, offset, length)
            if (size > 0) return size
        }
    }

    suspend override fun writePacket(packet: ByteReadPacket) {
        closed?.sendException?.let { packet.release(); throw it }

        when (packet) {
            is ByteReadPacketEmpty -> return
            is ByteReadPacketSingle -> writeSingleBufferPacket(packet)
            is ByteReadPacketImpl -> writeMultipleBufferPacket(packet)
            else -> writeExternalPacket(packet)
        }
    }

    private suspend fun writeSingleBufferPacket(packet: ByteReadPacketSingle) {
        val buffer = packet.steal()
        val t = try {
            writeAsMuchAsPossible(buffer)
            null
        } catch (t: Throwable) {
            t
        }

        if (t != null) {
            packet.pool.recycle(buffer)
            throw t
        }

        if (buffer.hasRemaining()) {
            return writeSingleBufferPacketSuspend(buffer, packet)
        }

        packet.pool.recycle(buffer)
    }

    private suspend fun writeMultipleBufferPacket(packet: ByteReadPacketImpl) {
        var buffer: ByteBuffer? = null

        val t = try {
            while (packet.remaining > 0) {
                buffer = packet.steal()
                writeAsMuchAsPossible(buffer)
                if (buffer.hasRemaining()) break
                packet.pool.recycle(buffer)
            }
            null
        } catch (t: Throwable) { t }

        if (t != null) {
            buffer?.let { packet.pool.recycle(it) }
            packet.release()
            throw t
        }

        if (buffer != null) {
            return writeMultipleBufferPacketSuspend(buffer, packet)
        }

        packet.release()
    }

    private suspend fun writeSingleBufferPacketSuspend(buffer: ByteBuffer, packet: ByteReadPacketSingle) {
        try {
            writeFully(buffer)
        } finally {
            packet.pool.recycle(buffer)
        }
    }

    private suspend fun writeMultipleBufferPacketSuspend(rem: ByteBuffer, packet: ByteReadPacketImpl) {
        var buffer = rem

        try {
            do {
                writeFully(buffer)
                if (packet.remaining == 0) break
                packet.pool.recycle(buffer)
                buffer = packet.steal()
            } while (true)
        } finally {
            packet.pool.recycle(buffer)
            packet.release()
        }
    }

    private suspend fun writeExternalPacket(packet: ByteReadPacket) {
        val buffer = BufferPool.borrow()
        val t = try {
            while (packet.remaining > 0) {
                buffer.clear()
                packet.readLazy(buffer)
                buffer.flip()
                writeAsMuchAsPossible(buffer)
                if (buffer.hasRemaining()) {
                    buffer.compact()
                    break
                }
            }

            null
        } catch (t: Throwable) {
            t
        }

        buffer.flip()
        if (buffer.hasRemaining()) {
            return writeExternalPacketSuspend(buffer, packet)
        }

        BufferPool.recycle(buffer)
        packet.release()

        if (t != null) throw t
    }

    private suspend fun writeExternalPacketSuspend(buffer: ByteBuffer, packet: ByteReadPacket) {
        try {
            do {
                buffer.compact()
                packet.readLazy(buffer)
                buffer.flip()
                writeFully(buffer)
            } while (packet.remaining > 0)
        } finally {
            BufferPool.recycle(buffer)
            packet.release()
        }
    }

    /**
     * Invokes [visitor] for every available batch until all bytes processed or visitor if visitor returns false.
     * Never invokes [visitor] with empty buffer unless [last] = true. Invokes visitor with last = true at most once
     * even if there are remaining bytes and visitor returned true.
     */
    override suspend fun lookAhead(visitor: (buffer: ByteBuffer, last: Boolean) -> Boolean) {
        if (lookAheadFast(false, visitor)) return
        lookAheadSuspend(visitor)
    }

    private inline fun lookAheadFast(last: Boolean, visitor: (buffer: ByteBuffer, last: Boolean) -> Boolean): Boolean {
        if (state === ReadWriteBufferState.Terminated && !last) return false

        val rc = reading {
            do {
                val available = state.capacity.availableForRead

                val rem = if (available > 0 || last) {
                    if (!visitor(this, last)) {
                        afterBufferVisited(this, it)
                        return true
                    }

                    val consumed = afterBufferVisited(this, it)
                    available - consumed
                } else 0
            } while (rem > 0 && !last)

            last
        }

        if (!rc && closed != null) {
            visitor(EmptyByteBuffer, true)
        }

        return rc
    }

    private suspend fun lookAheadSuspend(visitor: (buffer: ByteBuffer, last: Boolean) -> Boolean): Boolean {
        var last = false

        do {
            if (lookAheadFast(last, visitor)) return true
            if (last) return false
            if (!readSuspend(1)) {
                last = true
            }
        } while (true)
    }

    private fun afterBufferVisited(buffer: ByteBuffer, c: RingBufferCapacity): Int {
        val consumed = buffer.position() - readPosition
        if (consumed > 0) {
            if (!c.tryReadExact(consumed)) throw IllegalStateException("Consumed more bytes than available")

            buffer.bytesRead(c, consumed)
            buffer.prepareBuffer(readByteOrder, readPosition, c.availableForRead)
        }

        return consumed
    }

    private suspend fun readUTF8LineToAscii(out: Appendable, limit: Int): Boolean {
        if (state === ReadWriteBufferState.Terminated) return false

        var cr = false
        var consumed = 0
        var unicodeStarted = false
        var eol = false

        lookAheadFast(false) { buffer, last ->
            var forceConsume = false

            val rejected = !buffer.decodeASCII { ch ->
                when {
                    ch == '\r' -> {
                        cr = true
                        true
                    }
                    ch == '\n' -> {
                        eol = true
                        forceConsume = true
                        false
                    }
                    cr -> {
                        cr = false
                        eol = true
                        false
                    }
                    else -> {
                        if (consumed == limit) throw BufferOverflowException()
                        consumed++
                        out.append(ch)
                        true
                    }
                }
            }

            if (cr && last) {
                eol = true
            }

            if (eol && forceConsume) {
                buffer.position(buffer.position() + 1)
            }

            if (rejected && buffer.hasRemaining() && !eol) {
                unicodeStarted = true
                false
            } else
                !eol && !last
        }

        if (eol && !unicodeStarted) return true
        return readUTF8LineToUtf8(out, limit - consumed, cr, consumed)
    }

    private suspend fun readUTF8LineToUtf8(out: Appendable, limit: Int, cr0: Boolean, consumed0: Int): Boolean {
        var cr1 = cr0
        var consumed1 = 0
        var eol = false

        lookAheadFast(false) { buffer, last ->
            var forceConsume = false

            val rc = buffer.decodeUTF8 { ch ->
                when {
                    ch == '\r' -> {
                        cr1 = true
                        true
                    }
                    ch == '\n' -> {
                        eol = true
                        forceConsume = true
                        false
                    }
                    cr1 -> {
                        cr1 = false
                        eol = true
                        false
                    }
                    else -> {
                        if (consumed1 == limit) throw BufferOverflowException()
                        consumed1++
                        out.append(ch)
                        true
                    }
                }
            }

            if (cr1 && last) {
                eol = true
            }

            if (eol && forceConsume) {
                buffer.position(buffer.position() + 1)
            }

            rc != 0 && !eol && !last
        }

        if (eol) return true

        return readUTF8LineToUtf8Suspend(out, limit, cr1, consumed1 + consumed0)
    }

    private suspend fun readUTF8LineToUtf8Suspend(out: Appendable, limit: Int, cr0: Boolean, consumed0: Int): Boolean {
        var cr1 = cr0
        var consumed1 = 0
        var eol = false
        var wrap = 0

        lookAheadSuspend { buffer, last ->
            var forceConsume = false

            val rc = buffer.decodeUTF8 { ch ->
                when {
                    ch == '\r' -> {
                        cr1 = true
                        true
                    }
                    ch == '\n' -> {
                        eol = true
                        forceConsume = true
                        false
                    }
                    cr1 -> {
                        cr1 = false
                        eol = true
                        false
                    }
                    else -> {
                        if (consumed1 == limit) throw BufferOverflowException()
                        consumed1++
                        out.append(ch)
                        true
                    }
                }
            }

            if (cr1 && last) {
                eol = true
            }

            if (eol && forceConsume) {
                buffer.position(buffer.position() + 1)
            }

            wrap = maxOf(0, rc)

            wrap == 0 && !eol && !last
        }

        if (wrap != 0) {
            if (!readSuspend(wrap)) {

            }

            return readUTF8LineToUtf8Suspend(out, limit, cr1, consumed1)
        }

        return (consumed1 > 0 || consumed0 > 0 || eol)
    }

    suspend override fun <A : Appendable> readUTF8LineTo(out: A, limit: Int) = readUTF8LineToAscii(out, limit)

    private fun resumeReadOp() {
        ReadOp.getAndSet(this, null)?.resume(true)
    }

    private fun resumeWriteOp() {
        WriteOp.getAndSet(this, null)?.apply {
            val closed = closed
            if (closed == null) resume(Unit) else resumeWithException(closed.sendException)
        }
    }

    private fun resumeClosed(cause: Throwable?) {
        ReadOp.getAndSet(this, null)?.let { c ->
            if (cause != null)
                c.resumeWithException(cause)
            else
                c.resume(state.capacity.availableForRead > 0)
        }

        WriteOp.getAndSet(this, null)?.tryResumeWithException(cause ?: ClosedSendChannelException(null))
    }

    private tailrec suspend fun readSuspend(size: Int): Boolean {
        if (state.capacity.availableForRead >= size) return true

        closed?.let { c ->
            if (c.cause == null) return false
            throw c.cause
        }

        if (!readSuspendImpl(size)) return false

        return readSuspend(size)
    }

    private suspend fun readSuspendImpl(size: Int): Boolean {
        if (state.capacity.availableForRead >= size) return true

        return suspendCancellableCoroutine(holdCancellability = true) { c ->
            do {
                if (state.capacity.availableForRead >= size) {
                    c.resume(true)
                    break
                }

                closed?.let {
                    if (it.cause == null && state.capacity.availableForRead == 0) {
                        c.resume(false)
                        return@suspendCancellableCoroutine
                    } else if (it.cause != null) {
                        c.resumeWithException(it.cause)
                        return@suspendCancellableCoroutine
                    }
                }
            } while (!setContinuation({ readOp }, ReadOp, c, { closed == null && state.capacity.availableForRead < size }))
        }
    }

    private suspend fun writeSuspend(size: Int) {
        closed?.sendException?.let { throw it }

        while (state.capacity.availableForWrite < size && state !== ReadWriteBufferState.IdleEmpty && closed == null) {
            suspendCancellableCoroutine<Unit>(holdCancellability = true) { c ->
                do {
                    closed?.sendException?.let { throw it }
                    if (state.capacity.availableForWrite >= size || state === ReadWriteBufferState.IdleEmpty) {
                        c.resume(Unit)
                        break
                    }
                } while (!setContinuation({ writeOp }, WriteOp, c, { closed == null && state.capacity.availableForWrite < size && state !== ReadWriteBufferState.IdleEmpty }))

                flush()
            }
        }

        closed?.sendException?.let { throw it }
    }

    private inline fun <T, C : CancellableContinuation<T>> setContinuation(getter: () -> C?, updater: AtomicReferenceFieldUpdater<ByteBufferChannel, C?>, continuation: C, predicate: () -> Boolean): Boolean {
        while (true) {
            val current = getter()
            if (current != null) throw IllegalStateException("Operation is already in progress")

            if (!predicate()) {
                return false
            }

            if (updater.compareAndSet(this, null, continuation)) {
                if (predicate() || !updater.compareAndSet(this, continuation, null)) {
                    continuation.initCancellability()
                    return true
                }

                return false
            }
        }
    }

    private fun newBuffer(): ReadWriteBufferState.Initial {
        val result = pool.borrow()

        result.readBuffer.order(readByteOrder)
        result.writeBuffer.order(writeByteOrder)
        result.capacity.resetForWrite()

        return result
    }

    private fun releaseBuffer(buffer: ReadWriteBufferState.Initial) {
        pool.recycle(buffer)
    }

    // todo: replace with atomicfu
    private inline fun updateState(block: (ReadWriteBufferState) -> ReadWriteBufferState?):
        Pair<ReadWriteBufferState, ReadWriteBufferState> = update({ state }, State, block)

    // todo: replace with atomicfu
    private inline fun <T : Any> update(getter: () -> T, updater: AtomicReferenceFieldUpdater<ByteBufferChannel, T>, block: (old: T) -> T?): Pair<T, T> {
        while (true) {
            val old = getter()
            val newValue = block(old) ?: continue
            if (old === newValue || updater.compareAndSet(this, old, newValue)) return Pair(old, newValue)
        }
    }

    companion object {

        private const val ReservedLongIndex = -8

        // todo: replace with atomicfu, remove companion object
        private val State = updater(ByteBufferChannel::state)
        private val WriteOp = updater(ByteBufferChannel::writeOp)
        private val ReadOp = updater(ByteBufferChannel::readOp)
        private val Closed = updater(ByteBufferChannel::closed)
    }

    private class ClosedElement(val cause: Throwable?) {
        val sendException: Throwable
            get() = cause ?: ClosedWriteChannelException("The channel was closed")

        companion object {
            val EmptyCause = ClosedElement(null)
        }
    }
}

