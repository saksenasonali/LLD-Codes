/*
                +----------------------+
                |        Node          |
                +----------------------+
                | name                 |
                | parent: Directory    |
                | createdAt            |
                | updatedAt            |
                | permissions          |
                +----------------------+
                | getPath()            |
                | rename()             |
                | delete()             |
                | getSize()            |
                +----------+-----------+
                           / \
                          /   \
                         /     \
        +----------------+       +-------------------+
        |     File       |       |     Directory     |
        +----------------+       +-------------------+
        | content        |       | children: List<Node> |
        +----------------+       +-------------------+
        | read()         |       | addChild()        |
        | write()        |       | removeChild()     |
        | append()       |       | listChildren()    |
        +----------------+       +-------------------+

+----------------------+
|     FileSystem       |
+----------------------+
| root: Directory      |
| storage: StorageEngine |
| search: SearchStrategy |
+----------------------+
| createFile()         |
| createDirectory()    |
| delete()             |
| move()               |
| copy()               |
| search()             |
+----------------------+

+----------------------+
|   StorageEngine      |
+----------------------+
| save(node)           |
| load(path)           |
| delete(path)         |
+----------------------+

+----------------------+
|   SearchStrategy     |
+----------------------+
| search(root, query)  |
+----------------------+

*/

import java.util.*;

abstract class Node { 
    // Base class for both File and Directory.// 
    protected String name;
    protected Directory parent;
    protected long createdAt;
    protected long updatedAt; 

    public Node(String name) {
        this.name = name;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getName() { return name; }

    public String getPath() {
        if (parent == null) return "/" + name;
        String p = parent.getPath();
        return p.equals("/") ? p + name : p + "/" + name;
    }

    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = System.currentTimeMillis();
    }

    public abstract int getSize();

    public void delete() {
        if (parent != null) parent.removeChild(this);
    }
}

class File extends Node {
    // builder pattern
    private StringBuilder content = new StringBuilder();

    public File(String name) {
        super(name);
    }

    public String read() {
        return content.toString();
    }

    public void write(String data) {
        content.setLength(0); //overwrite file
        content.append(data);
        updatedAt = System.currentTimeMillis();
    }

    public void append(String data) {
        content.append(data);
        updatedAt = System.currentTimeMillis();
    }

    @Override
    public int getSize() {
        return content.length();
    }
}

class Directory extends Node {
    private final Map<String, Node> children = new LinkedHashMap<>();

    public Directory(String name) {
        super(name);
    }

    public void addChild(Node node) {
        if (children.containsKey(node.getName())) {
            throw new IllegalArgumentException("Name already exists: " + node.getName());
        }
        children.put(node.getName(), node);
        node.parent = this;
        updatedAt = System.currentTimeMillis();
    }

    public void removeChild(Node node) {
        children.remove(node.getName());
        node.parent = null;
        updatedAt = System.currentTimeMillis();
    }
// prevents external modification. Good encapsulation practice. returns copy
    public List<Node> listChildren() {
        return new ArrayList<>(children.values());
    }

    public Node findChild(String name) {
        return children.get(name);
    }

    @Override
    public int getSize() {
        int size = 0;
        for (Node child : children.values()) {
            size += child.getSize();
        }
        return size;
    }
}

interface SearchStrategy {
    List<Node> search(Directory root, String query);
}

class NameSearchStrategy implements SearchStrategy {
    @Override
    public List<Node> search(Directory root, String query) {
        List<Node> result = new ArrayList<>();
        dfs(root, query, result);
        return result;
    }

    private void dfs(Directory dir, String query, List<Node> result) {
        for (Node child : dir.listChildren()) {
            if (child.getName().contains(query)) {
                result.add(child);
            }
            // composite pattern Files = leaf nodes → stop Directories = internal nodes → recurse
            if (child instanceof Directory) { // runtime
                dfs((Directory) child, query, result); 
            }
        }
    }
}

interface StorageEngine {
    void save(Node node);
    void delete(Node node);
}

class InMemoryStorageEngine implements StorageEngine {
    @Override
    public void save(Node node) {
        // persist to memory map / mock store 
    }

    @Override
    public void delete(Node node) {
        // remove from memory map / mock store
    }
}

class FileSystem {
    private final Directory root;
    private final SearchStrategy searchStrategy;
    private final StorageEngine storageEngine; // Dependency Injection

    public FileSystem(SearchStrategy searchStrategy, StorageEngine storageEngine) { // Constructor injection.
        this.root = new Directory("");
        this.searchStrategy = searchStrategy;
        this.storageEngine = storageEngine;
    }

    public File createFile(Directory parent, String name) {
        File file = new File(name);
        parent.addChild(file);
        storageEngine.save(file);
        return file;
    }

    public Directory createDirectory(Directory parent, String name) {
        Directory dir = new Directory(name);
        parent.addChild(dir);
        storageEngine.save(dir);
        return dir;
    }

    public void delete(Node node) {
        storageEngine.delete(node);
        node.delete();
    }

    public List<Node> search(String query) {
        return searchStrategy.search(root, query);
    }

    public Directory getRoot() {
        return root;
    }
}