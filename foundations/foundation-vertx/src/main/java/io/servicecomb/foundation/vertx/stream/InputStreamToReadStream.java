/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.foundation.vertx.stream;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

// copy from AsyncFileImpl and modify
public class InputStreamToReadStream implements ReadStream<Buffer> {
  private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamToReadStream.class);

  public static final int DEFAULT_READ_BUFFER_SIZE = 8192;

  private Vertx vertx;

  private InputStream inputStream;

  private boolean closed;

  private boolean paused;

  private boolean readInProgress;

  private int readBufferSize = DEFAULT_READ_BUFFER_SIZE;

  private Handler<Throwable> exceptionHandler = this::unhandledException;

  private Handler<Buffer> dataHandler;

  private Handler<Void> endHandler;

  public InputStreamToReadStream(Vertx vertx, InputStream inputStream) {
    this.vertx = vertx;
    this.inputStream = inputStream;
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public synchronized InputStreamToReadStream readBufferSize(int readBufferSize) {
    this.readBufferSize = readBufferSize;
    return this;
  }

  private void check() {
    if (closed) {
      throw new IllegalStateException("inputStream is closed");
    }
  }

  private void unhandledException(Throwable t) {
    LOGGER.error("Unhandled exception", t);
  }

  @Override
  public synchronized InputStreamToReadStream exceptionHandler(Handler<Throwable> handler) {
    check();
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public synchronized InputStreamToReadStream handler(Handler<Buffer> handler) {
    check();
    this.dataHandler = handler;
    if (dataHandler != null && !paused && !closed) {
      doRead();
    }
    return this;
  }

  class ReadResult {
    int readed;

    byte[] bytes = new byte[readBufferSize];

    void doRead() throws IOException {
      readed = inputStream.read(bytes);
    }

    Buffer toBuffer() {
      return Buffer.buffer(Unpooled.wrappedBuffer(bytes).writerIndex(readed));
    }
  }

  private synchronized void doRead() {
    if (!readInProgress) {
      readInProgress = true;

      vertx.executeBlocking(this::readInWorker,
          false,
          this::afterReadInEventloop);
    }
  }

  private synchronized void readInWorker(Future<ReadResult> future) {
    try {
      ReadResult readResult = new ReadResult();
      readResult.doRead();
      future.complete(readResult);
    } catch (Throwable e) {
      future.fail(e);
    }
  }

  private synchronized void afterReadInEventloop(AsyncResult<ReadResult> ar) {
    if (ar.failed()) {
      exceptionHandler.handle(ar.cause());
      return;
    }

    readInProgress = false;
    ReadResult readResult = ar.result();
    if (readResult.readed < 0) {
      handleEnd();
      return;
    }

    handleData(readResult.toBuffer());
    if (!paused && dataHandler != null) {
      doRead();
    }
  }

  @Override
  public synchronized InputStreamToReadStream pause() {
    check();
    paused = true;
    return this;
  }

  @Override
  public synchronized InputStreamToReadStream resume() {
    check();
    if (paused && !closed) {
      paused = false;
      if (dataHandler != null) {
        doRead();
      }
    }
    return this;
  }

  private synchronized void handleData(Buffer buffer) {
    if (dataHandler != null) {
      dataHandler.handle(buffer);
    }
  }

  private synchronized void handleEnd() {
    dataHandler = null;
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }

  @Override
  public ReadStream<Buffer> endHandler(Handler<Void> handler) {
    check();
    this.endHandler = handler;
    return this;
  }
}
