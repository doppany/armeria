/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client;

import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2GoAwayHandler;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;

final class Http2ResponseDecoder extends HttpResponseDecoder implements Http2Connection.Listener,
                                                                        Http2FrameListener {

    private static final Logger logger = LoggerFactory.getLogger(Http2ResponseDecoder.class);

    private final Http2Connection conn;
    private final Http2ConnectionEncoder encoder;
    private final Http2GoAwayHandler goAwayHandler;
    private final KeepAliveHandler keepAliveHandler;

    Http2ResponseDecoder(Channel channel, Http2ConnectionEncoder encoder, HttpClientFactory clientFactory,
                         KeepAliveHandler keepAliveHandler) {
        super(channel,
              InboundTrafficController.ofHttp2(channel, clientFactory.http2InitialConnectionWindowSize()));
        conn = encoder.connection();
        this.encoder = encoder;
        assert keepAliveHandler instanceof Http2ClientKeepAliveHandler ||
               keepAliveHandler instanceof NoopKeepAliveHandler;
        this.keepAliveHandler = keepAliveHandler;
        goAwayHandler = new Http2GoAwayHandler();
    }

    @Override
    HttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, @Nullable ClientRequestContext ctx,
            EventLoop eventLoop, long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper resWrapper =
                super.addResponse(id, res, ctx, eventLoop, responseTimeoutMillis, maxContentLength);

        resWrapper.whenComplete().handle((unused, cause) -> {
            if (eventLoop.inEventLoop()) {
                onWrapperCompleted(resWrapper, id, cause);
            } else {
                eventLoop.execute(() -> onWrapperCompleted(resWrapper, id, cause));
            }
            return null;
        });
        return resWrapper;
    }

    private void onWrapperCompleted(HttpResponseWrapper resWrapper, int id, @Nullable Throwable cause) {
        // Cancel timeout future and abort the request if it exists.
        resWrapper.onSubscriptionCancelled(cause);

        if (cause != null) {
            // We are not closing the connection but just send a RST_STREAM,
            // so we have to remove the response manually.
            removeResponse(id);

            // Reset the stream.
            final int streamId = idToStreamId(id);
            final int lastStreamId = conn.local().lastStreamKnownByPeer();
            if (lastStreamId < 0 || // Did not receive a GOAWAY yet or
                streamId <= lastStreamId) { // received a GOAWAY and the request's streamId <= lastStreamId
                final ChannelHandlerContext ctx = channel().pipeline().lastContext();
                if (ctx != null) {
                    encoder.writeRstStream(ctx, streamId, Http2Error.CANCEL.code(), ctx.newPromise());
                    ctx.flush();
                } else {
                    // The pipeline has been cleaned up due to disconnection.
                }
            }
        }
    }

    Http2GoAwayHandler goAwayHandler() {
        return goAwayHandler;
    }

    @Override
    public void onStreamAdded(Http2Stream stream) {}

    @Override
    public void onStreamActive(Http2Stream stream) {}

    @Override
    public void onStreamHalfClosed(Http2Stream stream) {}

    @Override
    public void onStreamClosed(Http2Stream stream) {
        goAwayHandler.onStreamClosed(channel(), stream);

        final HttpResponseWrapper res = getResponse(streamIdToId(stream.id()), true);
        if (res == null) {
            return;
        }

        if (!goAwayHandler.receivedGoAway()) {
            res.close(ClosedStreamException.get());
            return;
        }

        final int lastStreamId = conn.local().lastStreamKnownByPeer();
        if (stream.id() > lastStreamId) {
            res.close(UnprocessedRequestException.of(GoAwayReceivedException.get()));
        } else {
            res.close(ClosedStreamException.get());
        }

        if (shouldSendGoAway()) {
            channel().close();
        }
    }

    @Override
    public void onStreamRemoved(Http2Stream stream) {}

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
        disconnectWhenFinished();
        goAwayHandler.onGoAwaySent(channel(), lastStreamId, errorCode, debugData);
    }

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        // Should not reuse a connection that received a GOAWAY frame.
        HttpSession.get(channel()).deactivate();
        disconnectWhenFinished();
        goAwayHandler.onGoAwayReceived(channel(), lastStreamId, errorCode, debugData);
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
        ctx.fireChannelRead(settings);
    }

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) {}

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                              boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead();
        final HttpResponseWrapper res = getResponse(streamIdToId(streamId), endOfStream);
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late HEADERS frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
                return;
            }

            throw connectionError(PROTOCOL_ERROR, "received a HEADERS frame for an unknown stream: %d",
                                  streamId);
        }

        res.logResponseFirstBytesTransferred();

        final HttpHeaders converted = ArmeriaHttpUtil.toArmeria(headers, false, endOfStream);
        try {
            res.initTimeout();
            res.write(converted);
        } catch (Throwable t) {
            res.close(t);
            throw connectionError(INTERNAL_ERROR, t, "failed to consume a HEADERS frame");
        }

        if (endOfStream) {
            res.close();

            if (shouldSendGoAway()) {
                channel().close();
            }
        }
    }

    @Override
    public void onHeadersRead(
            ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
            short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {

        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public int onDataRead(
            ChannelHandlerContext ctx, int streamId, ByteBuf data,
            int padding, boolean endOfStream) throws Http2Exception {
        keepAliveChannelRead();

        final int dataLength = data.readableBytes();
        final HttpResponseWrapper res = getResponse(streamIdToId(streamId), endOfStream);
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late DATA frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
                return dataLength + padding;
            }

            throw connectionError(PROTOCOL_ERROR, "received a DATA frame for an unknown stream: %d",
                                  streamId);
        }

        final long maxContentLength = res.maxContentLength();
        final long writtenBytes = res.writtenBytes();
        if (maxContentLength > 0 && writtenBytes > maxContentLength - dataLength) {
            final long transferred = LongMath.saturatedAdd(writtenBytes, dataLength);
            res.close(ContentTooLargeException.builder()
                                              .maxContentLength(maxContentLength)
                                              .contentLength(res.headers())
                                              .transferred(transferred)
                                              .build());
            throw connectionError(
                    INTERNAL_ERROR,
                    "content too large: transferred(%d + %d) > limit(%d) (stream: %d)",
                    writtenBytes, dataLength, maxContentLength, streamId);
        }

        try {
            res.write(HttpData.wrap(data.retain()).withEndOfStream(endOfStream));
        } catch (Throwable t) {
            res.close(t);
            throw connectionError(INTERNAL_ERROR, t, "failed to consume a DATA frame");
        }

        if (endOfStream) {
            res.close();

            if (shouldSendGoAway()) {
                // The connection has reached its lifespan.
                // Should send a GOAWAY frame if it did not receive or send a GOAWAY frame.
                channel().close();
            }
        }

        // All bytes have been processed.
        return dataLength + padding;
    }

    /**
     * Returns {@code true} if a connection has reached its lifespan
     * and the connection did not receive or send a GOAWAY frame.
     */
    private boolean shouldSendGoAway() {
        return needsToDisconnectNow() && !goAwayHandler.sentGoAway() && !goAwayHandler.receivedGoAway();
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        keepAliveChannelRead();
        final HttpResponseWrapper res = removeResponse(streamIdToId(streamId));
        if (res == null) {
            if (conn.streamMayHaveExisted(streamId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} Received a late RST_STREAM frame for a closed stream: {}",
                                 ctx.channel(), streamId);
                }
            } else {
                throw connectionError(PROTOCOL_ERROR,
                                      "received a RST_STREAM frame for an unknown stream: %d", streamId);
            }
            return;
        }

        final Http2Error http2Error = Http2Error.valueOf(errorCode);
        final ClosedStreamException cause =
                new ClosedStreamException("received a RST_STREAM frame: " + http2Error);

        if (http2Error == Http2Error.REFUSED_STREAM) {
            res.close(UnprocessedRequestException.of(cause));
        } else {
            res.close(cause);
        }
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) {}

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight,
                               boolean exclusive) {}

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) {
        keepAliveHandler.onPing();
    }

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, long data) {
        if (keepAliveHandler.isHttp2()) {
            keepAliveHandler.onPingAck(data);
        }
    }

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {}

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {}

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags,
                               ByteBuf payload) {}

    @Override
    KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    private void keepAliveChannelRead() {
        if (keepAliveHandler != null) {
            keepAliveHandler.onReadOrWrite();
        }
    }

    private static int streamIdToId(int streamId) {
        return streamId - 1 >>> 1;
    }

    private static int idToStreamId(int id) {
        return (id << 1) + 1;
    }
}
