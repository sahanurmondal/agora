package lab.machinecoding;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Machine-coding 01 — parking lot.
 * Patterns: Strategy (spot selection, pricing). Concurrency: spot claim is an
 * AtomicBoolean CAS — two gates racing for the last spot cannot both win.
 */
public final class ParkingLot {

    public enum VehicleType {BIKE, CAR, TRUCK}

    public static final class Spot {
        final String id;
        final VehicleType fits;
        final AtomicBoolean occupied = new AtomicBoolean(false);

        public Spot(String id, VehicleType fits) {
            this.id = id;
            this.fits = fits;
        }
    }

    public record Ticket(long id, String spotId, VehicleType type, long entryMs) {
    }

    /** Strategy: which free spot to take. */
    public interface SpotSelection {
        Optional<Spot> select(List<Spot> free, VehicleType type);
    }

    public static final SpotSelection FIRST_FIT = (free, type) ->
            free.stream().filter(s -> s.fits == type).findFirst();

    /** Strategy: pricing per vehicle type. */
    public interface Pricing {
        long fee(VehicleType type, Duration parked);
    }

    public static final Pricing HOURLY = (type, parked) -> {
        long hours = Math.max(1, (parked.toMinutes() + 59) / 60); // ceil, min 1h
        long rate = switch (type) {
            case BIKE -> 10;
            case CAR -> 30;
            case TRUCK -> 60;
        };
        return hours * rate;
    };

    private final List<Spot> spots;
    private final SpotSelection selection;
    private final Pricing pricing;
    private final Map<Long, Ticket> active = new ConcurrentHashMap<>();
    private final AtomicLong ticketSeq = new AtomicLong();

    public ParkingLot(List<Spot> spots, SpotSelection selection, Pricing pricing) {
        this.spots = List.copyOf(spots);
        this.selection = selection;
        this.pricing = pricing;
    }

    /** Empty = lot full for this type. CAS loop: selection is advisory, the claim decides. */
    public Optional<Ticket> park(VehicleType type) {
        for (Spot candidate : spots) {
            if (candidate.fits == type && !candidate.occupied.get()
                    && candidate.occupied.compareAndSet(false, true)) {
                Ticket t = new Ticket(ticketSeq.incrementAndGet(), candidate.id, type, System.currentTimeMillis());
                active.put(t.id(), t);
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public long unpark(Ticket ticket, long nowMs) {
        if (active.remove(ticket.id()) == null) {
            throw new IllegalArgumentException("unknown/used ticket " + ticket.id());
        }
        spots.stream().filter(s -> s.id.equals(ticket.spotId())).findFirst()
                .orElseThrow().occupied.set(false);
        return pricing.fee(ticket.type(), Duration.ofMillis(nowMs - ticket.entryMs()));
    }

    public long freeCount(VehicleType type) {
        return spots.stream().filter(s -> s.fits == type && !s.occupied.get()).count();
    }
}
