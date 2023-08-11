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

package org.eclipse.jetty.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Computes String representations of Dates then caches the results so
 * that subsequent requests within the same second will be fast.
 * <p>
 * When formatting with the cache, sub second formatting will not be precise
 * and may use a cached result.
 * <p>
 * If consecutive calls are frequently very different, then this
 * may be a little slower than a normal DateFormat.
 * <p>
 * @see DateTimeFormatter for date formatting patterns.
 */
public class DateCache
{
    public static final String DEFAULT_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";
    private static final String MS_CONSTANT = "@??@";

    private final String _formatString;
    private final DateTimeFormatter _tzFormat;
    private final ZoneId _zoneId;
    private final int _msIndex;

    protected volatile Tick _tick;

    public class Tick
    {
        private final long _seconds;
        private final String _prefix;
        private final String _suffix;

        public Tick(long seconds, String string)
        {
            _seconds = seconds;

            if (_msIndex < 0)
            {
                _prefix = string;
                _suffix = null;
            }
            else
            {
                // We can't use _msIndex because the size of format string is not always equal to size of the formatted result.
                int index = string.indexOf(MS_CONSTANT);
                _prefix = string.substring(0, index);
                _suffix = string.substring(index + MS_CONSTANT.length());
            }

        }

        public long getSeconds()
        {
            return _seconds;
        }

        public String getString(long inDate)
        {
            if (_msIndex < 0)
                return _prefix;

            long ms = inDate % 1000;
            StringBuilder sb = new StringBuilder();
            sb.append(_prefix);
            if (ms < 10)
                sb.append("00").append(ms);
            else if (ms < 100)
                sb.append('0').append(ms);
            else
                sb.append(ms);
            sb.append(_suffix);
            return sb.toString();
        }
    }

    /**
     * Make a DateCache that will use a default format.
     * The default format generates the same results as Date.toString().
     */
    public DateCache()
    {
        this(DEFAULT_FORMAT);
    }

    /**
     * Make a DateCache that will use the given format.
     *
     * @param format the format to use
     */
    public DateCache(String format)
    {
        this(format, null, TimeZone.getDefault());
    }

    public DateCache(String format, Locale l)
    {
        this(format, l, TimeZone.getDefault());
    }

    public DateCache(String format, Locale l, String tz)
    {
        this(format, l, TimeZone.getTimeZone(tz));
    }

    public DateCache(String format, Locale l, TimeZone tz)
    {
        _formatString = format;
        _zoneId = tz.toZoneId();
        _tick = null;

        _msIndex = format.indexOf("SSS");
        if (_msIndex >= 0)
            format = format.substring(0, _msIndex) + MS_CONSTANT + format.substring(_msIndex + 3);

        if (l == null)
            _tzFormat = DateTimeFormatter.ofPattern(format).withZone(_zoneId);
        else
            _tzFormat = DateTimeFormatter.ofPattern(format, l).withZone(_zoneId);
    }

    public TimeZone getTimeZone()
    {
        return TimeZone.getTimeZone(_zoneId);
    }

    /**
     * Format a date according to our stored formatter.
     * If it happens to be in the same second as the last
     * formatNow call, then the format is reused.
     *
     * @param inDate the Date.
     * @return Formatted date.
     */
    public String format(Date inDate)
    {
        return format(inDate.getTime());
    }

    /**
     * Format a date according to our stored formatter.
     * If it happens to be in the same second as the last
     * formatNow call, then the format is reused.
     *
     * @param inDate the date in milliseconds since unix epoch.
     * @return Formatted date.
     */
    public String format(long inDate)
    {
        return formatTick(inDate).getString(inDate);
    }

    /**
     * Format a date according to our stored formatter without using the cache.
     *
     * @param inDate the date in milliseconds since unix epoch.
     * @return Formatted date.
     */
    public String formatWithoutCache(long inDate)
    {
        return _tzFormat.format(Instant.ofEpochMilli(inDate));
    }

    /**
     * Format a date according to our stored formatter.
     * The passed time is expected to be close to the current time, so it is
     * compared to the last value passed and if it is within the same second,
     * the format is reused. Otherwise, a new cached format is created.
     *
     * @param now the milliseconds since unix epoch
     * @return Formatted date
     * @deprecated use {@link #format(long)}
     */
    @Deprecated
    public String formatNow(long now)
    {
        return format(now);
    }

    @Deprecated
    public String now()
    {
        return formatNow(System.currentTimeMillis());
    }

    @Deprecated
    public Tick tick()
    {
        return formatTick(System.currentTimeMillis());
    }

    protected Tick formatTick(long inDate)
    {
        long seconds = inDate / 1000;

        Tick tick = _tick;
        // recheck the tick, to save multiple formats
        if (tick == null || tick._seconds != seconds)
        {
            String s = formatWithoutCache(inDate);
            _tick = new Tick(seconds, s);
            tick = _tick;
        }
        return tick;
    }

    public String getFormatString()
    {
        return _formatString;
    }
}
