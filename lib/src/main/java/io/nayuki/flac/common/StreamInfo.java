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

package io.nayuki.flac.common;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import io.nayuki.flac.decode.ByteArrayFlacInput;
import io.nayuki.flac.decode.DataFormatException;
import io.nayuki.flac.decode.FlacDecoder;
import io.nayuki.flac.decode.FlacLowLevelInput;
import io.nayuki.flac.encode.BitOutputStream;

/**
 * Represents precisely all the fields of a stream info metadata block. Mutable structure, not thread-safe. Also has methods for parsing and serializing this
 * structure to/from bytes. All fields can be modified freely when no method call is active.
 *
 * @see FrameInfo
 * @see FlacDecoder
 */
public final class StreamInfo {

    /*---- Fields about block and frame sizes ----*/

    /**
     * Minimum block size (in samples per channel) among the whole stream, a uint16 value. However when minBlockSize = maxBlockSize (constant block size
     * encoding style), the final block is allowed to be smaller than minBlockSize.
     */
    public int minBlockSize;

    /**
     * Maximum block size (in samples per channel) among the whole stream, a uint16 value.
     */
    public int maxBlockSize;

    /**
     * Minimum frame size (in bytes) among the whole stream, a uint24 value. However, a value of 0 signifies that the value is unknown.
     */
    public int minFrameSize;

    /**
     * Maximum frame size (in bytes) among the whole stream, a uint24 value. However, a value of 0 signifies that the value is unknown.
     */
    public int maxFrameSize;

    /*---- Fields about stream properties ----*/

    /**
     * The sample rate of the audio stream (in hertz (Hz)), a positive uint20 value. Note that 0 is an invalid value.
     */
    public int sampleRate;

    /**
     * The number of channels in the audio stream, between 1 and 8 inclusive. 1 means mono, 2 means stereo, et cetera.
     */
    public int numChannels;

    /**
     * The bits per sample in the audio stream, in the range 4 to 32 inclusive.
     */
    public int sampleDepth;

    /**
     * The total number of samples per channel in the whole stream, a uint36 value. The special value of 0 signifies that the value is unknown (not empty
     * zero-length stream).
     */
    public long numSamples;

    /**
     * The 16-byte MD5 hash of the raw uncompressed audio data serialized in little endian with channel interleaving (not planar). It can be all zeros to
     * signify that the hash was not computed. It is okay to replace this array as needed (the initially constructed array object is not special).
     */
    public byte[] md5Hash;

    /*---- Constructors ----*/


    /**
     * Constructs a blank stream info structure with certain default values.
     */
    public StreamInfo() {
        // Set these fields to legal unknown values
        this.minFrameSize = 0;
        this.maxFrameSize = 0;
        this.numSamples = 0;
        this.md5Hash = new byte[16];

        // Set these fields to invalid (not reserved) values
        this.minBlockSize = 0;
        this.maxBlockSize = 0;
        this.sampleRate = 0;
    }


    /**
     * Constructs a stream info structure by parsing the specified 34-byte metadata block. (The array must contain only the metadata payload, without the type
     * or length fields.)
     *
     * @param b the metadata block's payload data to parse (not {@code null})
     *
     * @throws NullPointerException if the array is {@code null}
     * @throws IllegalArgumentException if the array length is not 34
     * @throws DataFormatException if the data contains invalid values
     */
    public StreamInfo(byte[] b) {
        Objects.requireNonNull(b);
        if (b.length != 34) {
            throw new IllegalArgumentException("Invalid data length");
        }
        try {
            @SuppressWarnings("resource")
            final FlacLowLevelInput in = new ByteArrayFlacInput(b);
            this.minBlockSize = in.readUint(16);
            this.maxBlockSize = in.readUint(16);
            this.minFrameSize = in.readUint(24);
            this.maxFrameSize = in.readUint(24);
            if (this.minBlockSize < 16) {
                throw new DataFormatException("Minimum block size less than 16");
            }
            if (this.maxBlockSize > 65535) {
                throw new DataFormatException("Maximum block size greater than 65535");
            }
            if (this.maxBlockSize < this.minBlockSize) {
                throw new DataFormatException("Maximum block size less than minimum block size");
            }
            if (this.minFrameSize != 0 && this.maxFrameSize != 0 && this.maxFrameSize < this.minFrameSize) {
                throw new DataFormatException("Maximum frame size less than minimum frame size");
            }
            this.sampleRate = in.readUint(20);
            if (this.sampleRate == 0 || this.sampleRate > 655350) {
                throw new DataFormatException("Invalid sample rate");
            }
            this.numChannels = in.readUint(3) + 1;
            this.sampleDepth = in.readUint(5) + 1;
            this.numSamples = (long) in.readUint(18) << 18 | in.readUint(18); // uint36
            this.md5Hash = new byte[16];
            in.readFully(this.md5Hash);
            // Skip closing the in-memory stream
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
    }


    /**
     * Checks the state of this object, and either returns silently or throws an exception.
     *
     * @throws NullPointerException if the MD5 hash array is {@code null}
     * @throws IllegalStateException if any field has an invalid value
     */
    public void checkValues() {
        if (this.minBlockSize >>> 16 != 0) {
            throw new IllegalStateException("Invalid minimum block size");
        }
        if (this.maxBlockSize >>> 16 != 0) {
            throw new IllegalStateException("Invalid maximum block size");
        }
        if (this.minFrameSize >>> 24 != 0) {
            throw new IllegalStateException("Invalid minimum frame size");
        }
        if (this.maxFrameSize >>> 24 != 0) {
            throw new IllegalStateException("Invalid maximum frame size");
        }
        if (this.sampleRate == 0 || this.sampleRate >>> 20 != 0) {
            throw new IllegalStateException("Invalid sample rate");
        }
        if (this.numChannels < 1 || this.numChannels > 8) {
            throw new IllegalStateException("Invalid number of channels");
        }
        if (this.sampleDepth < 4 || this.sampleDepth > 32) {
            throw new IllegalStateException("Invalid sample depth");
        }
        if (this.numSamples >>> 36 != 0) {
            throw new IllegalStateException("Invalid number of samples");
        }
        Objects.requireNonNull(this.md5Hash);
        if (this.md5Hash.length != 16) {
            throw new IllegalStateException("Invalid MD5 hash length");
        }
    }


    /**
     * Checks whether the specified frame information is consistent with values in this stream info object, either returning silently or throwing an exception.
     *
     * @param meta the frame info object to check (not {@code null})
     *
     * @throws NullPointerException if the frame info is {@code null}
     * @throws DataFormatException if the frame info contains bad values
     */
    public void checkFrame(FrameInfo meta) {
        if (meta.numChannels != this.numChannels) {
            throw new DataFormatException("Channel count mismatch");
        }
        if (meta.sampleRate != -1 && meta.sampleRate != this.sampleRate) {
            throw new DataFormatException("Sample rate mismatch");
        }
        if (meta.sampleDepth != -1 && meta.sampleDepth != this.sampleDepth) {
            throw new DataFormatException("Sample depth mismatch");
        }
        if (this.numSamples != 0 && meta.blockSize > this.numSamples) {
            throw new DataFormatException("Block size exceeds total number of samples");
        }

        if (meta.blockSize > this.maxBlockSize) {
            throw new DataFormatException("Block size exceeds maximum");
            // Note: If minBlockSize == maxBlockSize, then the final block
            // in the stream is allowed to be smaller than minBlockSize
        }

        if (this.minFrameSize != 0 && meta.frameSize < this.minFrameSize) {
            throw new DataFormatException("Frame size less than minimum");
        }
        if (this.maxFrameSize != 0 && meta.frameSize > this.maxFrameSize) {
            throw new DataFormatException("Frame size exceeds maximum");
        }
    }


    /**
     * Writes this stream info metadata block to the specified output stream, including the metadata block header, writing exactly 38 bytes. (This is unlike the
     * constructor, which takes an array without the type and length fields.) The output stream must initially be aligned to a byte boundary, and will finish at
     * a byte boundary.
     *
     * @param last whether the metadata block is the final one in the FLAC file
     * @param out the output stream to write to (not {@code null})
     *
     * @throws NullPointerException if the output stream is {@code null}
     * @throws IOException if an I/O exception occurred
     */
    public void write(boolean last, BitOutputStream out) throws IOException {
        // Check arguments and state
        Objects.requireNonNull(out);
        this.checkValues();

        // Write metadata block header
        out.writeInt(1, last ? 1 : 0);
        out.writeInt(7, 0); // Type
        out.writeInt(24, 34); // Length

        // Write stream info block fields
        out.writeInt(16, this.minBlockSize);
        out.writeInt(16, this.maxBlockSize);
        out.writeInt(24, this.minFrameSize);
        out.writeInt(24, this.maxFrameSize);
        out.writeInt(20, this.sampleRate);
        out.writeInt(3, this.numChannels - 1);
        out.writeInt(5, this.sampleDepth - 1);
        out.writeInt(18, (int) (this.numSamples >>> 18));
        out.writeInt(18, (int) (this.numSamples >>> 0));
        for (final byte b : this.md5Hash) {
            out.writeInt(8, b);
        }
    }


    /**
     * Computes and returns the MD5 hash of the specified raw audio sample data at the specified bit depth. Currently, the bit depth must be a multiple of 8,
     * between 8 and 32 inclusive. The returned array is a new object of length 16.
     *
     * @param samples the audio samples to hash, where each subarray is a channel (all not {@code null})
     * @param depth the bit depth of the audio samples (i.e. each sample value is a signed 'depth'-bit integer)
     *
     * @return a new 16-byte array representing the MD5 hash of the audio data
     *
     * @throws NullPointerException if the array or any subarray is {@code null}
     * @throws IllegalArgumentException if the bit depth is unsupported
     */
    public static byte[] getMd5Hash(int[][] samples, int depth) {
        // Check arguments
        Objects.requireNonNull(samples);
        for (final int[] chanSamples : samples) {
            Objects.requireNonNull(chanSamples);
        }
        if (depth < 0 || depth > 32 || depth % 8 != 0) {
            throw new IllegalArgumentException("Unsupported bit depth");
        }

        // Create hasher
        MessageDigest hasher;
        try { // Guaranteed available by the Java Cryptography Architecture
            hasher = MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        // Convert samples to a stream of bytes, compute hash
        final int numChannels = samples.length;
        final int numSamples = samples[0].length;
        final int numBytes = depth / 8;
        final byte[] buf = new byte[numChannels * numBytes * Math.min(numSamples, 2048)];
        for (int i = 0, l = 0; i < numSamples; i++) {
            for (int j = 0; j < numChannels; j++) {
                final int val = samples[j][i];
                for (int k = 0; k < numBytes; k++, l++) {
                    buf[l] = (byte) (val >>> (k << 3));
                }
            }
            if (l == buf.length || i == numSamples - 1) {
                hasher.update(buf, 0, l);
                l = 0;
            }
        }
        return hasher.digest();
    }

}
