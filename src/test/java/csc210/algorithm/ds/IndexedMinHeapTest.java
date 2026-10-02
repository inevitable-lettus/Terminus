package csc210.algorithm.ds;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 2.1. The main proof is a randomised oracle test: the same operation
 * sequence is pushed through our heap and through {@code java.util.PriorityQueue},
 * and the two must pop elements in exactly the same order. Priorities are kept
 * distinct so that "same order" is well defined — neither structure promises
 * anything about how it breaks ties, and demanding agreement on ties would be
 * testing an accident rather than a contract.
 *
 * <p>Then the operation the library queue cannot express at all: {@code decreaseKey}.
 */
class IndexedMinHeapTest {

    /** One queued item in the oracle, since PriorityQueue cannot index by element. */
    private record Entry(int element, long priority) {}

    private static final Comparator<Entry> BY_PRIORITY = Comparator.comparingLong(Entry::priority);

    @Test
    void popsInTheSameOrderAsTheLibraryQueue() {
        Random random = new Random(20260910L);
        int capacity = 200;

        for (int trial = 0; trial < 2_000; trial++) {
            IndexedMinHeap ours = new IndexedMinHeap(capacity);
            PriorityQueue<Entry> oracle = new PriorityQueue<>(BY_PRIORITY);

            // Distinct priorities, shuffled, so no two elements can tie.
            List<Long> priorities = distinctPriorities(capacity, random);
            List<Integer> unqueued = new ArrayList<>();
            for (int element = 0; element < capacity; element++) {
                unqueued.add(element);
            }

            int operations = 1 + random.nextInt(3 * capacity);
            for (int op = 0; op < operations; op++) {
                boolean insert = unqueued.isEmpty() ? false : (oracle.isEmpty() || random.nextInt(3) > 0);
                if (insert) {
                    int element = unqueued.remove(random.nextInt(unqueued.size()));
                    long priority = priorities.get(element);
                    ours.insert(element, priority);
                    oracle.add(new Entry(element, priority));
                } else if (!oracle.isEmpty()) {
                    assertEquals(oracle.poll().element(), ours.extractMin(),
                            "pop order diverged on trial " + trial + ", operation " + op);
                }
                assertEquals(oracle.size(), ours.size());
            }

            // Drain both: everything still queued must come out in the same order.
            while (!oracle.isEmpty()) {
                assertEquals(oracle.poll().element(), ours.extractMin());
            }
            assertTrue(ours.isEmpty());
        }
    }

    @Test
    void extractMinAlwaysReturnsTheSmallestRemaining() {
        Random random = new Random(7L);
        int capacity = 500;
        IndexedMinHeap heap = new IndexedMinHeap(capacity);
        List<Long> priorities = distinctPriorities(capacity, random);
        for (int element = 0; element < capacity; element++) {
            heap.insert(element, priorities.get(element));
        }

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < capacity; i++) {
            int element = heap.extractMin();
            long priority = priorities.get(element);
            assertTrue(priority > previous, "heap popped " + priority + " after " + previous);
            previous = priority;
        }
        assertTrue(heap.isEmpty());
    }

    /**
     * The operation the library queue has no method for. The oracle here has to
     * fake it with {@code remove} + {@code add} — an {@code O(n)} linear scan
     * every time, which is precisely the cost Dijkstra would pay if it used
     * {@code java.util.PriorityQueue} instead of this heap.
     */
    @Test
    void decreaseKeyMatchesRemoveAndReinsert() {
        Random random = new Random(99L);
        int capacity = 120;

        for (int trial = 0; trial < 500; trial++) {
            IndexedMinHeap ours = new IndexedMinHeap(capacity);
            PriorityQueue<Entry> oracle = new PriorityQueue<>(BY_PRIORITY);

            long[] current = new long[capacity];
            List<Long> priorities = distinctPriorities(capacity, random);
            // Every priority in play stays unique, including the lowered ones, so
            // "which element is the minimum" never depends on tie-breaking.
            java.util.Set<Long> inUse = new java.util.HashSet<>();
            for (int element = 0; element < capacity; element++) {
                current[element] = priorities.get(element);
                inUse.add(current[element]);
                ours.insert(element, current[element]);
                oracle.add(new Entry(element, current[element]));
            }

            for (int i = 0; i < capacity; i++) {
                int element = random.nextInt(capacity);
                if (!ours.contains(element)) {
                    continue;
                }
                long lowered = current[element] - 1 - random.nextInt(1_000);
                while (!inUse.add(lowered)) {
                    lowered--;
                }
                inUse.remove(current[element]);
                ours.decreaseKey(element, lowered);
                oracle.remove(new Entry(element, current[element]));
                oracle.add(new Entry(element, lowered));
                current[element] = lowered;

                assertEquals(lowered, ours.priorityOf(element));
                assertEquals(oracle.peek().element(), peekWithoutRemoving(ours, current));
            }

            while (!oracle.isEmpty()) {
                assertEquals(oracle.poll().element(), ours.extractMin());
            }
        }
    }

    @Test
    void decreaseKeyCanReachTheRoot() {
        IndexedMinHeap heap = new IndexedMinHeap(4);
        heap.insert(0, 10);
        heap.insert(1, 20);
        heap.insert(2, 30);
        heap.insert(3, 40);

        heap.decreaseKey(3, 5);

        assertEquals(3, heap.extractMin());
        assertEquals(0, heap.extractMin());
    }

    @Test
    void insertOrDecreaseHandlesBothCases() {
        IndexedMinHeap heap = new IndexedMinHeap(3);
        heap.insertOrDecrease(0, 100);
        assertTrue(heap.contains(0));
        assertEquals(100, heap.priorityOf(0));

        heap.insertOrDecrease(0, 40);
        assertEquals(1, heap.size(), "an improved priority must not queue a second copy");
        assertEquals(40, heap.priorityOf(0));

        heap.insertOrDecrease(0, 90);
        assertEquals(40, heap.priorityOf(0), "a worse priority is ignored, not applied");
    }

    @Test
    void misuseIsRejectedRatherThanCorruptingTheHeap() {
        IndexedMinHeap heap = new IndexedMinHeap(2);
        heap.insert(0, 10);

        assertThrows(IllegalStateException.class, () -> heap.insert(0, 5));
        assertThrows(IllegalArgumentException.class, () -> heap.decreaseKey(0, 50));
        assertThrows(IllegalStateException.class, () -> heap.priorityOf(1));
        assertThrows(IllegalArgumentException.class, () -> heap.insert(9, 1));

        assertEquals(0, heap.extractMin());
        assertFalse(heap.contains(0));
        assertThrows(IllegalStateException.class, heap::extractMin);
    }

    /** Reads the current minimum by extracting and putting it straight back. */
    private static int peekWithoutRemoving(IndexedMinHeap heap, long[] current) {
        int element = heap.extractMin();
        heap.insert(element, current[element]);
        return element;
    }

    private static List<Long> distinctPriorities(int count, Random random) {
        List<Long> priorities = new ArrayList<>(count);
        for (long value = 0; value < count; value++) {
            priorities.add(value * 1_000L + random.nextInt(500));
        }
        java.util.Collections.shuffle(priorities, random);
        return priorities;
    }
}
