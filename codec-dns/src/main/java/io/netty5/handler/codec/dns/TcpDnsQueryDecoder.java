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
package io.netty5.handler.codec.dns;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty5.util.internal.UnstableApi;

import static java.util.Objects.requireNonNull;

@UnstableApi
public final class TcpDnsQueryDecoder extends LengthFieldBasedFrameDecoder {
    private final DnsRecordDecoder decoder;

    /**
     * Creates a new decoder with {@linkplain DnsRecordDecoder#DEFAULT the default record decoder}.
     */
    public TcpDnsQueryDecoder() {
        this(DnsRecordDecoder.DEFAULT, 65535);
    }

    /**
     * Creates a new decoder with the specified {@code decoder}.
     */
    public TcpDnsQueryDecoder(DnsRecordDecoder decoder, int maxFrameLength) {
        super(maxFrameLength, 0, 2, 0, 2);
        this.decoder = requireNonNull(decoder, "decoder");
    }

    @Override
    protected Object decode0(ChannelHandlerContext ctx, Buffer in) throws Exception {
        Buffer frame = (Buffer) super.decode0(ctx, in);
        try (frame) {
            if (frame == null) {
                return null;
            }
            return DnsMessageUtil.decodeDnsQuery(decoder, ctx.bufferAllocator(), frame.split(), DefaultDnsQuery::new);
        }
    }
}
