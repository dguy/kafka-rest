/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package io.confluent.kafkarest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.confluent.kafkarest.entities.ConsumerRecord;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.message.MessageAndMetadata;

/**
 * Worker thread for consumers that multiplexes multiple consumer operations onto a single thread.
 */
public class ConsumerWorker extends Thread {

  private static final Logger log = LoggerFactory.getLogger(ConsumerWorker.class);

  KafkaRestConfig config;

  AtomicBoolean isRunning = new AtomicBoolean(true);
  CountDownLatch shutdownLatch = new CountDownLatch(1);

  Queue<ReadTask> tasks = new LinkedList<ReadTask>();
  Queue<ReadTask> waitingTasks =
      new PriorityQueue<ReadTask>(1, new ReadTaskExpirationComparator());

  public ConsumerWorker(KafkaRestConfig config) {
    this.config = config;
  }

  public synchronized Future readTopic(ConsumerState state, String topic, long maxBytes,
                                       ReadCallback callback) {
    log.trace("Consumer worker " + this.toString() + " reading topic " + topic
              + " for " + state.getId());
    ReadTask task = new ReadTask(state, topic, maxBytes, callback);
    if (!task.isDone()) {
      tasks.add(task);
      this.notifyAll();
    }
    return task;
  }

  @Override
  public void run() {
    while (isRunning.get()) {
      ReadTask task = null;
      synchronized (this) {
        if (tasks.isEmpty()) {
          try {
            long now = config.time.milliseconds();
            long nextExpiration = nextWaitingExpiration();
            if (nextExpiration > now) {
              long timeout = (nextExpiration == Long.MAX_VALUE ?
                              0 : nextExpiration - now);
              assert (timeout >= 0);
              config.time.waitOn(this, timeout);
            }
          } catch (InterruptedException e) {
            // Indication of shutdown
          }
        }

        long now = config.time.milliseconds();
        while (nextWaitingExpiration() <= now) {
          tasks.add(waitingTasks.remove());
        }

        task = tasks.poll();
        if (task != null) {
          boolean backoff = task.doPartialRead();
          if (!task.isDone()) {
            if (backoff) {
              waitingTasks.add(task);
            } else {
              tasks.add(task);
            }
          }
        }
      }
    }
    shutdownLatch.countDown();
  }

  private long nextWaitingExpiration() {
    if (waitingTasks.isEmpty()) {
      return Long.MAX_VALUE;
    } else {
      return waitingTasks.peek().waitExpiration;
    }
  }

  public void shutdown() {
    try {
      isRunning.set(false);
      this.interrupt();
      shutdownLatch.await();
    } catch (InterruptedException e) {
      log.error("Interrupted while "
                + "consumer worker thread.");
      throw new Error("Interrupted when shutting down consumer worker thread.");
    }
  }

  public interface ReadCallback {

    public void onCompletion(List<ConsumerRecord> records);
  }

  private class ReadTask implements Future<List<ConsumerRecord>> {

    ConsumerState state;
    String topic;
    final long maxResponseBytes;
    ReadCallback callback;
    CountDownLatch finished;

    ConsumerState.TopicState topicState;
    ConsumerIterator<byte[], byte[]> iter;
    List<ConsumerRecord> messages;
    private long bytesConsumed = 0;
    final long started;

    // Expiration if this task is waiting, considering both the expiration of the whole task and
    // a single backoff, if one is in progress
    long waitExpiration;

    public ReadTask(ConsumerState state, String topic, long maxBytes, ReadCallback callback) {
      this.state = state;
      this.topic = topic;
      this.maxResponseBytes = Math.min(
          maxBytes,
          config.getLong(KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG));
      this.callback = callback;
      this.finished = new CountDownLatch(1);

      started = config.time.milliseconds();
      topicState = state.getOrCreateTopicState(topic);
      if (topicState == null) {
        finish();
        return;
      }
    }

    /**
     * Performs one iteration of reading from a consumer iterator.
     *
     * @return true if this read timed out, indicating the scheduler should back off
     */
    public boolean doPartialRead() {
      try {
        // Initial setup requires locking, which must be done on this thread.
        if (iter == null) {
          state.startRead(topicState);
          iter = topicState.stream.iterator();
          messages = new Vector<ConsumerRecord>();
          waitExpiration = 0;
        }

        boolean backoff = false;

        long startedIteration = config.time.milliseconds();
        final int requestTimeoutMs = config.getInt(
            KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG);
        try {
          // Read off as many messages as we can without triggering a timeout exception. The
          // consumer timeout should be set very small, so the expectation is that even in the
          // worst case, num_messages * consumer_timeout << request_timeout, so it's safe to only
          // check the elapsed time once this loop finishes.
          while (iter.hasNext()) {
            MessageAndMetadata<byte[], byte[]> msg = iter.peek();
            byte[] key = msg.key();
            byte[] value = msg.message();
            int roughMsgSize = (key != null ? key.length : 0) + (value != null ? value.length : 0);
            if (bytesConsumed + roughMsgSize > maxResponseBytes) {
              break;
            }

            iter.next();
            messages.add(new ConsumerRecord(key, value, msg.partition(), msg.offset()));
            bytesConsumed += roughMsgSize;
            topicState.consumedOffsets.put(msg.partition(), msg.offset());
          }
        } catch (ConsumerTimeoutException cte) {
          backoff = true;
        }

        long now = config.time.milliseconds();
        long elapsed = now - started;
        // Compute backoff based on starting time. This makes reasoning about when timeouts
        // should occur simpler for tests.
        long backoffExpiration = startedIteration +
                                 config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG);
        long requestExpiration =
            started + config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG);
        waitExpiration = Math.min(backoffExpiration, requestExpiration);

        if (elapsed >= requestTimeoutMs || bytesConsumed >= maxResponseBytes) {
          state.finishRead(topicState);
          finish();
        }

        return backoff;
      } catch (Exception e) {
        state.finishRead(topicState);
        finish();
        log.error("Unexpected exception in consumer read thread: ", e);
        return false;
      }
    }

    public void finish() {
      callback.onCompletion(messages);
      finished.countDown();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return (finished.getCount() == 0);
    }

    @Override
    public List<ConsumerRecord> get() throws InterruptedException, ExecutionException {
      finished.await();
      return messages;
    }

    @Override
    public List<ConsumerRecord> get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      finished.await(timeout, unit);
      if (finished.getCount() > 0) {
        throw new TimeoutException();
      }
      return messages;
    }
  }

  private static class ReadTaskExpirationComparator implements Comparator<ReadTask> {

    @Override
    public int compare(ReadTask t1, ReadTask t2) {
      if (t1.waitExpiration == t2.waitExpiration) {
        return 0;
      } else if (t1.waitExpiration < t2.waitExpiration) {
        return -1;
      } else {
        return 1;
      }
    }
  }
}
