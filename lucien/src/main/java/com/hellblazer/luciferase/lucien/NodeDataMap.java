package com.hellblazer.luciferase.lucien;

import java.util.*;

/**
 * A NavigableMap transformation that wraps a NavigableMap<Long, Node<Content>> and exposes it as a NavigableMap<Long, Content>.
 * This allows accessing node data directly without having to call .getData() on the node.
 * 
 * When get(index) is called, it returns the node's data instead of the node itself.
 * This is a dynamic wrapper that reflects changes to the underlying nodes map.
 * 
 * @param <Content> the type of content stored in the nodes
 * @author hal.hildebrand
 */
public class NodeDataMap<Content> implements NavigableMap<Long, Content> {
    
    private final NavigableMap<Long, Octree.Node<Content>> nodes;
    
    public NodeDataMap(NavigableMap<Long, Octree.Node<Content>> nodes) {
        this.nodes = nodes;
    }
    
    @Override
    public Content get(Object key) {
        Octree.Node<Content> node = nodes.get(key);
        return node != null ? node.getData() : null;
    }
    
    @Override
    public Content put(Long key, Content value) {
        Octree.Node<Content> existingNode = nodes.get(key);
        Content previousValue = existingNode != null ? existingNode.getData() : null;
        
        if (existingNode != null) {
            existingNode.setData(value);
        } else {
            nodes.put(key, new Octree.Node<>(value));
        }
        
        return previousValue;
    }
    
    @Override
    public Content remove(Object key) {
        Octree.Node<Content> removedNode = nodes.remove(key);
        return removedNode != null ? removedNode.getData() : null;
    }
    
    @Override
    public boolean containsKey(Object key) {
        return nodes.containsKey(key);
    }
    
    @Override
    public boolean containsValue(Object value) {
        for (Octree.Node<Content> node : nodes.values()) {
            Content nodeData = node.getData();
            if (Objects.equals(nodeData, value)) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public int size() {
        return nodes.size();
    }
    
    @Override
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
    
    @Override
    public void clear() {
        nodes.clear();
    }
    
    @Override
    public Set<Long> keySet() {
        return nodes.keySet();
    }
    
    @Override
    public Collection<Content> values() {
        return new AbstractCollection<Content>() {
            @Override
            public Iterator<Content> iterator() {
                return new Iterator<Content>() {
                    private final Iterator<Octree.Node<Content>> nodeIterator = nodes.values().iterator();
                    
                    @Override
                    public boolean hasNext() {
                        return nodeIterator.hasNext();
                    }
                    
                    @Override
                    public Content next() {
                        return nodeIterator.next().getData();
                    }
                };
            }
            
            @Override
            public int size() {
                return nodes.size();
            }
        };
    }
    
    @Override
    public Set<Entry<Long, Content>> entrySet() {
        return new AbstractSet<Entry<Long, Content>>() {
            @Override
            public Iterator<Entry<Long, Content>> iterator() {
                return new Iterator<Entry<Long, Content>>() {
                    private final Iterator<Entry<Long, Octree.Node<Content>>> nodeEntryIterator = nodes.entrySet().iterator();
                    
                    @Override
                    public boolean hasNext() {
                        return nodeEntryIterator.hasNext();
                    }
                    
                    @Override
                    public Entry<Long, Content> next() {
                        Entry<Long, Octree.Node<Content>> nodeEntry = nodeEntryIterator.next();
                        return new Entry<Long, Content>() {
                            @Override
                            public Long getKey() {
                                return nodeEntry.getKey();
                            }
                            
                            @Override
                            public Content getValue() {
                                return nodeEntry.getValue().getData();
                            }
                            
                            @Override
                            public Content setValue(Content value) {
                                Content previousValue = nodeEntry.getValue().getData();
                                nodeEntry.getValue().setData(value);
                                return previousValue;
                            }
                        };
                    }
                };
            }
            
            @Override
            public int size() {
                return nodes.size();
            }
        };
    }
    
    @Override
    public void putAll(Map<? extends Long, ? extends Content> m) {
        for (Entry<? extends Long, ? extends Content> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }
    
    // NavigableMap methods
    
    @Override
    public Entry<Long, Content> lowerEntry(Long key) {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.lowerEntry(key);
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Long lowerKey(Long key) {
        return nodes.lowerKey(key);
    }
    
    @Override
    public Entry<Long, Content> floorEntry(Long key) {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.floorEntry(key);
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Long floorKey(Long key) {
        return nodes.floorKey(key);
    }
    
    @Override
    public Entry<Long, Content> ceilingEntry(Long key) {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.ceilingEntry(key);
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Long ceilingKey(Long key) {
        return nodes.ceilingKey(key);
    }
    
    @Override
    public Entry<Long, Content> higherEntry(Long key) {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.higherEntry(key);
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Long higherKey(Long key) {
        return nodes.higherKey(key);
    }
    
    @Override
    public Entry<Long, Content> firstEntry() {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.firstEntry();
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Entry<Long, Content> lastEntry() {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.lastEntry();
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Entry<Long, Content> pollFirstEntry() {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.pollFirstEntry();
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public Entry<Long, Content> pollLastEntry() {
        Entry<Long, Octree.Node<Content>> nodeEntry = nodes.pollLastEntry();
        return nodeEntry != null ? transformEntry(nodeEntry) : null;
    }
    
    @Override
    public NavigableMap<Long, Content> descendingMap() {
        return new NodeDataMap<>(nodes.descendingMap());
    }
    
    @Override
    public NavigableSet<Long> navigableKeySet() {
        return nodes.navigableKeySet();
    }
    
    @Override
    public NavigableSet<Long> descendingKeySet() {
        return nodes.descendingKeySet();
    }
    
    @Override
    public NavigableMap<Long, Content> subMap(Long fromKey, boolean fromInclusive, Long toKey, boolean toInclusive) {
        return new NodeDataMap<>(nodes.subMap(fromKey, fromInclusive, toKey, toInclusive));
    }
    
    @Override
    public NavigableMap<Long, Content> headMap(Long toKey, boolean inclusive) {
        return new NodeDataMap<>(nodes.headMap(toKey, inclusive));
    }
    
    @Override
    public NavigableMap<Long, Content> tailMap(Long fromKey, boolean inclusive) {
        return new NodeDataMap<>(nodes.tailMap(fromKey, inclusive));
    }
    
    @Override
    public Comparator<? super Long> comparator() {
        return nodes.comparator();
    }
    
    @Override
    public SortedMap<Long, Content> subMap(Long fromKey, Long toKey) {
        return new NodeDataMap<>((NavigableMap<Long, Octree.Node<Content>>) nodes.subMap(fromKey, toKey));
    }
    
    @Override
    public SortedMap<Long, Content> headMap(Long toKey) {
        return new NodeDataMap<>((NavigableMap<Long, Octree.Node<Content>>) nodes.headMap(toKey));
    }
    
    @Override
    public SortedMap<Long, Content> tailMap(Long fromKey) {
        return new NodeDataMap<>((NavigableMap<Long, Octree.Node<Content>>) nodes.tailMap(fromKey));
    }
    
    @Override
    public Long firstKey() {
        return nodes.firstKey();
    }
    
    @Override
    public Long lastKey() {
        return nodes.lastKey();
    }
    
    // Helper method to transform node entries to content entries
    private Entry<Long, Content> transformEntry(Entry<Long, Octree.Node<Content>> nodeEntry) {
        return new Entry<Long, Content>() {
            @Override
            public Long getKey() {
                return nodeEntry.getKey();
            }
            
            @Override
            public Content getValue() {
                return nodeEntry.getValue().getData();
            }
            
            @Override
            public Content setValue(Content value) {
                Content previousValue = nodeEntry.getValue().getData();
                nodeEntry.getValue().setData(value);
                return previousValue;
            }
        };
    }
}