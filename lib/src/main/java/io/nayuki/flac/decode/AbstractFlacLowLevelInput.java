/* FLAC library (Java)
 *
 * Copyright (c) Project Nayuki https://www.nayuki.io/page/flac-library-java
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program (see COPYING.txt and COPYING.LESSER.txt). If not, see
 * <http://www.gnu.org/licenses/>. */

package io.nayuki.flac.decode;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A basic implementation of most functionality required by FlacLowLevelInpuut.
 */
/**
 * @author Matthias
 *
 */
/**
 * @author Matthias
 *
 */
public abstract class AbstractFlacLowLevelInput implements FlacLowLevelInput {

    /*---- Fields ----*/

    // Data from the underlying stream is first stored into this byte buffer before further processing.
    private long   byteBufferStartPos;
    private byte[] byteBuffer;
    private int    byteBufferLen;
    private int    byteBufferIndex;

    // The buffer of next bits to return to a reader. Note that byteBufferIndex is incremented when byte
    // values are put into the bit buffer, but they might not have been consumed by the ultimate reader yet.
    private long bitBuffer;    // Only the bottom bitBufferLen bits are valid; the top bits are garbage.
    private int  bitBufferLen; // Always in the range [0, 64].

    // Current state of the CRC calculations.
    private int crc8;          // Always a uint8 value.
    private int crc16;         // Always a uint16 value.
    private int crcStartIndex; // In the range [0, byteBufferLen], unless byteBufferLen = -1.

    /*---- Constructors ----*/


    public AbstractFlacLowLevelInput() {
        this.byteBuffer = new byte[4096];
        this.positionChanged(0);
    }

    /*---- Methods ----*/

    /*-- Stream position --*/


    @Override
    public long getPosition() {
        return this.byteBufferStartPos + this.byteBufferIndex - (this.bitBufferLen + 7) / 8;
    }


    @Override
    public int getBitPosition() {
        return -this.bitBufferLen & 7;
    }


    // When a subclass handles seekTo() and didn't throw UnsupportedOperationException,
    // it must call this method to flush the buffers of upcoming data.
    protected void positionChanged(long pos) {
        this.byteBufferStartPos = pos;
        Arrays.fill(this.byteBuffer, (byte) 0); // Defensive clearing, should have no visible effect outside of debugging
        this.byteBufferLen = 0;
        this.byteBufferIndex = 0;
        this.bitBuffer = 0; // Defensive clearing, should have no visible effect outside of debugging
        this.bitBufferLen = 0;
        this.resetCrcs();
    }


    // Either returns silently or throws an exception.
    private void checkByteAligned() {
        if (this.bitBufferLen % 8 != 0) {
            throw new IllegalStateException("Not at a byte boundary");
        }
    }

    /*-- Reading bitwise integers --*/


    @Override
    public int readUint(int n) throws IOException {
        if (n < 0 || n > 32) {
            throw new IllegalArgumentException();
        }
        while (this.bitBufferLen < n) {
            final int b = this.readUnderlying();
            if (b == -1) {
                throw new EOFException();
            }
            this.bitBuffer = this.bitBuffer << 8 | b;
            this.bitBufferLen += 8;
            assert 0 <= this.bitBufferLen && this.bitBufferLen <= 64;
        }
        int result = (int) (this.bitBuffer >>> this.bitBufferLen - n);
        if (n != 32) {
            result &= (1 << n) - 1;
            assert result >>> n == 0;
        }
        this.bitBufferLen -= n;
        assert 0 <= this.bitBufferLen && this.bitBufferLen <= 64;
        return result;
    }


    @Override
    public int readSignedInt(int n) throws IOException {
        final int shift = 32 - n;
        return this.readUint(n) << shift >> shift;
    }


    @Override
    public void readRiceSignedInts(int param, long[] result, int start, int end) throws IOException {
        if (param < 0 || param > 31) {
            throw new IllegalArgumentException();
        }
        final long unaryLimit = 1L << 53 - param;

        final byte[] consumeTable = AbstractFlacLowLevelInput.RICE_DECODING_CONSUMED_TABLES[param];
        final int[] valueTable = AbstractFlacLowLevelInput.RICE_DECODING_VALUE_TABLES[param];
        while (true) {
            middle: while (start <= end - AbstractFlacLowLevelInput.RICE_DECODING_CHUNK) {
                if (this.bitBufferLen < AbstractFlacLowLevelInput.RICE_DECODING_CHUNK * AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS) {
                    if (this.byteBufferIndex > this.byteBufferLen - 8) {
                        break;
                    }
                    this.fillBitBuffer();
                }
                for (int i = 0; i < AbstractFlacLowLevelInput.RICE_DECODING_CHUNK; i++, start++) {
                    // Fast decoder
                    final int extractedBits = (int) (this.bitBuffer >>> this.bitBufferLen - AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS)
                            & AbstractFlacLowLevelInput.RICE_DECODING_TABLE_MASK;
                    final int consumed = consumeTable[extractedBits];
                    if (consumed == 0) {
                        break middle;
                    }
                    this.bitBufferLen -= consumed;
                    result[start] = valueTable[extractedBits];
                }
            }

            // Slow decoder
            if (start >= end) {
                break;
            }
            long val = 0;
            while (this.readUint(1) == 0) {
                if (val >= unaryLimit) {
                    // At this point, the final decoded value would be so large that the result of the
                    // downstream restoreLpc() calculation would not fit in the output sample's bit depth -
                    // hence why we stop early and throw an exception. However, this check is conservative
                    // and doesn't catch all the cases where the post-LPC result wouldn't fit.
                    throw new DataFormatException("Residual value too large");
                }
                val++;
            }
            val = val << param | this.readUint(param); // Note: Long masking unnecessary because param <= 31
            assert val >>> 53 == 0; // Must fit a uint53 by design due to unaryLimit
            val = val >>> 1 ^ -(val & 1); // Transform uint53 to int53 according to Rice coding of signed numbers
            assert val >> 52 == 0 || val >> 52 == -1; // Must fit a signed int53 by design
            result[start] = val;
            start++;
        }
    }


    /**
     * Appends at least 8 bits to the bit buffer, or throws EOFException.
     *
     * @throws IOException
     */
    private void fillBitBuffer() throws IOException {
        int i = this.byteBufferIndex;
        final int n = Math.min(64 - this.bitBufferLen >>> 3, this.byteBufferLen - i);
        final byte[] b = this.byteBuffer;
        if (n > 0) {
            for (int j = 0; j < n; j++, i++) {
                this.bitBuffer = this.bitBuffer << 8 | b[i] & 0xFF;
            }
            this.bitBufferLen += n << 3;
        } else if (this.bitBufferLen <= 56) {
            final int temp = this.readUnderlying();
            if (temp == -1) {
                throw new EOFException();
            }
            this.bitBuffer = this.bitBuffer << 8 | temp;
            this.bitBufferLen += 8;
        }
        assert 8 <= this.bitBufferLen && this.bitBufferLen <= 64;
        this.byteBufferIndex += n;
    }

    /*-- Reading bytes --*/


    @Override
    public int readByte() throws IOException {
        this.checkByteAligned();
        if (this.bitBufferLen >= 8) {
            return this.readUint(8);
        }
        assert this.bitBufferLen == 0;
        return this.readUnderlying();
    }


    @Override
    public void readFully(byte[] b) throws IOException {
        Objects.requireNonNull(b);
        this.checkByteAligned();
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) this.readUint(8);
        }
    }


    /**
     * Reads a byte from the byte buffer (if available) or from the underlying stream, returning either a uint8 or -1.
     *
     * @return
     *
     * @throws IOException
     */
    private int readUnderlying() throws IOException {
        if (this.byteBufferIndex >= this.byteBufferLen) {
            if (this.byteBufferLen == -1) {
                return -1;
            }
            this.byteBufferStartPos += this.byteBufferLen;
            this.updateCrcs(0);
            this.byteBufferLen = this.readUnderlying(this.byteBuffer, 0, this.byteBuffer.length);
            this.crcStartIndex = 0;
            if (this.byteBufferLen <= 0) {
                return -1;
            }
            this.byteBufferIndex = 0;
        }
        assert this.byteBufferIndex < this.byteBufferLen;
        final int temp = this.byteBuffer[this.byteBufferIndex] & 0xFF;
        this.byteBufferIndex++;
        return temp;
    }


    /**
     * Reads up to 'len' bytes from the underlying byte-based input stream into the given array subrange. Returns a value in the range [0, len] for a successful
     * read, or -1 if the end of stream was reached.
     *
     * @param buf
     * @param off
     * @param len
     *
     * @return
     *
     * @throws IOException
     */
    protected abstract int readUnderlying(byte[] buf, int off, int len) throws IOException;

    /*-- CRC calculations --*/


    @Override
    public void resetCrcs() {
        this.checkByteAligned();
        this.crcStartIndex = this.byteBufferIndex - this.bitBufferLen / 8;
        this.crc8 = 0;
        this.crc16 = 0;
    }


    @Override
    public int getCrc8() {
        this.checkByteAligned();
        this.updateCrcs(this.bitBufferLen / 8);
        if (this.crc8 >>> 8 != 0) {
            throw new AssertionError();
        }
        return this.crc8;
    }


    @Override
    public int getCrc16() {
        this.checkByteAligned();
        this.updateCrcs(this.bitBufferLen / 8);
        if (this.crc16 >>> 16 != 0) {
            throw new AssertionError();
        }
        return this.crc16;
    }


    /**
     * Updates the two CRC values with data in byteBuffer[crcStartIndex : byteBufferIndex - unusedTrailingBytes].
     *
     * @param unusedTrailingBytes
     */
    private void updateCrcs(int unusedTrailingBytes) {
        final int end = this.byteBufferIndex - unusedTrailingBytes;
        for (int i = this.crcStartIndex; i < end; i++) {
            final int b = this.byteBuffer[i] & 0xFF;
            this.crc8 = AbstractFlacLowLevelInput.CRC8_TABLE[this.crc8 ^ b] & 0xFF;
            this.crc16 = AbstractFlacLowLevelInput.CRC16_TABLE[this.crc16 >>> 8 ^ b] ^ (this.crc16 & 0xFF) << 8;
            assert this.crc8 >>> 8 == 0;
            assert this.crc16 >>> 16 == 0;
        }
        this.crcStartIndex = end;
    }

    /*-- Miscellaneous --*/


    /**
     * Note: This class only uses memory and has no native resources. It's not strictly necessary to call the implementation of
     * AbstractFlacLowLevelInput.close() here, but it's a good habit anyway.
     */
    @Override
    public void close() throws IOException {
        this.byteBuffer = null;
        this.byteBufferLen = -1;
        this.byteBufferIndex = -1;
        this.bitBuffer = 0;
        this.bitBufferLen = -1;
        this.crc8 = -1;
        this.crc16 = -1;
        this.crcStartIndex = -1;
    }

    /*---- Tables of constants ----*/

    // For Rice decoding


    private static final int      RICE_DECODING_TABLE_BITS      = 13;                                                                   // Configurable, must be
                                                                                                                                        // positive
    private static final int      RICE_DECODING_TABLE_MASK      = (1 << AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS) - 1;
    private static final byte[][] RICE_DECODING_CONSUMED_TABLES = new byte[31][1 << AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS];
    private static final int[][]  RICE_DECODING_VALUE_TABLES    = new int[31][1 << AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS];
    private static final int      RICE_DECODING_CHUNK           = 4;                                                                    // Configurable, must be
                                                                                                                                        // positive, and
                                                                                                                                        // RICE_DECODING_CHUNK *
                                                                                                                                        // RICE_DECODING_TABLE_BITS
                                                                                                                                        // <= 64

    static {
        for (int param = 0; param < AbstractFlacLowLevelInput.RICE_DECODING_CONSUMED_TABLES.length; param++) {
            final byte[] consumed = AbstractFlacLowLevelInput.RICE_DECODING_CONSUMED_TABLES[param];
            final int[] values = AbstractFlacLowLevelInput.RICE_DECODING_VALUE_TABLES[param];
            for (int i = 0;; i++) {
                final int numBits = (i >>> param) + 1 + param;
                if (numBits > AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS) {
                    break;
                }
                final int bits = 1 << param | i & (1 << param) - 1;
                final int shift = AbstractFlacLowLevelInput.RICE_DECODING_TABLE_BITS - numBits;
                for (int j = 0; j < 1 << shift; j++) {
                    consumed[bits << shift | j] = (byte) numBits;
                    values[bits << shift | j] = i >>> 1 ^ -(i & 1);
                }
            }
            if (consumed[0] != 0) {
                throw new AssertionError();
            }
        }
    }

    // For CRC calculations

    private static byte[] CRC8_TABLE  = new byte[256];
    private static char[] CRC16_TABLE = new char[256];

    static {
        for (int i = 0; i < AbstractFlacLowLevelInput.CRC8_TABLE.length; i++) {
            int temp8 = i;
            int temp16 = i << 8;
            for (int j = 0; j < 8; j++) {
                temp8 = temp8 << 1 ^ (temp8 >>> 7) * 0x107;
                temp16 = temp16 << 1 ^ (temp16 >>> 15) * 0x18005;
            }
            AbstractFlacLowLevelInput.CRC8_TABLE[i] = (byte) temp8;
            AbstractFlacLowLevelInput.CRC16_TABLE[i] = (char) temp16;
        }
    }

}
