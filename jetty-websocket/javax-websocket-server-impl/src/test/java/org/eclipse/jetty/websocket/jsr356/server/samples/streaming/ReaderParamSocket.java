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

package org.eclipse.jetty.websocket.jsr356.server.samples.streaming;

import java.io.IOException;
import java.io.Reader;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.server.StackUtil;

@ServerEndpoint("/echo/streaming/readerparam/{param}")
public class ReaderParamSocket
{
    private static final Logger LOG = Log.getLogger(ReaderParamSocket.class);

    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
    }

    @OnMessage
    public void onReader(Reader reader, @PathParam("param") String param) throws IOException
    {
        StringBuilder msg = new StringBuilder();
        msg.append(IO.toString(reader));
        msg.append('|');
        msg.append(param);
        session.getAsyncRemote().sendText(msg.toString());
    }

    @OnError
    public void onError(Throwable cause) throws IOException
    {
        LOG.warn("Error",cause);
        session.getBasicRemote().sendText("Exception: " + StackUtil.toString(cause));
    }
}
