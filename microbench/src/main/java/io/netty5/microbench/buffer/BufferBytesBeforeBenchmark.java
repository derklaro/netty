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
package io.netty5.microbench.buffer;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.buffer.MemoryManager;
import io.netty5.buffer.bytebuffer.ByteBufferMemoryManager;
import io.netty5.buffer.unsafe.UnsafeMemoryManager;
import io.netty5.microbench.util.AbstractMicrobenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {
        "-Dio.netty5.tryReflectionSetAccessible=true",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints" })
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
public class BufferBytesBeforeBenchmark extends AbstractMicrobenchmark {

    @Param({
            "7",
            "16",
            "23",
            "32",
    })
    private int size;

    @Param({
            "4",
            "11",
    })
    private int logPermutations;

    @Param({
            "1"
    })
    private int seed;

    private int permutations;

    private Buffer[] data;
    private int i;

    @Param({
            "-91"
    })
    private byte needleByte;
    private Buffer needleBuffer;
    private int needleBufferLength = 5;

    @Param({
            "true",
            "false",
    })
    private boolean direct;

    @Param({
            "false",
            "true",
    })
    private boolean noUnsafe;

    @Setup(Level.Trial)
    public void init() {
        SplittableRandom random = new SplittableRandom(seed);
        permutations = 1 << logPermutations;
        data = new Buffer[permutations];
        MemoryManager memoryManager = noUnsafe ? new ByteBufferMemoryManager() : new UnsafeMemoryManager();
        BufferAllocator allocator = MemoryManager.using(memoryManager, () -> direct?
                BufferAllocator.offHeapUnpooled() : BufferAllocator.onHeapUnpooled());
        needleBuffer = allocator.allocate(needleBufferLength);
        for (int j = 0; j < needleBufferLength; j++) {
            needleBuffer.writeByte((byte) (needleByte + j));
        }
        for (int i = 0; i < permutations; ++i) {
            data[i] = allocator.allocate(size);
            data[i].skipWritableBytes(size);
            for (int j = 0; j < size; j++) {
                int value = random.nextInt(Byte.MIN_VALUE, Byte.MAX_VALUE + 1);
                // turn any found value into something different
                if (value == needleByte) {
                    value = ~value;
                }
                data[i].setByte(j, (byte) value);
            }
            for (int k = 0; k < needleBufferLength; k++) {
                data[i].setByte(data[i].capacity() - needleBufferLength + k, needleBuffer.getByte(k));
            }
        }
        allocator.close();
    }

    private Buffer getData() {
        return data[i++ & permutations - 1];
    }

    @Benchmark
    public int bytesBeforeByte() {
        return getData().bytesBefore(needleByte);
    }

    @Benchmark
    public int bytesBeforeBuffer() {
        return getData().bytesBefore(needleBuffer);
    }

    @TearDown
    public void releaseBuffers() {
        for (Buffer buffer : data) {
            buffer.close();
        }
    }
}
