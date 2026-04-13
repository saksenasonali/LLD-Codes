/*+-------------------+
|    CacheManager   |
+-------------------+
| - tiers: List<CacheTier>      |
+-------------------+
| + get(key)                    |
| + put(key, value)             |
| + delete(key)                 |
| + invalidate(key)             |
+-------------------+
           |
           v
+-------------------+      +-------------------+      +-------------------+
|    CacheTier      |<-----| MemoryCacheTier   |<-----| SSDCacheTier      |
+-------------------+      +-------------------+      +-------------------+
| + get(key)       |      | + get/put/delete   |      | + get/put/delete   |
| + put(entry)     |      | + evictLRU         |      | + evictLRU         |
| + delete(key)    |      +-------------------+      +-------------------+
| + evictLRU()     |                                         |
+-------------------+                                         v
                                                     +-------------------+
                                                     |  HDDCacheTier     |
                                                     +-------------------+
                                                     | + get/put/delete   |
                                                     | + evictLRU         |
                                                     +-------------------+

+-------------------+
|   CacheEntry      |
+-------------------+
| key               |
| value             |
| size              |
| lastAccessTime    |
| prev, next        |
+-------------------+

+-------------------+
| EvictionPolicy    |
+-------------------+
| + onAccess()      |
| + victim()        |
+-------------------+
*/
class CacheEntry {
    String key;
    String value;
    int size;
    long lastAccessTime;
    CacheEntry prev, next; // DLL

    CacheEntry(String key, String value) {
        this.key = key;
        this.value = value;
        this.size = value.length();
        this.lastAccessTime = System.currentTimeMillis();
    }
}

interface CacheTier {
    CacheEntry get(String key);
    void put(CacheEntry entry);
    void delete(String key);
    boolean contains(String key);
    boolean isFull();
    CacheEntry evictLRU(); // This is useful because RAM, SSD, and HDD can all follow the same API.
}

abstract class BaseCacheTier implements CacheTier {
    protected Map<String, CacheEntry> map = new HashMap<>();
    protected int capacityBytes;
    protected int usedBytes = 0;
    protected CacheEntry head, tail; // LRU list

    public CacheEntry get(String key) {
        CacheEntry entry = map.get(key);
        if (entry == null) return null;
        moveToFront(entry);
        entry.lastAccessTime = System.currentTimeMillis();
        return entry;
    }

    public void put(CacheEntry entry) {
        if (map.containsKey(entry.key)) {
            delete(entry.key); // remove the old copy first. to avoid duplicates
        }

        while (usedBytes + entry.size > capacityBytes) {
            CacheEntry victim = evictLRU();
            if (victim == null) break;
            onEvict(victim);
        }

        insertAtFront(entry);
        map.put(entry.key, entry);
        usedBytes += entry.size;
    }

    public void delete(String key) {
        CacheEntry entry = map.remove(key);
        if (entry == null) return;
        removeFromList(entry);
        usedBytes -= entry.size;
    }

    public boolean contains(String key) {
        return map.containsKey(key);
    }

    public boolean isFull() {
        return usedBytes >= capacityBytes;
    }

    public CacheEntry evictLRU() {
        if (tail == null) return null;
        CacheEntry victim = tail;
        delete(victim.key);
        return victim;
    }

    protected void onEvict(CacheEntry victim) {
        // overridden by CacheManager orchestration cascading evictions
    }

    private void moveToFront(CacheEntry entry) { /* doubly linked list ops */ }
    private void insertAtFront(CacheEntry entry) { /* doubly linked list ops */ }
    private void removeFromList(CacheEntry entry) { /* doubly linked list ops */ }
}

class MemoryCacheTier extends BaseCacheTier {
    public String name() { return "RAM"; }
}

class SSDCacheTier extends BaseCacheTier {
    public String name() { return "SSD"; }
}

class HDDCacheTier extends BaseCacheTier {
    public String name() { return "HDD"; }
}

class CacheManager {
    private List<CacheTier> tiers; // ordered top-down

    CacheManager(List<CacheTier> tiers) {
        this.tiers = tiers;
    }

    public synchronized String get(String key) {
        for (int i = 0; i < tiers.size(); i++) {
            CacheTier tier = tiers.get(i);
            CacheEntry entry = tier.get(key);
            if (entry != null) {
                // promote to top tier if found lower
                if (i > 0) {
                    tier.delete(key);
                    insertWithCascade(0, entry);
                }
                return entry.value;
            }
        }
        return null;
    }

    public synchronized void put(String key, String value) {
        CacheEntry entry = new CacheEntry(key, value);
        insertWithCascade(0, entry);
    }

    public synchronized void delete(String key) {
        for (CacheTier tier : tiers) {
            tier.delete(key);
        }
    }

    private void insertWithCascade(int tierIndex, CacheEntry entry) {
        if (tierIndex >= tiers.size()) return; // dropped from the system.

        CacheTier tier = tiers.get(tierIndex);

        if (!tier.isFull()) {
            tier.put(entry);
            return;
        }

        CacheEntry victim = tier.evictLRU();
        tier.put(entry);

        if (victim != null) {
            insertWithCascade(tierIndex + 1, victim);
        }
    }
}