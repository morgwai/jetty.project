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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;

/**
 * {@link ContentDecoder} for the "gzip" encoding.
 */
public class GZIPContentDecoder extends org.eclipse.jetty.http.GZIPContentDecoder implements ContentDecoder
{
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private long decodedLength;

    public GZIPContentDecoder()
    {
        this(DEFAULT_BUFFER_SIZE);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null, bufferSize);
    }

    public GZIPContentDecoder(ByteBufferPool byteBufferPool, int bufferSize)
    {
        super(byteBufferPool, bufferSize);
    }

    @Override
    public void beforeDecoding(HttpExchange exchange)
    {
        HttpFields.Mutable headers = exchange.getResponse().headers();
        // Content-Length is not valid anymore while we are decoding.
        headers.remove(HttpHeader.CONTENT_LENGTH);
        // Content-Encoding should be removed/modified as the content will be decoded.
        ListIterator<HttpField> fields = headers.listIterator();
        while (fields.hasNext())
        {
            HttpField field = fields.next();
            if (field.getHeader() == HttpHeader.CONTENT_ENCODING)
            {
                String value = Arrays.stream(field.getValues())
                    .filter(encoding -> !"gzip".equalsIgnoreCase(encoding))
                    .collect(Collectors.joining(", "));
                if (value.isEmpty())
                    fields.remove();
                else
                    fields.set(new HttpField(HttpHeader.CONTENT_ENCODING, value));
                break;
            }
        }
    }

    @Override
    protected boolean decodedChunk(ByteBuffer chunk)
    {
        decodedLength += chunk.remaining();
        super.decodedChunk(chunk);
        return true;
    }

    @Override
    public void afterDecoding(HttpExchange exchange)
    {
        HttpFields.Mutable headers = exchange.getResponse().headers();
        headers.remove(HttpHeader.TRANSFER_ENCODING);
        headers.putLongField(HttpHeader.CONTENT_LENGTH, decodedLength);
    }

    /**
     * Specialized {@link ContentDecoder.Factory} for the "gzip" encoding.
     */
    public static class Factory extends ContentDecoder.Factory
    {
        private final int bufferSize;
        private final ByteBufferPool byteBufferPool;

        public Factory()
        {
            this(DEFAULT_BUFFER_SIZE);
        }

        public Factory(int bufferSize)
        {
            this(null, bufferSize);
        }

        public Factory(ByteBufferPool byteBufferPool)
        {
            this(byteBufferPool, DEFAULT_BUFFER_SIZE);
        }

        public Factory(ByteBufferPool byteBufferPool, int bufferSize)
        {
            super("gzip");
            this.byteBufferPool = byteBufferPool;
            this.bufferSize = bufferSize;
        }

        @Override
        public ContentDecoder newContentDecoder()
        {
            return new GZIPContentDecoder(byteBufferPool, bufferSize);
        }
    }
}
