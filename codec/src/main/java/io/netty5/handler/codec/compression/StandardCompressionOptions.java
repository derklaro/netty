/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.codec.compression;

import com.aayushatharva.brotli4j.encoder.Encoder;
import io.netty5.util.internal.ObjectUtil;

import java.util.Objects;

/**
 * Standard Compression Options for {@link BrotliOptions},
 * {@link GzipOptions} and {@link DeflateOptions}
 */
public final class StandardCompressionOptions {

    private StandardCompressionOptions() {
        // Prevent outside initialization
    }

    /**
     * Default implementation of {@link BrotliOptions} with {@link Encoder.Parameters#setQuality(int)} set to 4
     * and {@link Encoder.Parameters#setMode(Encoder.Mode)} set to {@link Encoder.Mode#TEXT}
     */
    public static BrotliOptions brotli() {
        return BrotliOptions.DEFAULT;
    }

    /**
     * Create a new {@link BrotliOptions}
     *
     * @param quality Specifies the compression level.
     * @param window  Specifies the size of the sliding window when compressing.
     * @param mode    optimizes the compression algorithm based on the type of input data.
     * @throws NullPointerException If {@link BrotliMode} is {@code null}
     */
    public static BrotliOptions brotli(int quality, int window, BrotliMode mode) {
        ObjectUtil.checkInRange(quality, 0, 11, "quality");
        ObjectUtil.checkInRange(window, 10, 24, "window");
        Objects.requireNonNull(mode, "mode");

        Encoder.Parameters parameters = new Encoder.Parameters()
                .setQuality(quality)
                .setWindow(window)
                .setMode(mode.adapt());
        return new BrotliOptions(parameters);
    }

    /**
     * Default implementation of {@link ZstdOptions} with{compressionLevel(int)} set to
     * {@link ZstdConstants#DEFAULT_COMPRESSION_LEVEL},{@link ZstdConstants#DEFAULT_BLOCK_SIZE},
     * {@link ZstdConstants#MAX_BLOCK_SIZE}
     */
    public static ZstdOptions zstd() {
        return ZstdOptions.DEFAULT;
    }

    /**
     * Create a new {@link ZstdOptions}
     *
     * @param blockSize        is used to calculate the compressionLevel
     * @param maxEncodeSize    specifies the size of the largest compressed object
     * @param compressionLevel specifies the level of the compression
     */
    public static ZstdOptions zstd(int compressionLevel, int blockSize, int maxEncodeSize) {
        return new ZstdOptions(compressionLevel, blockSize, maxEncodeSize);
    }

    /**
     * Create a new {@link SnappyOptions}
     */
    public static SnappyOptions snappy() {
        return new SnappyOptions();
    }

    /**
     * Default implementation of {@link GzipOptions} with
     * {@code compressionLevel()} set to 6, {@code windowBits()} set to 15 and {@code memLevel()} set to 8.
     */
    public static GzipOptions gzip() {
        return GzipOptions.DEFAULT;
    }

    /**
     * Create a new {@link GzipOptions} Instance
     *
     * @param compressionLevel {@code 1} yields the fastest compression and {@code 9} yields the
     *                         best compression.  {@code 0} means no compression.  The default
     *                         compression level is {@code 6}.
     */
    public static GzipOptions gzip(int compressionLevel) {
        return new GzipOptions(compressionLevel);
    }

    /**
     * Default implementation of {@link DeflateOptions} with
     * {@code compressionLevel} set to 6, {@code windowBits} set to 15 and {@code memLevel} set to 8.
     */
    public static DeflateOptions deflate() {
        return DeflateOptions.DEFAULT;
    }

    /**
     * Create a new {@link DeflateOptions} Instance
     *
     * @param compressionLevel {@code 1} yields the fastest compression and {@code 9} yields the
     *                         best compression.  {@code 0} means no compression.  The default
     *                         compression level is {@code 6}.
     */
    public static DeflateOptions deflate(int compressionLevel) {
        return new DeflateOptions(compressionLevel);
    }
}
