package kotlinx.coroutines.experimental.io

import kotlinx.coroutines.experimental.io.internal.ReadWriteBufferState
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.io.pool.*
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PooledBufferTest {
    private val allocated = CopyOnWriteArrayList<ByteBuffer>()

    private inner class TestPool : ObjectPool<ReadWriteBufferState.Initial> {
        override val capacity: Int get() = 0

        override fun borrow(): ReadWriteBufferState.Initial {
            val buffer = ReadWriteBufferState.Initial(ByteBuffer.allocate(4096))
            allocated.add(buffer.backingBuffer)
            return buffer
        }

        override fun recycle(instance: ReadWriteBufferState.Initial) {
            if (!allocated.remove(instance.backingBuffer)) {
                fail("Couldn't release buffer from pool")
            }
        }

        override fun dispose() {
        }
    }

    private val channel = ByteBufferChannel(autoFlush = true, pool = TestPool())

    @After
    fun tearDown() {
        assertTrue { allocated.isEmpty() }
    }

    @Test
    fun testWriteReadClose() {
        runBlocking {
            channel.writeInt(1)
            assertEquals(1, allocated.size)
            channel.readInt()
            channel.close()
            assertEquals(0, allocated.size)
        }
    }

    @Test
    fun testWriteCloseRead() {
        runBlocking {
            channel.writeInt(1)
            assertEquals(1, allocated.size)
            channel.close()
            channel.readInt()
            assertEquals(0, allocated.size)
        }
    }

    @Test
    fun testWriteCloseReadRead() {
        runBlocking {
            channel.writeInt(1)
            assertEquals(1, allocated.size)
            channel.close()
            channel.readShort()
            assertEquals(1, allocated.size)
            channel.readShort()
            assertEquals(0, allocated.size)
        }
    }

    @Test
    fun testCloseOnly() {
        runBlocking {
            channel.close()
            assertEquals(0, allocated.size)
        }
    }

    @Test
    fun testCloseWithEerror() {
        runBlocking {
            channel.writeFully("OK".toByteArray())
            assertEquals(1, allocated.size)
            channel.close(IOException())
            assertEquals(0, allocated.size)
        }
    }
}