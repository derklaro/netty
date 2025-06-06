/*
 * Copyright 2017 The Netty Project
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
package io.netty5.microbench.handler.ssl;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;

@State(Scope.Benchmark)
@Threads(1)
public class SslEngineWrapBenchmark extends AbstractSslEngineThroughputBenchmark {

    @Param({
            "1",
            "2",
            "5",
            "10",
    })
    public int numWraps;

    @Benchmark
    public ByteBuffer wrap() throws SSLException {
        return doWrap(numWraps);
    }
}
