//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class IdleTimeoutTest extends AbstractTest
{
    private final int idleTimeout = 1000;

    @Test
    public void testServerEnforcingIdleTimeout() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                latch.countDown();
            }
        });

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                latch.countDown();
            }
        });

        // The request is not replied, and the server should idle timeout.
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerNotEnforcingIdleTimeoutWithinCallback() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                // Stay in the callback for more than idleTimeout,
                // but not for an integer number of idle timeouts,
                // to avoid a race where the idle timeout fires
                // again before we can send the headers to the client.
                sleep(idleTimeout + idleTimeout / 2);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClient(new ServerSessionListener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                replyLatch.countDown();
            }
        });

        assertTrue(replyLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));

        // Just make sure onClose() has never been called, but don't wait too much
        assertFalse(closeLatch.await(idleTimeout / 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeout() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(session.isClosed());
    }

    @Test
    public void testClientEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientNotEnforcingIdleTimeoutWithinCallback() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());

        final CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                // Stay in the callback for more than idleTimeout,
                // but not for an integer number of idle timeouts,
                // to avoid that the idle timeout fires again.
                sleep(idleTimeout + idleTimeout / 2);
                replyLatch.countDown();
            }
        });

        assertFalse(closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(replyLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingStreamIdleTimeout() throws Exception
    {
        final int idleTimeout = 1000;
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                sleep(2 * idleTimeout);
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                dataLatch.countDown();
            }

            @Override
            public boolean onIdleTimeout(Stream stream, Throwable x)
            {
                assertThat(x, Matchers.instanceOf(TimeoutException.class));
                timeoutLatch.countDown();
                return true;
            }
        });

        assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        // We must not receive any DATA frame.
        assertFalse(dataLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Stream must be gone.
        assertTrue(session.getStreams().isEmpty());
        // Session must not be closed, nor disconnected.
        assertFalse(session.isClosed());
        assertFalse(((HTTP2Session)session).isDisconnected());
    }

    @Test
    public void testServerEnforcingStreamIdleTimeout() throws Exception
    {
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public boolean onIdleTimeout(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                        return true;
                    }
                };
            }
        });

        final CountDownLatch resetLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        // Stream does not end here, but we won't send any DATA frame.
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });

        assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        // Stream must be gone.
        assertTrue(session.getStreams().isEmpty());
        // Session must not be closed, nor disconnected.
        assertFalse(session.isClosed());
        assertFalse(((HTTP2Session)session).isDisconnected());
    }

    @Test
    public void testServerStreamIdleTimeoutIsNotEnforcedWhenReceiving() throws Exception
    {
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public boolean onIdleTimeout(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                        return true;
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        final Stream stream = promise.get(5, TimeUnit.SECONDS);

        sleep(idleTimeout / 2);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), new Callback()
        {
            private int sends;

            @Override
            public void succeeded()
            {
                sleep(idleTimeout / 2);
                final boolean last = ++sends == 2;
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), last), !last ? this : new Callback()
                {
                    @Override
                    public InvocationType getInvocationType()
                    {
                        return InvocationType.NON_BLOCKING;
                    }

                    @Override
                    public void succeeded()
                    {
                        // Idle timeout should not fire while the server is receiving.
                        assertEquals(1, timeoutLatch.getCount());
                        dataLatch.countDown();
                    }
                });
            }
        });

        assertTrue(dataLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        // The server did not send a response, so it will eventually timeout.
        assertTrue(timeoutLatch.await(5 * idleTimeout, TimeUnit.SECONDS));
    }

    @Test
    public void testClientStreamIdleTimeoutIsNotEnforcedWhenSending() throws Exception
    {
        final CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
                super.succeeded(stream);
            }
        };
        session.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        final Stream stream = promise.get(5, TimeUnit.SECONDS);

        Callback.Completable completable1 = new Callback.Completable();
        sleep(idleTimeout / 2);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), completable1);
        completable1.thenCompose(nil ->
        {
            Callback.Completable completable2 = new Callback.Completable();
            sleep(idleTimeout / 2);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), completable2);
            return completable2;
        }).thenRun(() ->
        {
            sleep(idleTimeout / 2);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), true), Callback.NOOP);
        });

        assertFalse(resetLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testBufferedReadsResetStreamIdleTimeout() throws Exception
    {
        int bufferSize = 8192;
        long delay = 1000;
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                ServletInputStream input = request.getInputStream();
                byte[] buffer = new byte[bufferSize];
                while (true)
                {
                    int read = input.read(buffer);
                    LoggerFactory.getLogger(IdleTimeoutTest.class).info("Read {} bytes", read);
                    if (read < 0)
                        break;
                    sleep(delay);
                }
            }
        });
        // The timeout is going to be reset each time a DATA frame is fully consumed, hence
        // every 2 loops in the above servlet. So the IdleTimeout must be greater than (2 * delay)
        // to make sure it does not fire spuriously.
        connector.setIdleTimeout(3 * delay);

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Send data larger than the flow control window.
        // The client will send bytes up to the flow control window immediately
        // and they will be buffered by the server; the Servlet will consume them slowly.
        // Servlet reads should reset the idle timeout.
        int contentLength = FlowControlStrategy.DEFAULT_WINDOW_SIZE + 1;
        ByteBuffer data = ByteBuffer.allocate(contentLength);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(2 * (contentLength / bufferSize + 1) * delay, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerIdleTimeoutIsEnforcedForQueuedRequest() throws Exception
    {
        long idleTimeout = 2000;
        // Use a small thread pool to cause request queueing.
        QueuedThreadPool serverExecutor = new QueuedThreadPool(5);
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        h2.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setStreamIdleTimeout(idleTimeout);
        connector = new ServerConnector(server, 1, 1, h2);
        connector.setIdleTimeout(10 * idleTimeout);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        AtomicReference<CountDownLatch> phaser = new AtomicReference<>();
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                phaser.get().countDown();

                // Hold the dispatched requests enough for the idle requests to idle timeout.
                sleep(2 * idleTimeout);
            }
        }), servletPath + "/*");
        server.start();

        prepareClient();
        client.start();

        Session client = newClient(new Session.Listener.Adapter());

        // Send requests until one is queued on the server but not dispatched.
        while (true)
        {
            phaser.set(new CountDownLatch(1));

            MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, new Stream.Listener.Adapter());
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            ByteBuffer data = ByteBuffer.allocate(10);
            stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

            if (!phaser.get().await(1, TimeUnit.SECONDS))
                break;
        }

        // Send one more request to consume the whole session flow control window.
        CountDownLatch resetLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(((ISession)client).updateSendWindow(0));
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(resetLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Wait for WINDOW_UPDATEs to be processed by the client.
        sleep(1000);

        assertThat(((ISession)client).updateSendWindow(0), Matchers.greaterThan(0));
    }

    @Test
    public void testDisableStreamIdleTimeout() throws Exception
    {
        // Set the stream idle timeout to a negative value to disable it.
        long streamIdleTimeout = -1;
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true));
                        }
                    }
                };
            }
        }, h2 -> h2.setStreamIdleTimeout(streamIdleTimeout));
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch responseLatch = new CountDownLatch(2);
        CountDownLatch resetLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData1 = newRequest("GET", "/1", HttpFields.EMPTY);
        Stream stream1 = session.newStream(new HeadersFrame(metaData1, null, false), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);

        MetaData.Request metaData2 = newRequest("GET", "/2", HttpFields.EMPTY);
        Stream stream2 = session.newStream(new HeadersFrame(metaData2, null, false), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        // Keep the connection busy with the stream2, stream1 must not idle timeout.
        for (int i = 0; i < 3; ++i)
        {
            Thread.sleep(idleTimeout / 2);
            stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(64), false));
        }

        // Stream1 must not have idle timed out.
        assertFalse(resetLatch.await(idleTimeout / 2, TimeUnit.MILLISECONDS));

        // Finish the streams.
        stream1.data(new DataFrame(stream1.getId(), ByteBuffer.allocate(128), true));
        stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(64), true));

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private void sleep(long value)
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(value);
        }
        catch (InterruptedException x)
        {
            fail(x);
        }
    }
}
