package lab.machinecoding;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Machine-coding 02 — BookMyShow-style seat booking.
 * Patterns: State (AVAILABLE→HELD→BOOKED with TTL holds). Concurrency: per-seat
 * monitor for the hold race + version check at confirm (optimistic: the hold
 * you confirm must be the hold you took).
 *
 * DELIBERATELY the same problem inventory-service solves at cluster scale
 * (DB conditional update / row locks) — same invariant, two scales.
 */
public final class SeatBooking {

    public enum State {AVAILABLE, HELD, BOOKED}

    public static final class Seat {
        final String id;
        State state = State.AVAILABLE;
        String holder;
        long holdExpiresMs;
        int version; // bumps on every transition; confirm() must present the observed version

        Seat(String id) {
            this.id = id;
        }
    }

    public record Hold(String seatId, String user, int version) {
    }

    private final Map<String, Seat> seats = new ConcurrentHashMap<>();
    private final long holdTtlMs;

    public SeatBooking(List<String> seatIds, long holdTtlMs) {
        seatIds.forEach(id -> seats.put(id, new Seat(id)));
        this.holdTtlMs = holdTtlMs;
    }

    /** null = lost the race (seat not available). */
    public Hold hold(String seatId, String user, long nowMs) {
        Seat seat = seats.get(seatId);
        synchronized (seat) {
            expireIfDue(seat, nowMs);
            if (seat.state != State.AVAILABLE) {
                return null;
            }
            seat.state = State.HELD;
            seat.holder = user;
            seat.holdExpiresMs = nowMs + holdTtlMs;
            seat.version++;
            return new Hold(seatId, user, seat.version);
        }
    }

    /** Optimistic confirm: stale hold (expired+resold, or wrong version) is rejected. */
    public boolean confirm(Hold hold, long nowMs) {
        Seat seat = seats.get(hold.seatId());
        synchronized (seat) {
            expireIfDue(seat, nowMs);
            if (seat.state != State.HELD || !hold.user().equals(seat.holder)
                    || seat.version != hold.version()) {
                return false;
            }
            seat.state = State.BOOKED;
            seat.version++;
            return true;
        }
    }

    public State stateOf(String seatId, long nowMs) {
        Seat seat = seats.get(seatId);
        synchronized (seat) {
            expireIfDue(seat, nowMs);
            return seat.state;
        }
    }

    private void expireIfDue(Seat seat, long nowMs) {
        if (seat.state == State.HELD && nowMs >= seat.holdExpiresMs) {
            seat.state = State.AVAILABLE; // lazy expiry: no reaper thread needed
            seat.holder = null;
            seat.version++;
        }
    }
}
