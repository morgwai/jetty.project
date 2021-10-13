//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.client;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;

public class QuicClientConnectorConfigurator extends ClientConnector.Configurator
{
    public static final String CONNECTION_CONFIGURATOR_CONTEXT_KEY = QuicClientConnectorConfigurator.class.getSimpleName() + ".connectionConfigurator";

    private final UnaryOperator<Connection> connectionConfigurator;

    public QuicClientConnectorConfigurator()
    {
        this(UnaryOperator.identity());
    }

    public QuicClientConnectorConfigurator(UnaryOperator<Connection> connectionConfigurator)
    {
        this.connectionConfigurator = Objects.requireNonNull(connectionConfigurator);
    }

    @Override
    public boolean isIntrinsicallySecure(ClientConnector clientConnector, SocketAddress address)
    {
        return true;
    }

    @Override
    public ChannelWithAddress newChannelWithAddress(ClientConnector clientConnector, SocketAddress address, Map<String, Object> context) throws IOException
    {
        context.putIfAbsent(CONNECTION_CONFIGURATOR_CONTEXT_KEY, connectionConfigurator);
        DatagramChannel channel = DatagramChannel.open();
        return new ChannelWithAddress(channel, address);
    }

    @Override
    public EndPoint newEndPoint(ClientConnector clientConnector, SocketAddress address, SelectableChannel selectable, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new DatagramChannelEndPoint((DatagramChannel)selectable, selector, selectionKey, clientConnector.getScheduler());
    }

    @Override
    public Connection newConnection(ClientConnector clientConnector, SocketAddress address, EndPoint endPoint, Map<String, Object> context)
    {
        @SuppressWarnings("unchecked")
        UnaryOperator<Connection> configurator = (UnaryOperator<Connection>)context.get(CONNECTION_CONFIGURATOR_CONTEXT_KEY);
        if (configurator == null)
            configurator = UnaryOperator.identity();
        return configurator.apply(new ClientQuicConnection(clientConnector.getExecutor(), clientConnector.getScheduler(), clientConnector.getByteBufferPool(), endPoint, context));
    }
}