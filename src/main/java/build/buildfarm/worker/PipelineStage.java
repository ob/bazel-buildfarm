// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.worker;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import com.google.common.base.Stopwatch;
import java.util.logging.Logger;

public abstract class PipelineStage implements Runnable {
  protected final String name;
  protected final WorkerContext workerContext;
  protected final PipelineStage output;
  private final PipelineStage error;

  private PipelineStage input = null;
  protected boolean claimed = false;
  private boolean closed = false;

  PipelineStage(String name, WorkerContext workerContext, PipelineStage output, PipelineStage error) {
    this.name = name;
    this.workerContext = workerContext;
    this.output = output;
    this.error = error;
  }

  public void setInput(PipelineStage input) {
    this.input = input;
  }

  private void runInterruptible() throws InterruptedException {
    while (!output.isClosed() || isClaimed()) {
      iterate();
    }
  }

  @Override
  public void run() {
    try {
      runInterruptible();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      close();
    }
  }

  protected void iterate() throws InterruptedException {
    OperationContext operationContext;
    OperationContext nextOperationContext = null;
    long stallUSecs = 0;
    Stopwatch stopwatch = Stopwatch.createUnstarted();
    try {
      operationContext = take();
      logStart(operationContext.operation.getName());
      stopwatch.start();
      nextOperationContext = tick(operationContext);
      long tickUSecs = stopwatch.elapsed(MICROSECONDS);
      if (nextOperationContext != null && output.claim()) {
        output.put(nextOperationContext);
      } else {
        error.put(operationContext);
      }
      stallUSecs = stopwatch.elapsed(MICROSECONDS) - tickUSecs;
    } finally {
      release();
    }
    after(operationContext);
    long usecs = stopwatch.elapsed(MICROSECONDS);
    logComplete(operationContext.operation.getName(), usecs, stallUSecs, nextOperationContext != null);
  }

  private String logIterateId(String operationName) {
    return String.format("%s::iterate(%s)", name, operationName);
  }

  protected void logStart() {
    logStart("");
  }

  protected void logStart(String operationName) {
    logStart(operationName, "Starting");
  }

  protected void logStart(String operationName, String message) {
    getLogger().fine(String.format("%s: %s", logIterateId(operationName), message));
  }

  protected void logComplete(String operationName, long usecs, long stallUSecs, boolean success) {
    logComplete(operationName, usecs, stallUSecs, success ? "Success" : "Failed");
  }

  protected void logComplete(String operationName, long usecs, long stallUSecs, String status) {
    getLogger().fine(String.format(
        "%s: %gms (%gms stalled) %s",
        logIterateId(operationName),
        usecs / 1000.0f,
        stallUSecs / 1000.0f,
        status));
  }

  protected OperationContext tick(OperationContext operationContext) throws InterruptedException {
    return operationContext;
  }

  protected void after(OperationContext operationContext) { }

  public synchronized boolean claim() throws InterruptedException {
    while (!closed && claimed) {
      wait();
    }
    if (closed) {
      return false;
    }
    claimed = true;
    return true;
  }

  public synchronized void release() {
    claimed = false;
    notify();
  }

  public void close() {
    closed = true;
  }

  public boolean isClosed() {
    return closed;
  }

  protected boolean isClaimed() {
    return claimed;
  }

  public PipelineStage output() {
    return this.output;
  }

  public PipelineStage error() {
    return this.error;
  }

  abstract Logger getLogger();
  abstract OperationContext take() throws InterruptedException;
  abstract void put(OperationContext operationContext) throws InterruptedException;
}
