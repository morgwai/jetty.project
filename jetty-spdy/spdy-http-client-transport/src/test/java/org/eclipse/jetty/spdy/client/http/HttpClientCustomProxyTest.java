//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.client.http;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.PushStrategy;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientCustomProxyTest
{
    public static final byte[] CAFE_BABE = new byte[]{(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE};

    private Server server;
    private ServerConnector connector;
    private SPDYClient.Factory factory;
    private HttpClient httpClient;

    public void prepare(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new CAFEBABEServerConnectionFactory(new HTTPSPDYServerConnectionFactory(SPDY.V3, new HttpConfiguration(), new PushStrategy.None())));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");

        factory = new SPDYClient.Factory(executor);
        factory.start();

        httpClient = new HttpClient(new HttpClientTransportOverSPDY(factory.newSPDYClient(SPDY.V3)), null);
        httpClient.setExecutor(executor);
        httpClient.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (httpClient != null)
            httpClient.stop();
        if (factory != null)
            factory.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testCustomProxy() throws Exception
    {
        final String serverHost = "server";
        final int status = HttpStatus.NO_CONTENT_204;
        prepare(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (!URI.create(baseRequest.getUri().toString()).isAbsolute())
                    response.setStatus(HttpServletResponse.SC_USE_PROXY);
                else if (serverHost.equals(request.getServerName()))
                    response.setStatus(status);
                else
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            }
        });

        // Setup the custom proxy
        int proxyPort = connector.getLocalPort();
        int serverPort = proxyPort + 1; // Any port will do for these tests - just not the same as the proxy
        httpClient.getProxyConfiguration().getProxies().add(new CAFEBABEProxy(new Origin.Address("localhost", proxyPort), false));

        ContentResponse response = httpClient.newRequest(serverHost, serverPort)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response.getStatus());
    }

    private class CAFEBABEProxy extends ProxyConfiguration.Proxy
    {
        private CAFEBABEProxy(Origin.Address address, boolean secure)
        {
            super(address, secure);
        }

        @Override
        public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            return new CAFEBABEClientConnectionFactory(connectionFactory);
        }
    }

    private static class CAFEBABEClientConnectionFactory implements ClientConnectionFactory
    {
        private final ClientConnectionFactory connectionFactory;

        private CAFEBABEClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            return new CAFEBABEConnection(endPoint, executor, connectionFactory, context);
        }
    }

    private static class CAFEBABEConnection extends AbstractConnection
    {
        private final ClientConnectionFactory connectionFactory;
        private final Map<String, Object> context;

        public CAFEBABEConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, Map<String, Object> context)
        {
            super(endPoint, executor);
            this.connectionFactory = connectionFactory;
            this.context = context;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
            getEndPoint().write(new Callback.Adapter(), ByteBuffer.wrap(CAFE_BABE));
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = BufferUtil.allocate(4);
                int filled = getEndPoint().fill(buffer);
                Assert.assertEquals(4, filled);
                Assert.assertArrayEquals(CAFE_BABE, buffer.array());

                // We are good, upgrade the connection
                ClientConnectionFactory.Helper.replaceConnection(this, connectionFactory.newConnection(getEndPoint(), context));
            }
            catch (Throwable x)
            {
                close();
                @SuppressWarnings("unchecked")
                Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
                promise.failed(x);
            }
        }
    }

    private class CAFEBABEServerConnectionFactory extends AbstractConnectionFactory
    {
        private final org.eclipse.jetty.server.ConnectionFactory connectionFactory;

        private CAFEBABEServerConnectionFactory(org.eclipse.jetty.server.ConnectionFactory connectionFactory)
        {
            super("cafebabe");
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(Connector connector, EndPoint endPoint)
        {
            return new CAFEBABEServerConnection(connector, endPoint, connectionFactory);
        }
    }

    private class CAFEBABEServerConnection extends AbstractConnection
    {
        private final org.eclipse.jetty.server.ConnectionFactory connectionFactory;

        public CAFEBABEServerConnection(Connector connector, EndPoint endPoint, org.eclipse.jetty.server.ConnectionFactory connectionFactory)
        {
            super(endPoint, connector.getExecutor());
            this.connectionFactory = connectionFactory;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = BufferUtil.allocate(4);
                int filled = getEndPoint().fill(buffer);
                Assert.assertEquals(4, filled);
                Assert.assertArrayEquals(CAFE_BABE, buffer.array());
                getEndPoint().write(new Callback.Adapter(), buffer);

                // We are good, upgrade the connection
                ClientConnectionFactory.Helper.replaceConnection(this, connectionFactory.newConnection(connector, getEndPoint()));
            }
            catch (Throwable x)
            {
                close();
            }
        }
    }
}
