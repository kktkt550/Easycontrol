package top.saymzx.easycontrol.app.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;

public class BufferNew {
    private volatile boolean isClosed = false;
    private final LinkedBlockingDeque<ByteBuffer> dataQueue = new LinkedBlockingDeque<>();
    // 维护运行总字节数，避免 getSize / readByteArrayBeforeClose 每次 O(n) 遍历队列
    private final java.util.concurrent.atomic.AtomicInteger totalSize = new java.util.concurrent.atomic.AtomicInteger(0);

    public void write(ByteBuffer data) {
        int remaining = data.remaining();
        dataQueue.offerLast(data);
        totalSize.addAndGet(remaining);
    }

    public synchronized ByteBuffer read(int len) throws InterruptedException, IOException {
        if (len < 0 || isClosed) throw new IOException("BufferNew error");
        ByteBuffer data = ByteBuffer.allocate(len);
        int bytesToRead = len;
        while (bytesToRead > 0) {
            ByteBuffer tmpData = dataQueue.takeFirst();
            if (isClosed) throw new IOException("BufferNew error");
            int remaining = tmpData.remaining();
            if (remaining <= bytesToRead) {
                data.put(tmpData);
                bytesToRead -= remaining;
                totalSize.addAndGet(-remaining);
            } else {
                int oldLimit = tmpData.limit();
                tmpData.limit(tmpData.position() + bytesToRead);
                data.put(tmpData);
                totalSize.addAndGet(-(oldLimit - tmpData.position()));
                tmpData.limit(oldLimit);
                dataQueue.offerFirst(tmpData);
                bytesToRead = 0;
            }
        }
        data.flip();
        return data;
    }

    public synchronized ByteBuffer readNext() throws InterruptedException, IOException {
        if (isClosed) throw new IOException("BufferNew error");
        ByteBuffer byteBuffer = dataQueue.takeFirst();
        if (isClosed) throw new IOException("BufferNew error");
        totalSize.addAndGet(-byteBuffer.remaining());
        return byteBuffer;
    }

    public ByteBuffer readByteArrayBeforeClose() {
        int size = Math.max(totalSize.get(), 1);
        ByteBuffer byteBuffer = ByteBuffer.allocate(size);
        for (ByteBuffer tmpBuffer : dataQueue) byteBuffer.put(tmpBuffer);
        return byteBuffer;
    }

    public boolean isEmpty() {
        return dataQueue.isEmpty();
    }

    public int getSize() {
        return totalSize.get();
    }

    public void close() {
        if (isClosed) return;
        isClosed = true;
        dataQueue.offer(ByteBuffer.allocate(1));
        totalSize.incrementAndGet();
    }

}
