
package io.github.resilience4j.circularbuffer;

import static java.lang.reflect.Array.newInstance;
import static java.util.Objects.requireNonNull;

import java.util.AbstractQueue;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

/**
 一个简单的循环队列，大家练手的好demo
 同时可以很好的展示 ConcurrentModificationException 是如何发生的，可以深入了解集合相关的东西
 Java8环境
*/
public class CircleQueue<E> extends AbstractQueue<E> {

    private static final String ILLEGAL_CAPACITY = "Capacity must be bigger than 0";
    private static final String ILLEGAL_ELEMENT = "Element must not be null";
    private static final String ILLEGAL_DESTINATION_ARRAY = "Destination array must not be null";
    private static final Object[] DEFAULT_DESTINATION = new Object[0];
    private static final int RETRIES = 5;

    private final int maxSize;
    private volatile int size;
    private volatile int modificationsCount;
    private final StampedLock stampedLock;
    private Object[] ringBuffer;
    private int headIndex;
    private int tailIndex;

    public CircleQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(ILLEGAL_CAPACITY);
        }
        maxSize = capacity;
        ringBuffer = new Object[capacity];
        size = 0;
        headIndex = 0;
        tailIndex = 0;
        modificationsCount = 0;
        stampedLock = new StampedLock();
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     * <p>
     * This iterator implementation NOT allow removes and co-modifications.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    @Override
    public Iterator<E> iterator() {
        return readConcurrently(() -> new Iter(headIndex, modificationsCount));
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * Inserts the specified element at the tail of this queue if it is
     * possible to do so immediately or if capacity limit is exited
     * the oldest element (the head) will be evicted, and then the new element added at the tail.
     * This method is generally preferable to method {@link #add},
     * which can fail to insert an element only by throwing an exception.
     *
     * @throws NullPointerException if the specified element is null
     */
    @Override
    public boolean offer(final E e) {
        requireNonNull(e, ILLEGAL_ELEMENT);

        Supplier<Boolean> offerElement = () -> {
            if (size == 0) {
                ringBuffer[tailIndex] = e;
                modificationsCount++;
                size++;
            } else if (size == maxSize) { //已经跑完一轮了
                headIndex = nextIndex(headIndex);
                tailIndex = nextIndex(tailIndex);
                ringBuffer[tailIndex] = e;
                modificationsCount++;
            } else {
                tailIndex = nextIndex(tailIndex);
                ringBuffer[tailIndex] = e;
                size++;
                modificationsCount++;
            }
            return true;
        };
        return writeConcurrently(offerElement);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        Supplier<E> pollElement = () -> {
            if (size == 0) {
                return null;
            }
            E result = (E) ringBuffer[headIndex];
            ringBuffer[headIndex] = null;
            if (size != 1) {
                headIndex = nextIndex(headIndex);
            }
            size--;
            modificationsCount++;
            return result;
        };
        return writeConcurrently(pollElement);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        return readConcurrently(() -> {
            if (size == 0) {
                return null;
            }
            return (E) this.ringBuffer[this.headIndex];
        });
    }

    /**
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    @Override
    public void clear() {
        Supplier<Object> clearStrategy = () -> {
            if (size == 0) {
                return null;
            }
            ringBuffer = new Object[maxSize]; // ?为啥要重新new
            size = 0;
            headIndex = 0;
            tailIndex = 0;
            modificationsCount++;
            return null;
        };
        writeConcurrently(clearStrategy);
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence.
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is free to modify the returned array.
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    @Override
    public Object[] toArray() {
        if (size == 0) {
            return new Object[0];
        }
        Object[] destination = toArray(DEFAULT_DESTINATION);
        return destination;
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the queue fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this queue.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.

     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param destination the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    @Override
    @SuppressWarnings({"unchecked"})
    public <T> T[] toArray(final T[] destination) {
        requireNonNull(destination, ILLEGAL_DESTINATION_ARRAY);

        Supplier<T[]> copyRingBuffer = () -> {
            T[] result = destination;
            if (size == 0) {
                return result;
            }
            if (destination.length < size) {
                result = (T[]) newInstance(result.getClass().getComponentType(), size);
            }
            if (headIndex <= tailIndex) {
                System.arraycopy(ringBuffer, headIndex, result, 0, size);
            } else {
                int toTheEnd = ringBuffer.length - headIndex;
                System.arraycopy(ringBuffer, headIndex, result, 0, toTheEnd);
                System.arraycopy(ringBuffer, 0, result, toTheEnd, tailIndex + 1);
            }
            return result;
        };
        return readConcurrentlyWithoutSpin(copyRingBuffer);
    }

    private int nextIndex(final int ringIndex) {
        return (ringIndex + 1) % maxSize;
    }


    private class Iter implements Iterator<E> {

        private int visitedCount = 0;
        private int cursor;
        private int expectedModificationsCount;

        Iter(final int headIndex, final int modificationsCount) {
            this.cursor = headIndex;
            this.expectedModificationsCount = modificationsCount;
        }

        @Override
        public boolean hasNext() {
            return visitedCount < size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            Supplier<E> nextElement = () -> {
                checkForModification();
                if (visitedCount >= size) {
                    throw new NoSuchElementException();
                }
                E item = (E) ringBuffer[cursor];
                cursor = nextIndex(cursor);
                visitedCount++;
                return item;
            };
            return readConcurrently(nextElement);
        }
        private void checkForModification() {
            if (modificationsCount != expectedModificationsCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    private <T> T readConcurrently(final Supplier<T> readSupplier) {
        T result;
        long stamp;
        for (int i = 0; i < RETRIES; i++) {
            stamp = stampedLock.tryOptimisticRead();
            result = readSupplier.get();
            if (stampedLock.validate(stamp)) {
                return result;
            }
        }
        stamp = stampedLock.readLock();
        try {
            result = readSupplier.get();
        } finally {
            stampedLock.unlockRead(stamp);
        }
        return result;
    }

    private <T> T readConcurrentlyWithoutSpin(final Supplier<T> readSupplier) {
        T result;
        long stamp = stampedLock.readLock();
        try {
            result = readSupplier.get();
        } finally {
            stampedLock.unlockRead(stamp);
        }
        return result;
    }

    private <T> T writeConcurrently(final Supplier<T> writeSupplier) {
        T result;
        long stamp = stampedLock.writeLock();
        try {
            result = writeSupplier.get();
        } finally {
            stampedLock.unlockWrite(stamp);
        }
        return result;
    }
}
