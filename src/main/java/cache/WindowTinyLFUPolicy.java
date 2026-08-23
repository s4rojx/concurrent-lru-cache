package cache;

import java.util.HashMap;
import java.util.Map;

final class WindowTinyLFUPolicy<K, V> implements EvictionPolicy<K, V> {

    private static final double WINDOW_RATIO = 0.01;
    private static final double PROTECTED_RATIO = 0.80;

    private final int windowMaxSize;
    private final int protectedMaxSize;

    private int windowSize = 0;
    private int protectedSize = 0;
    private int probationSize = 0;

    private long admissions = 0;
    private long rejections = 0;

    private final DoublyLinkedList<K, V> windowQueue = new DoublyLinkedList<>();
    private final DoublyLinkedList<K, V> probationQueue = new DoublyLinkedList<>();
    private final DoublyLinkedList<K, V> protectedQueue = new DoublyLinkedList<>();

    private final Map<K, QueueType> queueMap = new HashMap<>();
    private final CountMinSketch sketch;

    WindowTinyLFUPolicy(int capacity) {
        this.windowMaxSize = Math.max(1, (int) (capacity * WINDOW_RATIO));
        int mainMax = capacity - windowMaxSize;
        this.protectedMaxSize = Math.max(1, (int) (mainMax * PROTECTED_RATIO));
        this.sketch = new CountMinSketch(capacity);
    }

    @Override
    public void onInsert(CacheNode<K, V> node) {
        windowQueue.addToFront(node);
        queueMap.put(node.key, QueueType.WINDOW);
        windowSize++;
        sketch.increment(node.key);
    }

    @Override
    public void onAccess(CacheNode<K, V> node) {
        sketch.increment(node.key);
        QueueType queue = queueMap.get(node.key);
        if (queue == null) return;
        switch (queue) {
            case WINDOW -> windowQueue.moveToFront(node);
            case PROBATION -> {
                probationQueue.remove(node);
                probationSize--;
                protectedQueue.addToFront(node);
                protectedSize++;
                queueMap.put(node.key, QueueType.PROTECTED);
                if (protectedSize > protectedMaxSize) {
                    CacheNode<K, V> demoted = protectedQueue.getTail();
                    if (demoted != null) {
                        protectedQueue.remove(demoted);
                        protectedSize--;
                        probationQueue.addToFront(demoted);
                        probationSize++;
                        queueMap.put(demoted.key, QueueType.PROBATION);
                    }
                }
            }
            case PROTECTED -> protectedQueue.moveToFront(node);
        }
    }

    @Override
    public void onRemove(CacheNode<K, V> node) {
        QueueType queue = queueMap.remove(node.key);
        if (queue == null) return;
        switch (queue) {
            case WINDOW -> {
                windowQueue.remove(node);
                windowSize--;
            }
            case PROBATION -> {
                probationQueue.remove(node);
                probationSize--;
            }
            case PROTECTED -> {
                protectedQueue.remove(node);
                protectedSize--;
            }
        }
    }

    @Override
    public CacheNode<K, V> evictionCandidate() {
        if (windowSize > windowMaxSize) {
            CacheNode<K, V> windowVictim = windowQueue.getTail();
            if (windowVictim == null) return null;

            CacheNode<K, V> probationVictim = probationQueue.getTail();
            if (probationVictim != null
                    && sketch.estimate(windowVictim.key) <= sketch.estimate(probationVictim.key)) {
                return windowVictim;
            }
            return windowVictim;
        }

        CacheNode<K, V> probationVictim = probationQueue.getTail();
        if (probationVictim != null) {
            return probationVictim;
        }
        return protectedQueue.getTail();
    }

    @Override
    public void clear() {
        windowQueue.clear();
        probationQueue.clear();
        protectedQueue.clear();
        queueMap.clear();
        windowSize = 0;
        protectedSize = 0;
        probationSize = 0;
        sketch.reset();
    }

    void promoteWindowVictimToMain(CacheNode<K, V> node) {
        windowQueue.remove(node);
        windowSize--;

        CacheNode<K, V> probationVictim = probationQueue.getTail();
        boolean admit =
                probationVictim == null
                        || sketch.estimate(node.key) > sketch.estimate(probationVictim.key);

        if (admit) {
            admissions++;
            probationQueue.addToFront(node);
            probationSize++;
            queueMap.put(node.key, QueueType.PROBATION);
        } else {
            rejections++;
            queueMap.remove(node.key);
        }
    }

    @Override
    public PolicyStats getStats() {
        long total = admissions + rejections;
        double rate = total == 0 ? 0.0 : (double) rejections / total;
        return new PolicyStats("WINDOW_TINY_LFU", admissions, rejections, rate);
    }

    void discardWindowVictim(CacheNode<K, V> node) {
        windowQueue.remove(node);
        windowSize--;
        queueMap.remove(node.key);
    }

    boolean isWindowOverCapacity() {
        return windowSize > windowMaxSize;
    }

    QueueType getQueueType(K key) {
        return queueMap.get(key);
    }

    enum QueueType {
        WINDOW,
        PROBATION,
        PROTECTED
    }

    static final class CountMinSketch {
        private static final int DEPTH = 4;
        private static final int MAX_COUNT = 15;
        private static final int RESET_MULTIPLIER = 10;

        private final long[] table;
        private final int width;
        private final int[] seeds;
        private long additions = 0;
        private final long resetThreshold;

        CountMinSketch(int capacity) {
            int w = Integer.highestOneBit(Math.max(capacity, 8) * 8);
            if (w < capacity * 8) w <<= 1;
            this.width = w;
            this.table = new long[DEPTH * (width / 16)];
            this.resetThreshold = (long) capacity * RESET_MULTIPLIER;
            this.seeds = new int[] {0x3ba8d67f, 0x9abf3251, 0x55e8ac71, 0xc73fa8d3};
        }

        void increment(Object key) {
            int hash = spread(key.hashCode());
            boolean saturated = true;
            for (int i = 0; i < DEPTH; i++) {
                int idx = indexOf(hash, i);
                int count = getCount(i, idx);
                if (count < MAX_COUNT) {
                    setCount(i, idx, count + 1);
                    saturated = false;
                }
            }
            if (!saturated) {
                additions++;
                if (additions >= resetThreshold) {
                    halve();
                }
            }
        }

        int estimate(Object key) {
            int hash = spread(key.hashCode());
            int min = MAX_COUNT;
            for (int i = 0; i < DEPTH; i++) {
                int idx = indexOf(hash, i);
                min = Math.min(min, getCount(i, idx));
            }
            return min;
        }

        void reset() {
            java.util.Arrays.fill(table, 0L);
            additions = 0;
        }

        private void halve() {
            for (int i = 0; i < table.length; i++) {
                table[i] = (table[i] >>> 1) & 0x7777777777777777L;
            }
            additions = 0;
        }

        private int indexOf(int hash, int depth) {
            int h = hash ^ seeds[depth];
            return ((h >>> 16) ^ h) & (width - 1);
        }

        private int getCount(int depth, int idx) {
            int longIdx = (depth * (width / 16)) + (idx >>> 4);
            int bitIdx = (idx & 15) << 2;
            return (int) ((table[longIdx] >>> bitIdx) & 0xFL);
        }

        private void setCount(int depth, int idx, int count) {
            int longIdx = (depth * (width / 16)) + (idx >>> 4);
            int bitIdx = (idx & 15) << 2;
            table[longIdx] = (table[longIdx] & ~(0xFL << bitIdx)) | ((long) count << bitIdx);
        }

        private static int spread(int h) {
            h ^= h >>> 17;
            h *= 0xbf085a57;
            h ^= h >>> 13;
            return h;
        }
    }
}
