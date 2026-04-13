import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class Main {
    enum VehicleType { BIKE, CAR, TRUCK }
    enum SpotType { BIKE, COMPACT, LARGE }

    static class Vehicle {
        String number;
        VehicleType type;
        Vehicle(String number, VehicleType type) { this.number = number; this.type = type; }
    }

    static class ParkingTicket {
        long id;
        String vehicleNo, spotId;
        int floorNo;
        VehicleType type;
        ParkingTicket(long id, String vehicleNo, VehicleType type, int floorNo, String spotId) {
            this.id = id; this.vehicleNo = vehicleNo; this.type = type; this.floorNo = floorNo; this.spotId = spotId;
        }
        public String toString() {
            return "Ticket{id=" + id + ", vehicle=" + vehicleNo + ", floor=" + floorNo + ", spot=" + spotId + "}";
        }
    }

    static boolean fits(VehicleType v, SpotType s) {
        if (v == VehicleType.BIKE) return true;
        if (v == VehicleType.CAR) return s != SpotType.BIKE;
        return s == SpotType.LARGE;
    }

    static SpotType[] pref(VehicleType v) {
        if (v == VehicleType.BIKE) return new SpotType[]{SpotType.BIKE, SpotType.COMPACT, SpotType.LARGE};
        if (v == VehicleType.CAR) return new SpotType[]{SpotType.COMPACT, SpotType.LARGE};
        return new SpotType[]{SpotType.LARGE};
    }

    static class Floor {
        final int no;
        final ReentrantLock lock = new ReentrantLock();
        final EnumMap<SpotType, Deque<String>> free = new EnumMap<>(SpotType.class);
        final Map<String, SpotType> spotType = new HashMap<>();
        final Set<String> occupied = new HashSet<>();

        Floor(int no, int b, int c, int l) {
            this.no = no;
            for (SpotType t : SpotType.values()) free.put(t, new ArrayDeque<>());
            add(SpotType.BIKE, b, "B");
            add(SpotType.COMPACT, c, "C");
            add(SpotType.LARGE, l, "L");
        }

        void add(SpotType t, int n, String p) {
            for (int i = 1; i <= n; i++) {
                String id = "F" + no + "-" + p + i;
                free.get(t).offerLast(id);
                spotType.put(id, t);
            }
        }

        String allocate(Vehicle v) {
            for (SpotType t : pref(v.type)) {
                String id = free.get(t).pollFirst();
                if (id != null && fits(v.type, t)) {
                    occupied.add(id);
                    return id;
                }
            }
            return null;
        }

        boolean release(String spotId) {
            if (!occupied.remove(spotId)) return false;
            free.get(spotType.get(spotId)).offerFirst(spotId);
            return true;
        }

        int available(SpotType t) { return free.get(t).size(); }
    }

    static class ParkingLot {
        final Map<Integer, Floor> floors = new LinkedHashMap<>();
        final ConcurrentHashMap<Long, ParkingTicket> tickets = new ConcurrentHashMap<>();
        final AtomicLong seq = new AtomicLong(0);

        ParkingLot(List<Floor> fs) {
            for (Floor f : fs) floors.put(f.no, f);
        }

        ParkingTicket park(Vehicle v) {
            for (Floor f : floors.values()) {
                f.lock.lock();
                try {
                    String spot = f.allocate(v);
                    if (spot != null) {
                        long id = seq.incrementAndGet();
                        ParkingTicket t = new ParkingTicket(id, v.number, v.type, f.no, spot);
                        tickets.put(id, t);
                        return t;
                    }
                } finally {
                    f.lock.unlock();
                }
            }
            return null;
        }

        Vehicle unpark(long ticketId) {
            ParkingTicket t = tickets.remove(ticketId);
            if (t == null) return null;
            Floor f = floors.get(t.floorNo);
            if (f == null) return null;

            f.lock.lock();
            try {
                return f.release(t.spotId) ? new Vehicle(t.vehicleNo, t.type) : null;
            } finally {
                f.lock.unlock();
            }
        }

        int available(SpotType t) {
            int count = 0;
            for (Floor f : floors.values()) {
                f.lock.lock();
                try { count += f.available(t); }
                finally { f.lock.unlock(); }
            }
            return count;
        }
    }

    public static void main(String[] args) {
        ParkingLot lot = new ParkingLot(Arrays.asList(
                new Floor(1, 1, 2, 1),
                new Floor(2, 1, 2, 1)
        ));

        ParkingTicket t1 = lot.park(new Vehicle("KA-01-BIKE", VehicleType.BIKE));
        ParkingTicket t2 = lot.park(new Vehicle("KA-02-CAR", VehicleType.CAR));
        ParkingTicket t3 = lot.park(new Vehicle("KA-03-TRUCK", VehicleType.TRUCK));

        System.out.println(t1);
        System.out.println(t2);
        System.out.println(t3);
        System.out.println("Free compact: " + lot.available(SpotType.COMPACT));

        System.out.println(lot.unpark(t2.id));
        System.out.println("Free compact after unpark: " + lot.available(SpotType.COMPACT));
    }
}