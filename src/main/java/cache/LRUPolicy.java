package cache;

final class LRUPolicy<K, V> implements EvictionPolicy<K, V> {

    private final DoublyLinkedList<K, V> list = new DoublyLinkedList<>();

    @Override
    public void onInsert(CacheNode<K, V> node) {
        list.addToFront(node);
    }

    @Override
    public void onAccess(CacheNode<K, V> node) {
        list.moveToFront(node);
    }

    @Override
    public void onRemove(CacheNode<K, V> node) {
        list.remove(node);
    }

    @Override
    public CacheNode<K, V> evictionCandidate() {
        return list.getTail();
    }

    @Override
    public void clear() {
        list.clear();
    }
}
