import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class ShopifyLite {

    static class Store {
        private final Map<Long, Product> products = new ConcurrentHashMap<>();
        private final Map<String, Cart> carts = new ConcurrentHashMap<>();
        private final Map<Long, Order> orders = new ConcurrentHashMap<>();
        private final AtomicLong pid = new AtomicLong(1);
        private final AtomicLong oid = new AtomicLong(1);
        private final ReentrantLock lock = new ReentrantLock();

        long addProduct(String name, int price, int stock) {
            if (name == null || name.isBlank() || price < 0 || stock < 0)
                throw new IllegalArgumentException();
            long id = pid.getAndIncrement();
            products.put(id, new Product(id, name, price, stock));
            return id;
        }

        String createCart() {
            String id = UUID.randomUUID().toString();
            carts.put(id, new Cart());
            return id;
        }

        void addToCart(String cartId, long productId, int qty) {
            if (qty <= 0) throw new IllegalArgumentException();
            Cart cart = carts.get(cartId);
            if (cart == null || !products.containsKey(productId)) throw new IllegalArgumentException();
            cart.add(productId, qty);
        }

        Order checkout(String cartId, PaymentProcessor payment) {
            if (payment == null) throw new IllegalArgumentException();

            lock.lock();
            try {
                Cart cart = carts.get(cartId);
                if (cart == null) throw new IllegalArgumentException("Cart not found");

                Map<Long, Integer> items = cart.snapshot();
                if (items.isEmpty()) throw new IllegalStateException("Cart empty");

                long total = 0;
                for (var e : items.entrySet()) {
                    Product p = products.get(e.getKey());
                    int qty = e.getValue();
                    if (p == null || p.stock < qty) throw new IllegalStateException("Stock issue");
                    total += (long) p.price * qty;
                }

                for (var e : items.entrySet()) products.get(e.getKey()).stock -= e.getValue();

                Order order = new Order(oid.getAndIncrement(), total, items);
                if (!payment.pay(order)) {
                    rollback(items);
                    throw new RuntimeException("Payment failed");
                }

                cart.clear();
                orders.put(order.id, order);
                return order;
            } finally {
                lock.unlock();
            }
        }

        private void rollback(Map<Long, Integer> items) {
            for (var e : items.entrySet()) {
                Product p = products.get(e.getKey());
                if (p != null) p.stock += e.getValue();
            }
        }
    }

    static class Product {
        long id;
        String name;
        int price, stock;
        Product(long id, String name, int price, int stock) {
            this.id = id; this.name = name; this.price = price; this.stock = stock;
        }
    }

    static class Cart {
        private final Map<Long, Integer> items = new ConcurrentHashMap<>();

        void add(long productId, int qty) {
            items.merge(productId, qty, Integer::sum);
        }

        Map<Long, Integer> snapshot() {
            // original cart isn’t modified during checkout
            // prevents concurrent modification issues during checkout 
            return new HashMap<>(items);
        }

        void clear() {
            items.clear();
        }
    }

    static class Order {
        long id;
        long total;
        Map<Long, Integer> items;

        Order(long id, long total, Map<Long, Integer> items) {
            this.id = id;
            this.total = total;
            this.items = Collections.unmodifiableMap(new HashMap<>(items));
        }

        public String toString() {
            return "Order{id=" + id + ", total=" + total + ", items=" + items + "}";
        }
    }

    interface PaymentProcessor {
        boolean pay(Order order);
    }

    static class NoOpPayment implements PaymentProcessor {
        public boolean pay(Order order) { return true; }
    }

    public static void main(String[] args) {
        Store store = new Store();

        long tshirt = store.addProduct("TShirt", 100, 10);
        long hoodie = store.addProduct("Hoodie", 200, 5);

        String cartId = store.createCart();
        store.addToCart(cartId, tshirt, 2);
        store.addToCart(cartId, hoodie, 1);

        Order order = store.checkout(cartId, new NoOpPayment());
        System.out.println(order);
    }
}