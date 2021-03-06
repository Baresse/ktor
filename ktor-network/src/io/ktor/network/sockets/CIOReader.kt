package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.io.pool.*
import java.nio.channels.*

internal fun attachForReadingImpl(channel: ByteChannel, nioChannel: ReadableByteChannel, selectable: Selectable, selector: SelectorManager, pool: ObjectPool<ByteBuffer>): WriterJob {
    val buffer = pool.borrow()
    return writer(ioCoroutineDispatcher, channel) {
        try {
            while (true) {
                val rc = nioChannel.read(buffer)
                if (rc == -1) {
                    channel.close()
                    break
                } else if (rc == 0) {
                    selectable.interestOp(SelectInterest.READ, true)
                    selector.select(selectable, SelectInterest.READ)
                } else {
                    selectable.interestOp(SelectInterest.READ, false)
                    buffer.flip()
                    channel.writeFully(buffer)
                    buffer.clear()
                }
            }
        } finally {
            pool.recycle(buffer)
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.shutdownInput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}

internal fun attachForReadingDirectImpl(channel: ByteChannel, nioChannel: ReadableByteChannel, selectable: Selectable, selector: SelectorManager): WriterJob {
    return writer(ioCoroutineDispatcher, channel) {
        try {
            var rc: Int
            val writeBlock = { buffer: ByteBuffer ->
                rc = nioChannel.read(buffer) // we are writing from nio channel to CIO byte channel
            }

            while (true) {
                rc = 0
                channel.write(block = writeBlock)
                if (rc == -1) {
                    channel.close()
                    break
                } else if (rc == 0) {
                    selectable.interestOp(SelectInterest.READ, true)
                    selector.select(selectable, SelectInterest.READ)
                } else {
                    selectable.interestOp(SelectInterest.READ, false)
                }
            }
        } finally {
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.shutdownInput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}