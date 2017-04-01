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

    @Override
    public Object[] toArray() {
        if (size == 0) {
            return new Object[0];
        }
        Object[] destination = toArray(DEFAULT_DESTINATION);
        return destination;
    }

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
