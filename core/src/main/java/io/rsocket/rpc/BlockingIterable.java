package io.rsocket.rpc;

import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.publisher.Operators;
import reactor.util.annotation.Nullable;

/**
 * An iterable that consumes a Publisher in a blocking fashion.
 *
 * <p>
 *
 * <p>It also implements methods to stream the contents via Stream that also supports cancellation.
 *
 * @param <T> the value type
 */
public class BlockingIterable<T> implements Iterable<T>, Scannable {

  final Publisher<? extends T> source;

  final int batchSize;

  final Supplier<Queue<T>> queueSupplier;

  public BlockingIterable(
      Publisher<? extends T> source, int batchSize, Supplier<Queue<T>> queueSupplier) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize > 0 required but it was " + batchSize);
    }
    this.source = Objects.requireNonNull(source, "source");
    this.batchSize = batchSize;
    this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
  }

  static long unboundedOrPrefetch(int prefetch) {
    return prefetch == Integer.MAX_VALUE ? Long.MAX_VALUE : prefetch;
  }

  static int unboundedOrLimit(int prefetch) {
    return prefetch == Integer.MAX_VALUE ? Integer.MAX_VALUE : (prefetch - (prefetch >> 2));
  }

  @Override
  @Nullable
  public Object scanUnsafe(Attr key) {
    if (key == Attr.PREFETCH)
      return Math.min(Integer.MAX_VALUE, batchSize); // FIXME should batchSize be forced to int?
    if (key == Attr.PARENT) return source;

    return null;
  }

  @Override
  public Iterator<T> iterator() {
    SubscriberIterator<T> it = createIterator();

    source.subscribe(it);

    return it;
  }

  @Override
  public Spliterator<T> spliterator() {
    return stream().spliterator(); // cancellation should be composed through this way
  }

  /**
   * @return a {@link Stream} of unknown size with onClose attached to {@link Subscription#cancel()}
   */
  public Stream<T> stream() {
    SubscriberIterator<T> it = createIterator();
    source.subscribe(it);

    Spliterator<T> sp = Spliterators.spliteratorUnknownSize(it, 0);

    return StreamSupport.stream(sp, false).onClose(it);
  }

  SubscriberIterator<T> createIterator() {
    Queue<T> q;

    try {
      q = Objects.requireNonNull(queueSupplier.get(), "The queueSupplier returned a null queue");
    } catch (Throwable e) {
      throw Exceptions.propagate(e);
    }

    return new SubscriberIterator<>(q, batchSize);
  }

  static final class SubscriberIterator<T>
      implements CoreSubscriber<T>, Scannable, Iterator<T>, Runnable {

    @SuppressWarnings("rawtypes")
    static final AtomicReferenceFieldUpdater<SubscriberIterator, Subscription> S =
        AtomicReferenceFieldUpdater.newUpdater(SubscriberIterator.class, Subscription.class, "s");

    final Queue<T> queue;
    final int batchSize;
    final int limit;
    final Lock lock;
    final Condition condition;
    long produced;
    volatile Subscription s;
    volatile boolean done;
    Throwable error;

    SubscriberIterator(Queue<T> queue, int batchSize) {
      this.queue = queue;
      this.batchSize = batchSize;
      this.limit = unboundedOrLimit(batchSize);
      this.lock = new ReentrantLock();
      this.condition = lock.newCondition();
    }

    @Override
    public boolean hasNext() {
      for (; ; ) {
        boolean d = done;
        boolean empty = queue.isEmpty();
        if (d) {
          Throwable e = error;
          if (e != null) {
            throw Exceptions.propagate(e);
          } else if (empty) {
            return false;
          }
        }
        if (empty) {
          lock.lock();
          try {
            while (!done && queue.isEmpty()) {
              condition.await();
            }
          } catch (InterruptedException ex) {
            run();
            throw Exceptions.propagate(ex);
          } finally {
            lock.unlock();
          }
        } else {
          return true;
        }
      }
    }

    @Override
    public T next() {
      if (hasNext()) {
        T v = queue.poll();

        if (v == null) {
          run();

          throw new IllegalStateException(
              "Queue is empty: Expected one element to be available from the Reactive Streams source.");
        }

        long p = produced + 1;
        if (p == limit) {
          produced = 0;
          s.request(p);
        } else {
          produced = p;
        }

        return v;
      }
      throw new NoSuchElementException();
    }

    @Override
    public void onSubscribe(Subscription s) {
      if (Operators.setOnce(S, this, s)) {
        s.request(unboundedOrPrefetch(batchSize));
      }
    }

    @Override
    public void onNext(T t) {
      if (!queue.offer(t)) {
        Operators.terminate(S, this);

        onError(
            Operators.onOperatorError(
                null,
                Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL),
                t,
                currentContext()));
      } else {
        signalConsumer();
      }
    }

    @Override
    public void onError(Throwable t) {
      error = t;
      done = true;
      signalConsumer();
    }

    @Override
    public void onComplete() {
      done = true;
      signalConsumer();
    }

    void signalConsumer() {
      lock.lock();
      try {
        condition.signalAll();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void run() {
      Operators.terminate(S, this);
      signalConsumer();
    }

    @Override
    @Nullable
    public Object scanUnsafe(Attr key) {
      if (key == Attr.TERMINATED) return done;
      if (key == Attr.PARENT) return s;
      if (key == Attr.CANCELLED) return s == Operators.cancelledSubscription();
      if (key == Attr.PREFETCH) return batchSize;
      if (key == Attr.ERROR) return error;

      return null;
    }
  }
}
