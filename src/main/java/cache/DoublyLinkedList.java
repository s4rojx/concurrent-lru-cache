package cache;

final class DoublyLinkedList<K, V> {
    private CacheNode<K, V> head;
    private CacheNode<K, V> tail;

    void addToFront(CacheNode<K, V> node) {
        node.previous = null;
        node.next = head;
        if (head != null) {
            head.previous = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    void moveToFront(CacheNode<K, V> node) {
        if (node == head) {
            return;
        }
        remove(node);
        addToFront(node);
    }

    void remove(CacheNode<K, V> node) {
        CacheNode<K, V> previous = node.previous;
        CacheNode<K, V> next = node.next;
        if (previous != null) {
            previous.next = next;
        } else {
            head = next;
        }
        if (next != null) {
            next.previous = previous;
        } else {
            tail = previous;
        }
        node.previous = null;
        node.next = null;
    }

    CacheNode<K, V> removeTail() {
        CacheNode<K, V> currentTail = tail;
        if (currentTail != null) {
            remove(currentTail);
        }
        return currentTail;
    }

    void clear() {
        CacheNode<K, V> current = head;
        while (current != null) {
            CacheNode<K, V> next = current.next;
            current.previous = null;
            current.next = null;
            current = next;
        }
        head = null;
        tail = null;
    }
}
