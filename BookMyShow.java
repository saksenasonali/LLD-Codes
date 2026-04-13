import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class Main {

    static class Movie {
        String id, name;
        Movie(String id, String name) { this.id = id; this.name = name; }
    }

    static class Booking {
        long id;
        String showId, user;
        List<String> seats;
        Booking(long id, String showId, String user, List<String> seats) {
            this.id = id; this.showId = showId; this.user = user; this.seats = seats;
        }
        public String toString() {
            return "Booking{id=" + id + ", showId=" + showId + ", user=" + user + ", seats=" + seats + "}";
        }
    }

    static class Show {
        String id, city;
        Movie movie;
        List<String> allSeats;
        Set<String> booked = new HashSet<>();
        ReentrantLock lock = new ReentrantLock();

        Show(String id, Movie movie, String city, List<String> seats) {
            this.id = id; this.movie = movie; this.city = city; this.allSeats = seats;
        }

        List<String> availableSeats() {
            List<String> ans = new ArrayList<>();
            for (String s : allSeats) if (!booked.contains(s)) ans.add(s);
            return ans;
        }

        List<String> book(int count) {
            if (availableSeats().size() < count) return Collections.emptyList();
            List<String> chosen = new ArrayList<>();
            for (String s : allSeats) {
                if (!booked.contains(s)) {
                    chosen.add(s);
                    if (chosen.size() == count) break;
                }
            }
            booked.addAll(chosen);
            return chosen;
        }

        boolean cancel(List<String> seats) {
            for (String s : seats) if (!booked.contains(s)) return false;
            booked.removeAll(seats);
            return true;
        }
    }

    static class BookMyShowSystem {
        private final Map<String, Show> shows = new ConcurrentHashMap<>();
        private final Map<String, List<Show>> byCity = new ConcurrentHashMap<>();
        private final Map<String, List<Show>> byMovie = new ConcurrentHashMap<>();
        private final Map<Long, Booking> bookings = new ConcurrentHashMap<>();
        private final AtomicLong bookingSeq = new AtomicLong(0);

        void addShow(Show show) {
            shows.put(show.id, show);
            byCity.computeIfAbsent(show.city, k -> Collections.synchronizedList(new ArrayList<>())).add(show);
            byMovie.computeIfAbsent(show.movie.name, k -> Collections.synchronizedList(new ArrayList<>())).add(show);
        }

        List<Show> searchByCity(String city) {
            return byCity.getOrDefault(city, Collections.emptyList());
        }

        List<Show> searchByMovie(String movie) {
            return byMovie.getOrDefault(movie, Collections.emptyList());
        }

        Booking book(String showId, String user, int seatCount) {
            Show show = shows.get(showId);
            if (show == null) return null;

            show.lock.lock();
            try {
                List<String> seats = show.book(seatCount);
                if (seats.isEmpty()) return null;
                long id = bookingSeq.incrementAndGet();
                Booking b = new Booking(id, showId, user, seats);
                bookings.put(id, b);
                return b;
            } finally {
                show.lock.unlock();
            }
        }

        boolean cancel(long bookingId) {
            Booking b = bookings.get(bookingId);
            if (b == null) return false;

            Show show = shows.get(b.showId);
            if (show == null) return false;

            show.lock.lock();
            try {
                b = bookings.remove(bookingId);
                if (b == null) return false;
                return show.cancel(b.seats);
            } finally {
                show.lock.unlock();
            }
        }
    }

    public static void main(String[] args) {
        BookMyShowSystem system = new BookMyShowSystem();

        Movie m1 = new Movie("m1", "Inception");
        Show s1 = new Show("show1", m1, "Bangalore",
                Arrays.asList("S1", "S2", "S3", "S4", "S5"));

        system.addShow(s1);

        System.out.println(system.searchByCity("Bangalore").size()); // 1

        Booking b1 = system.book("show1", "Alice", 3);
        System.out.println(b1);

        Booking b2 = system.book("show1", "Bob", 3);
        System.out.println(b2); // null, only 2 seats left

        System.out.println(system.cancel(b1.id)); // true

        Booking b3 = system.book("show1", "Charlie", 2);
        System.out.println(b3);
    }
}