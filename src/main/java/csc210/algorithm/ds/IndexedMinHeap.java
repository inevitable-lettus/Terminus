package csc210.algorithm.ds;

/**
 * A binary min-heap over dense integer elements, with {@code decreaseKey}.
 *
 * <p>The heap is a complete binary tree — every level full except possibly the
 * last, which fills left to right. That shape is what lets the tree live in a
 * flat array with no pointers at all: the node at index {@code i} has parent
 * {@code (i - 1) / 2} and children {@code 2i + 1} and {@code 2i + 2}. The heap
 * property is the single invariant everything rests on: every node's priority
 * is less than or equal to both of its children's. It says nothing about
 * left-versus-right ordering, which is exactly why the structure is cheap to
 * maintain — a change only ever has to walk one root-to-leaf path, so
 * {@code insert}, {@code extractMin} and {@code decreaseKey} are all
 * {@code O(log n)}.
 *
 * <p><strong>Why this exists instead of {@code java.util.PriorityQueue}.</strong>
 * Dijkstra repeatedly lowers a node's tentative distance while that node is
 * already sitting in the queue. The library queue cannot express that: it has no
 * "this element's priority just improved" operation, and its {@code remove(Object)}
 * is a {@code O(n)} linear scan. The usual workaround is lazy deletion — push a
 * second entry at the better priority and ignore the stale one when it pops —
 * which grows the queue from {@code O(V)} entries to {@code O(E)} and needs a
 * "have I settled this already?" check on every pop. Here a {@code position}
 * array maps each element to where it currently sits in the heap array, so
 * {@code decreaseKey} finds the element in {@code O(1)} and then only sifts.
 *
 * <p>Elements are the integers {@code 0 .. capacity - 1} — node ids, which DSTNS
 * already hands out dense and 0-indexed, so no map from element to slot is
 * needed. Priorities are {@code long} milliseconds, matching
 * {@link csc210.algorithm.cost.CostModel}; using integers rather than doubles
 * keeps comparisons exact, so two paths of equal cost really do compare equal.
 */
public final class IndexedMinHeap {

    /** Marks an element that is not currently in the heap. */
    private static final int ABSENT = -1;

    /** heap[i] = the element sitting at heap position i. */
    private final int[] heap;

    /** position[e] = where element e sits in {@link #heap}, or {@link #ABSENT}. */
    private final int[] position;

    /** priority[e] = element e's current priority. Meaningful only while e is in the heap. */
    private final long[] priority;

    private int size;

    /**
     * @param capacity number of distinct elements, which are then {@code 0 .. capacity - 1}
     */
    public IndexedMinHeap(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.heap = new int[capacity];
        this.position = new int[capacity];
        this.priority = new long[capacity];
        java.util.Arrays.fill(this.position, ABSENT);
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public boolean contains(int element) {
        checkElement(element);
        return position[element] != ABSENT;
    }

    /** The priority {@code element} was inserted or decreased to. */
    public long priorityOf(int element) {
        requirePresent(element);
        return priority[element];
    }

    /**
     * Adds {@code element} at {@code priority}, in {@code O(log n)}.
     *
     * @throws IllegalStateException if it is already in the heap — an element
     *         appearing twice would break the {@link #position} index, so this
     *         is caught rather than tolerated
     */
    public void insert(int element, long priority) {
        checkElement(element);
        if (position[element] != ABSENT) {
            throw new IllegalStateException("element " + element + " is already in the heap");
        }
        this.priority[element] = priority;
        heap[size] = element;
        position[element] = size;
        size++;
        siftUp(size - 1);
    }

    /**
     * Removes and returns the element with the smallest priority, in {@code O(log n)}.
     *
     * <p>The root cannot simply be deleted — that would leave a hole. Instead the
     * last leaf is moved into the root and sifted back down, which keeps the tree
     * complete and touches only one path.
     */
    public int extractMin() {
        if (size == 0) {
            throw new IllegalStateException("heap is empty");
        }
        int minimum = heap[0];
        size--;
        if (size > 0) {
            place(heap[size], 0);
            siftDown(0);
        }
        position[minimum] = ABSENT;
        return minimum;
    }

    /**
     * Lowers {@code element}'s priority and restores the heap property, in
     * {@code O(log n)} — the operation {@code java.util.PriorityQueue} has no
     * way to express. Only sifting *up* is needed: the element got smaller, so
     * it can only have violated the invariant against its parent.
     *
     * @throws IllegalArgumentException if the new priority is not lower — a
     *         raise would need a sift *down* instead, and silently accepting one
     *         here would corrupt the heap
     */
    public void decreaseKey(int element, long newPriority) {
        requirePresent(element);
        if (newPriority > priority[element]) {
            throw new IllegalArgumentException(
                    "decreaseKey may only lower a priority: element " + element + " is at "
                            + priority[element] + ", asked for " + newPriority);
        }
        priority[element] = newPriority;
        siftUp(position[element]);
    }

    /** Inserts {@code element}, or decreases it if it is already queued. */
    public void insertOrDecrease(int element, long newPriority) {
        if (contains(element)) {
            if (newPriority < priority[element]) {
                decreaseKey(element, newPriority);
            }
        } else {
            insert(element, newPriority);
        }
    }

    /** Walks a too-small element toward the root until its parent is no larger. */
    private void siftUp(int index) {
        int element = heap[index];
        long elementPriority = priority[element];
        while (index > 0) {
            int parentIndex = (index - 1) / 2;
            int parent = heap[parentIndex];
            if (priority[parent] <= elementPriority) {
                break;
            }
            place(parent, index);
            index = parentIndex;
        }
        place(element, index);
    }

    /** Walks a too-large element toward the leaves, always swapping with the smaller child. */
    private void siftDown(int index) {
        int element = heap[index];
        long elementPriority = priority[element];
        while (true) {
            int leftIndex = 2 * index + 1;
            if (leftIndex >= size) {
                break;
            }
            int rightIndex = leftIndex + 1;
            // Swapping with the larger child would leave it above its sibling
            // and break the invariant a second time, so always take the smaller.
            int smallestChildIndex = (rightIndex < size && priority[heap[rightIndex]] < priority[heap[leftIndex]])
                    ? rightIndex
                    : leftIndex;
            if (elementPriority <= priority[heap[smallestChildIndex]]) {
                break;
            }
            place(heap[smallestChildIndex], index);
            index = smallestChildIndex;
        }
        place(element, index);
    }

    /** Writes an element into a heap slot, keeping {@link #position} in step. */
    private void place(int element, int index) {
        heap[index] = element;
        position[element] = index;
    }

    private void checkElement(int element) {
        if (element < 0 || element >= heap.length) {
            throw new IllegalArgumentException(
                    "element " + element + " is outside the heap's range [0, " + heap.length + ")");
        }
    }

    private void requirePresent(int element) {
        checkElement(element);
        if (position[element] == ABSENT) {
            throw new IllegalStateException("element " + element + " is not in the heap");
        }
    }
}
