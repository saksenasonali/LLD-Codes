#include <bits/stdc++.h>
using namespace std;


class PriorityLRUCache {
private:
   struct Entry {
       int value;
       int priority;
       list<int>::iterator it; // iterator to key's position in its priority bucket
// pointer to its position in LRU list
   };


   int capacity;
// map keeps priorities sorted
// list maintains LRU order
   mutable mutex mtx;
   unordered_map<int, Entry> mp;           // key -> {value, priority, iterator}
   map<int, list<int>> buckets;             // priority -> list of keys (front = MRU, back = LRU)


   void touch(int key) {
       // Move key to front of its priority bucket (most recently used)
       auto &entry = mp[key];
       auto &lst = buckets[entry.priority];
       lst.erase(entry.it);
       lst.push_front(key);
       entry.it = lst.begin();
   }


   void evictOne() {
       // Evict from the lowest priority bucket; within that bucket evict LRU (back)
       auto bucketIt = buckets.begin(); // smallest priority
// Skip/remove empty buckets 
       while (bucketIt != buckets.end() && bucketIt->second.empty()) {
           bucketIt = buckets.erase(bucketIt);
       }
       if (bucketIt == buckets.end()) return;
	// Get list of keys for lowest priority 
       list<int> &lst = bucketIt->second;
       int victim = lst.back();
       lst.pop_back();
       mp.erase(victim);


       if (lst.empty()) {
           buckets.erase(bucketIt);
       }
   }


public:
   explicit PriorityLRUCache(int cap) : capacity(cap) {}


   bool get(int key, int &valueOut) {
       lock_guard<mutex> lock(mtx);
       auto it = mp.find(key);
       if (it == mp.end()) return false;


       valueOut = it->second.value;
       touch(key);
       return true;
   }


   void put(int key, int value, int priority) {
lock_guard<mutex> lock(mtx);
       if (capacity <= 0) return;


       // Update existing key
       if (mp.count(key)) {
           auto &entry = mp[key];


           // Remove from old priority bucket if priority changes
           if (entry.priority != priority) {
               auto &oldList = buckets[entry.priority];
               oldList.erase(entry.it);
               if (oldList.empty()) buckets.erase(entry.priority);


               buckets[priority].push_front(key);
               entry.it = buckets[priority].begin();
               entry.priority = priority;
           } else {
               touch(key);
           }


           entry.value = value;
           return;
       }


       // Evict if full
       if ((int)mp.size() == capacity) {
           evictOne();
       }


       // Insert new key
       buckets[priority].push_front(key);
       mp[key] = {value, priority, buckets[priority].begin()};
   }


   void debugPrint() {
       cout << "Cache state:\n";
       for (auto &p : buckets) {
           cout << "Priority " << p.first << ": ";
           for (int key : p.second) cout << key << " ";
           cout << "\n";
       }
       cout << "\n";
   }
};


int main() {
   PriorityLRUCache cache(3);


   cache.put(1, 100, 2); // key=1, value=100, priority=2
   cache.put(2, 200, 1); // key=2, value=200, priority=1
   cache.put(3, 300, 1); // key=3, value=300, priority=1


   int val;
   if (cache.get(2, val)) cout << "get(2) = " << val << "\n"; // makes 2 MRU in priority 1


   cache.put(4, 400, 0); // capacity full -> evict from lowest priority bucket (priority 1), LRU within it
                         // key 3 gets evicted because 2 was recently accessed


   cache.debugPrint();


   return 0;
}


/*Time complexity:


get(key): O(log P), where P is the number of distinct priorities currently stored.
put(key, value, priority): O(log P).
evictOne(): O(log P) in the worst case.


unordered_map lookup/update is average O(1).
But map operations on buckets (begin, erase, operator[]) cost O(log P).


Space complexity:


O(C), where C is the cache capacity.
More precisely: one entry per cached key, 
plus one list node per key, plus at most one bucket list per active priority.*/
