/*
 * Copyright 2014 The Netty Project
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

package io.netty5.resolver.dns;

import io.netty5.channel.ChannelFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.resolver.AddressResolver;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.resolver.InetSocketAddressResolver;
import io.netty5.resolver.NameResolver;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.StringUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link AddressResolverGroup} of {@link DnsNameResolver}s.
 */
public class DnsAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final DnsNameResolverBuilder dnsResolverBuilder;

    private final ConcurrentMap<String, Promise<InetAddress>> resolvesInProgress = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Promise<List<InetAddress>>> resolveAllsInProgress = new ConcurrentHashMap<>();

    public DnsAddressResolverGroup(DnsNameResolverBuilder dnsResolverBuilder) {
        this.dnsResolverBuilder = withSharedCaches(dnsResolverBuilder.copy());
    }

    public DnsAddressResolverGroup(
            Class<? extends DatagramChannel> channelType,
            DnsServerAddressStreamProvider nameServerProvider) {
        this.dnsResolverBuilder = withSharedCaches(new DnsNameResolverBuilder());
        dnsResolverBuilder.datagramChannelType(channelType).nameServerProvider(nameServerProvider);
    }

    public DnsAddressResolverGroup(
            ChannelFactory<? extends DatagramChannel> channelFactory,
            DnsServerAddressStreamProvider nameServerProvider) {
        this.dnsResolverBuilder = withSharedCaches(new DnsNameResolverBuilder());
        dnsResolverBuilder.datagramChannelFactory(channelFactory).nameServerProvider(nameServerProvider);
    }

    private static DnsNameResolverBuilder withSharedCaches(DnsNameResolverBuilder dnsResolverBuilder) {
        /// To avoid each member of the group having its own cache we either use the configured cache
        // or create a new one to share among the entire group.
        return dnsResolverBuilder.resolveCache(dnsResolverBuilder.getOrNewCache())
                .cnameCache(dnsResolverBuilder.getOrNewCnameCache())
                .authoritativeDnsServerCache(dnsResolverBuilder.getOrNewAuthoritativeDnsServerCache());
    }

    @SuppressWarnings("deprecation")
    @Override
    protected final AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        if (!(executor instanceof EventLoop)) {
            throw new IllegalStateException(
                    "unsupported executor type: " + StringUtil.simpleClassName(executor) +
                    " (expected: " + StringUtil.simpleClassName(EventLoop.class));
        }

        // we don't really need to pass channelFactory and nameServerProvider separately,
        // but still keep this to ensure backward compatibility with (potentially) override methods
        EventLoop loop = dnsResolverBuilder.eventLoop;
        return newResolver(loop == null ? (EventLoop) executor : loop,
                dnsResolverBuilder.datagramChannelFactory(),
                dnsResolverBuilder.nameServerProvider());
    }

    /**
     * @deprecated Override {@link #newNameResolver(EventLoop, ChannelFactory, DnsServerAddressStreamProvider)}.
     */
    @Deprecated
    protected AddressResolver<InetSocketAddress> newResolver(
            EventLoop eventLoop, ChannelFactory<? extends DatagramChannel> channelFactory,
            DnsServerAddressStreamProvider nameServerProvider) throws Exception {

        final NameResolver<InetAddress> resolver = new InflightNameResolver<>(
                eventLoop,
                newNameResolver(eventLoop, channelFactory, nameServerProvider),
                resolvesInProgress,
                resolveAllsInProgress);

        return newAddressResolver(eventLoop, resolver);
    }

    /**
     * Creates a new {@link NameResolver}. Override this method to create an alternative {@link NameResolver}
     * implementation or override the default configuration.
     */
    protected NameResolver<InetAddress> newNameResolver(EventLoop eventLoop,
                                                        ChannelFactory<? extends DatagramChannel> channelFactory,
                                                        DnsServerAddressStreamProvider nameServerProvider)
            throws Exception {
        DnsNameResolverBuilder builder = dnsResolverBuilder.copy();

        // once again, channelFactory and nameServerProvider are most probably set in builder already,
        // but I do reassign them again to avoid corner cases with override methods
        return builder.eventLoop(eventLoop)
                .datagramChannelFactory(channelFactory)
                .nameServerProvider(nameServerProvider)
                .build();
    }

    /**
     * Creates a new {@link AddressResolver}. Override this method to create an alternative {@link AddressResolver}
     * implementation or override the default configuration.
     */
    protected AddressResolver<InetSocketAddress> newAddressResolver(EventLoop eventLoop,
                                                                    NameResolver<InetAddress> resolver)
            throws Exception {
        return new InetSocketAddressResolver(eventLoop, resolver);
    }
}
