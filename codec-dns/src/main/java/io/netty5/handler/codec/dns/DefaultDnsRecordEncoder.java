/*
 * Copyright 2015 The Netty Project
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
import io.netty5.channel.socket.SocketProtocolFamily;
import io.netty5.handler.codec.UnsupportedMessageTypeException;
import io.netty5.util.internal.UnstableApi;
import org.jetbrains.annotations.VisibleForTesting;

import static io.netty5.handler.codec.dns.DnsCodecUtil.addressNumber;

/**
 * The default {@link DnsRecordEncoder} implementation.
 *
 * @see DefaultDnsRecordDecoder
 */
@UnstableApi
public class DefaultDnsRecordEncoder implements DnsRecordEncoder {
    private static final int PREFIX_MASK = Byte.SIZE - 1;

    /**
     * Creates a new instance.
     */
    protected DefaultDnsRecordEncoder() { }

    @Override
    public final void encodeQuestion(DnsQuestion question, Buffer out) throws Exception {
        encodeName(question.name(), out);
        out.ensureWritable(4);
        out.writeShort((short) question.type().intValue());
        out.writeShort((short) question.dnsClass());
    }

    private static final Class<?>[] SUPPORTED_MESSAGES = new Class<?>[] {
            DnsQuestion.class, DnsPtrRecord.class,
            DnsOptEcsRecord.class, DnsOptPseudoRecord.class, DnsRawRecord.class };

    @Override
    public void encodeRecord(DnsRecord record, Buffer out) throws Exception {
        if (record instanceof DnsQuestion) {
            encodeQuestion((DnsQuestion) record, out);
        } else if (record instanceof DnsPtrRecord) {
            encodePtrRecord((DnsPtrRecord) record, out);
        } else if (record instanceof DnsOptEcsRecord) {
            encodeOptEcsRecord((DnsOptEcsRecord) record, out);
        } else if (record instanceof DnsOptPseudoRecord) {
            encodeOptPseudoRecord((DnsOptPseudoRecord) record, out);
        } else if (record instanceof DnsRawRecord) {
            encodeRawRecord((DnsRawRecord) record, out);
        } else {
            throw new UnsupportedMessageTypeException(record, SUPPORTED_MESSAGES);
        }
    }

    private void encodeRecord0(DnsRecord record, Buffer out) throws Exception {
        encodeName(record.name(), out);
        out.ensureWritable(8);
        out.writeShort((short) record.type().intValue());
        out.writeShort((short) record.dnsClass());
        out.writeInt((int) record.timeToLive());
    }

    private void encodePtrRecord(DnsPtrRecord record, Buffer out) throws Exception {
        encodeRecord0(record, out);
        int writerIndex = out.writerOffset();
        // Skip 2 bytes as these will be used to encode the rdataLen after we know how many bytes were written.
        // See https://www.rfc-editor.org/rfc/rfc1035.html#section-3.2.1
        out.writerOffset(writerIndex + 2);
        encodeName(record.hostname(), out);
        int rdLength = out.writerOffset() - (writerIndex + 2);
        out.setShort(writerIndex, (short) rdLength);
    }

    private void encodeOptPseudoRecord(DnsOptPseudoRecord record, Buffer out) throws Exception {
        encodeRecord0(record, out);
        out.ensureWritable(2);
        out.writeShort((short) 0);
    }

    private void encodeOptEcsRecord(DnsOptEcsRecord record, Buffer out) throws Exception {
        encodeRecord0(record, out);

        int sourcePrefixLength = record.sourcePrefixLength();
        int scopePrefixLength = record.scopePrefixLength();
        int lowOrderBitsToPreserve = sourcePrefixLength & PREFIX_MASK;

        byte[] bytes = record.address();
        int addressBits = bytes.length << 3;
        if (addressBits < sourcePrefixLength || sourcePrefixLength < 0) {
            throw new IllegalArgumentException(sourcePrefixLength + ": " +
                    sourcePrefixLength + " (expected: 0 >= " + addressBits + ')');
        }

        // See https://www.iana.org/assignments/address-family-numbers/address-family-numbers.xhtml
        final short addressNumber = (short) addressNumber(bytes.length == 4 ?
                SocketProtocolFamily.INET : SocketProtocolFamily.INET6);
        int payloadLength = calculateEcsAddressLength(sourcePrefixLength, lowOrderBitsToPreserve);

        int fullPayloadLength = 2 + // OPTION-CODE
                2 + // OPTION-LENGTH
                2 + // FAMILY
                1 + // SOURCE PREFIX-LENGTH
                1 + // SCOPE PREFIX-LENGTH
                payloadLength; //  ADDRESS...

        out.ensureWritable(fullPayloadLength);
        out.writeShort((short) fullPayloadLength);
        out.writeShort((short) 8); // This is the defined type for ECS.

        out.writeShort((short) (fullPayloadLength - 4)); // Not include OPTION-CODE and OPTION-LENGTH
        out.writeShort(addressNumber);
        out.writeByte((byte) sourcePrefixLength);
        out.writeByte((byte) scopePrefixLength); // Must be 0 in queries.

        if (lowOrderBitsToPreserve > 0) {
            int bytesLength = payloadLength - 1;
            out.writeBytes(bytes, 0, bytesLength);

            // Pad the leftover of the last byte with zeros.
            out.writeByte(padWithZeros(bytes[bytesLength], lowOrderBitsToPreserve));
        } else {
            // The sourcePrefixLength align with Byte so just copy in the bytes directly.
            out.writeBytes(bytes, 0, payloadLength);
        }
    }

    // Package-Private for testing
    @VisibleForTesting
    static int calculateEcsAddressLength(int sourcePrefixLength, int lowOrderBitsToPreserve) {
        return (sourcePrefixLength >>> 3) + (lowOrderBitsToPreserve != 0 ? 1 : 0);
    }

    private void encodeRawRecord(DnsRawRecord record, Buffer out) throws Exception {
        encodeRecord0(record, out);

        Buffer content = record.content();
        int contentLen = content.readableBytes();

        out.ensureWritable(2 + contentLen);
        out.writeShort((short) contentLen);
        content.copyInto(content.readerOffset(), out, out.writerOffset(), contentLen);
        out.skipWritableBytes(contentLen);
    }

    protected void encodeName(String name, Buffer buf) throws Exception {
        DnsCodecUtil.encodeDomainName(name, buf);
    }

    private static byte padWithZeros(byte b, int lowOrderBitsToPreserve) {
        switch (lowOrderBitsToPreserve) {
        case 0:
            return 0;
        case 1:
            return (byte) (0x80 & b);
        case 2:
            return (byte) (0xC0 & b);
        case 3:
            return (byte) (0xE0 & b);
        case 4:
            return (byte) (0xF0 & b);
        case 5:
            return (byte) (0xF8 & b);
        case 6:
            return (byte) (0xFC & b);
        case 7:
            return (byte) (0xFE & b);
        case 8:
            return b;
        default:
            throw new IllegalArgumentException("lowOrderBitsToPreserve: " + lowOrderBitsToPreserve);
        }
    }
}
