/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.rsocket.Closeable;
import io.rsocket.DuplexConnection;
import io.rsocket.frame.FrameHeaderCodec;
import io.rsocket.frame.FrameUtil;
import io.rsocket.plugins.DuplexConnectionInterceptor.Type;
import io.rsocket.plugins.InitializingInterceptorRegistry;
import java.net.SocketAddress;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * {@link DuplexConnection#receive()} is a single stream on which the following type of frames
 * arrive:
 *
 * <ul>
 *   <li>Frames for streams initiated by the initiator of the connection (client).
 *   <li>Frames for streams initiated by the acceptor of the connection (server).
 * </ul>
 *
 * <p>The only way to differentiate these two frames is determining whether the stream Id is odd or
 * even. Even IDs are for the streams initiated by server and odds are for streams initiated by the
 * client.
 *
 * @deprecated since 1.1.0-M1 in favor of package-private {@link
 *     io.rsocket.core.ClientServerInputMultiplexer}
 */
@Deprecated
public class ClientServerInputMultiplexer implements Closeable {
  private static final Logger LOGGER = LoggerFactory.getLogger("io.rsocket.FrameLogger");
  private static final InitializingInterceptorRegistry emptyInterceptorRegistry =
      new InitializingInterceptorRegistry();

  private final DuplexConnection setupConnection;
  private final DuplexConnection serverConnection;
  private final DuplexConnection clientConnection;
  private final DuplexConnection source;
  private final DuplexConnection clientServerConnection;

  private boolean setupReceived;

  public ClientServerInputMultiplexer(DuplexConnection source) {
    this(source, emptyInterceptorRegistry, false);
  }

  public ClientServerInputMultiplexer(
      DuplexConnection source, InitializingInterceptorRegistry registry, boolean isClient) {
    this.source = source;
    final MonoProcessor<Flux<ByteBuf>> setup = MonoProcessor.create();
    final MonoProcessor<Flux<ByteBuf>> server = MonoProcessor.create();
    final MonoProcessor<Flux<ByteBuf>> client = MonoProcessor.create();

    source = registry.initConnection(Type.SOURCE, source);
    setupConnection =
        registry.initConnection(Type.SETUP, new InternalDuplexConnection(source, setup));
    serverConnection =
        registry.initConnection(Type.SERVER, new InternalDuplexConnection(source, server));
    clientConnection =
        registry.initConnection(Type.CLIENT, new InternalDuplexConnection(source, client));
    clientServerConnection = new InternalDuplexConnection(source, client, server);

    source
        .receive()
        .groupBy(
            frame -> {
              int streamId = FrameHeaderCodec.streamId(frame);
              final Type type;
              if (streamId == 0) {
                switch (FrameHeaderCodec.frameType(frame)) {
                  case SETUP:
                  case RESUME:
                  case RESUME_OK:
                    type = Type.SETUP;
                    setupReceived = true;
                    break;
                  case LEASE:
                  case KEEPALIVE:
                  case ERROR:
                    type = isClient ? Type.CLIENT : Type.SERVER;
                    break;
                  default:
                    type = isClient ? Type.SERVER : Type.CLIENT;
                }
              } else if ((streamId & 0b1) == 0) {
                type = Type.SERVER;
              } else {
                type = Type.CLIENT;
              }
              if (!isClient && type != Type.SETUP && !setupReceived) {
                frame.release();
                throw new IllegalStateException(
                    "SETUP or LEASE frame must be received before any others.");
              }
              return type;
            })
        .subscribe(
            group -> {
              switch (group.key()) {
                case SETUP:
                  setup.onNext(group);
                  break;

                case SERVER:
                  server.onNext(group);
                  break;

                case CLIENT:
                  client.onNext(group);
                  break;
              }
            },
            ex -> {
              setup.onError(ex);
              server.onError(ex);
              client.onError(ex);
            });
  }

  public DuplexConnection asClientServerConnection() {
    return clientServerConnection;
  }

  public DuplexConnection asServerConnection() {
    return serverConnection;
  }

  public DuplexConnection asClientConnection() {
    return clientConnection;
  }

  public DuplexConnection asSetupConnection() {
    return setupConnection;
  }

  @Override
  public void dispose() {
    source.dispose();
  }

  @Override
  public boolean isDisposed() {
    return source.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    return source.onClose();
  }

  private static class InternalDuplexConnection implements DuplexConnection {
    private final DuplexConnection source;
    private final MonoProcessor<Flux<ByteBuf>>[] processors;
    private final boolean debugEnabled;

    @SafeVarargs
    public InternalDuplexConnection(
        DuplexConnection source, MonoProcessor<Flux<ByteBuf>>... processors) {
      this.source = source;
      this.processors = processors;
      this.debugEnabled = LOGGER.isDebugEnabled();
    }

    @Override
    public Mono<Void> send(Publisher<ByteBuf> frame) {
      if (debugEnabled) {
        frame = Flux.from(frame).doOnNext(f -> LOGGER.debug("sending -> " + FrameUtil.toString(f)));
      }

      return source.send(frame);
    }

    @Override
    public Mono<Void> sendOne(ByteBuf frame) {
      if (debugEnabled) {
        LOGGER.debug("sending -> " + FrameUtil.toString(frame));
      }

      return source.sendOne(frame);
    }

    @Override
    public Flux<ByteBuf> receive() {
      return Flux.fromArray(processors)
          .flatMap(
              p ->
                  p.flatMapMany(
                      f -> {
                        if (debugEnabled) {
                          return f.doOnNext(
                              frame -> LOGGER.debug("receiving -> " + FrameUtil.toString(frame)));
                        } else {
                          return f;
                        }
                      }));
    }

    @Override
    public ByteBufAllocator alloc() {
      return source.alloc();
    }

    @Override
    public SocketAddress remoteAddress() {
      return source.remoteAddress();
    }

    @Override
    public void dispose() {
      source.dispose();
    }

    @Override
    public boolean isDisposed() {
      return source.isDisposed();
    }

    @Override
    public Mono<Void> onClose() {
      return source.onClose();
    }

    @Override
    public double availability() {
      return source.availability();
    }
  }
}
