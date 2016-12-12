/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.block.stream;

import alluxio.client.BoundedStream;
import alluxio.client.Cancelable;
import alluxio.exception.PreconditionMessage;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Provides an {@link OutputStream} implementation that is based on {@link PacketWriter} which
 * streams data packet by packet.
 */
@NotThreadSafe
public final class PacketOutStream extends OutputStream implements BoundedStream, Cancelable {
  /** Length of the stream. If unknown, set to Long.MAX_VALUE. */
  private final long mLength;
  private ByteBuf mCurrentPacket = null;

  private final PacketWriter mPacketWriter;
  private boolean mClosed;

  /**
   * Constructs a new {@link PacketOutStream}.
   *
   * @param packetWriter the packet writer
   * @param length the length of the stream
   */
  public PacketOutStream(PacketWriter packetWriter, long length) {
    mLength = length;
    mPacketWriter = packetWriter;
    mClosed = false;
  }

  /**
   * @return the remaining size of the block
   */
  @Override
  public long remaining() {
    return mLength - mPacketWriter.pos()
        - (mCurrentPacket != null ? mCurrentPacket.readableBytes() : 0);
  }

  @Override
  public void write(int b) throws IOException {
    Preconditions.checkState(remaining() > 0, PreconditionMessage.ERR_END_OF_BLOCK);
    updateCurrentPacket(false);
    mCurrentPacket.writeByte(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return;
    }

    while (len > 0) {
      updateCurrentPacket(false);
      int toWrite = Math.min(len, mCurrentPacket.writableBytes());
      mCurrentPacket.writeBytes(b, off, toWrite);
      off += toWrite;
      len -= toWrite;
    }
    updateCurrentPacket(false);
  }

  @Override
  public void flush() throws IOException {
    if (mClosed) {
      return;
    }
    updateCurrentPacket(true);
    mPacketWriter.flush();

    // Release the channel used in the packet writer early. This is required to avoid holding the
    // netty channel unnecessarily because the block out streams are closed after all the blocks
    // are written.
    if (mPacketWriter.pos() == mLength) {
      close();
    }
  }

  @Override
  public void cancel() throws IOException {
    if (mClosed) {
      return;
    }
    releaseCurrentPacket();
    mPacketWriter.cancel();
  }

  @Override
  public void close() throws IOException {
    try {
      updateCurrentPacket(true);
    } finally {
      mPacketWriter.close();
      mClosed = true;
    }
  }

  /**
   * Updates the current packet.
   *
   * @param lastPacket if the current packet is the last packet
   * @throws IOException if it fails to update the current packet
   */
  private void updateCurrentPacket(boolean lastPacket) throws IOException {
    // Early return for the most common case.
    if (mCurrentPacket != null && mCurrentPacket.writableBytes() > 0 && !lastPacket) {
      return;
    }

    if (mCurrentPacket == null) {
      if (!lastPacket) {
        mCurrentPacket = allocateBuffer();
      }
      return;
    }

    if (mCurrentPacket.readableBytes() > 0) {
      if (mCurrentPacket.writableBytes() == 0 || lastPacket) {
        mPacketWriter.writePacket(mCurrentPacket);
        mCurrentPacket = null;
      }
      if (!lastPacket) {
        mCurrentPacket = allocateBuffer();
      }
      return;
    }
  }

  /**
   * Release the current packet.
   */
  private void releaseCurrentPacket() {
    if (mCurrentPacket != null) {
      ReferenceCountUtil.release(mCurrentPacket);
      mCurrentPacket = null;
    }
  }

  /**
   * @return a newly allocated byte buffer of the user defined default size
   */
  private ByteBuf allocateBuffer() {
    return PooledByteBufAllocator.DEFAULT.buffer(mPacketWriter.packetSize());
  }
}
