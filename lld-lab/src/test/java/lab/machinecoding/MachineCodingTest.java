package lab.machinecoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MachineCodingTest {

    // ---------- 01 parking lot ----------

    @Test
    @Timeout(20)
    void parkingLot_noDoubleAssignmentUnderRacingGates() throws InterruptedException {
        List<ParkingLot.Spot> spots = new ArrayList<>();
        for (int i = 0; i < 10; i++) spots.add(new ParkingLot.Spot("c" + i, ParkingLot.VehicleType.CAR));
        ParkingLot lot = new ParkingLot(spots, ParkingLot.FIRST_FIT, ParkingLot.HOURLY);

        int gates = 16;
        AtomicInteger parked = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(gates);
        for (int g = 0; g < gates; g++) {
            Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 5; i++) {
                    lot.park(ParkingLot.VehicleType.CAR).ifPresent(t -> parked.incrementAndGet());
                }
                done.countDown();
            });
        }
        done.await();
        assertEquals(10, parked.get(), "exactly capacity parked, no double-assignment");
        assertEquals(0, lot.freeCount(ParkingLot.VehicleType.CAR));

        // pricing: 90 min car = 2h * 30
        var t = new ParkingLot.Ticket(999, "cX", ParkingLot.VehicleType.CAR, 0);
        assertEquals(60, ParkingLot.HOURLY.fee(ParkingLot.VehicleType.CAR, java.time.Duration.ofMinutes(90)));
        assertThrows(IllegalArgumentException.class, () -> lot.unpark(t, 1000)); // unknown ticket
    }

    // ---------- 02 seat booking ----------

    @Test
    @Timeout(20)
    void seatBooking_raceYieldsOneWinner_holdExpiryReopens() throws InterruptedException {
        SeatBooking show = new SeatBooking(List.of("A1", "A2"), 1_000);

        int racers = 8;
        AtomicInteger wins = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(racers);
        for (int r = 0; r < racers; r++) {
            final String user = "u" + r;
            Thread.ofPlatform().start(() -> {
                if (show.hold("A1", user, 0) != null) wins.incrementAndGet();
                done.countDown();
            });
        }
        done.await();
        assertEquals(1, wins.get(), "exactly one hold winner");

        // expiry: hold lapses → seat reopens → new hold + confirm succeeds;
        // the STALE hold's confirm must fail (version moved on)
        SeatBooking.Hold stale = show.hold("A2", "alice", 0);
        assertNotNull(stale);
        assertEquals(SeatBooking.State.AVAILABLE, show.stateOf("A2", 2_000), "hold expired");
        SeatBooking.Hold fresh = show.hold("A2", "bob", 2_000);
        assertNotNull(fresh);
        assertFalse(show.confirm(stale, 2_100), "stale hold rejected by version check");
        assertTrue(show.confirm(fresh, 2_100));
        assertEquals(SeatBooking.State.BOOKED, show.stateOf("A2", 2_200));
    }

    // ---------- 03 splitwise ----------

    @Test
    void splitwise_balancesConserve_andSimplifySettles() {
        Splitwise sw = new Splitwise();
        sw.addExpense("alice", 9000, List.of("alice", "bob", "carol"), Splitwise.EQUAL);
        sw.addExpense("bob", 3000, List.of("bob", "carol"), Splitwise.EQUAL);

        long total = sw.balanceOf("alice") + sw.balanceOf("bob") + sw.balanceOf("carol");
        assertEquals(0, total, "net balances always sum to zero");
        assertEquals(6000, sw.balanceOf("alice"));
        assertEquals(-1500, sw.balanceOf("bob"));
        assertEquals(-4500, sw.balanceOf("carol"));

        List<Splitwise.Transfer> transfers = sw.simplify();
        assertTrue(transfers.size() <= 2, "min-cash-flow: <= n-1 transfers");
        assertEquals(6000, transfers.stream()
                .filter(t -> t.to().equals("alice")).mapToLong(Splitwise.Transfer::amountCents).sum());
    }

    // ---------- 04 mini logger ----------

    @Test
    @Timeout(20)
    void logger_decoratorComposes_asyncFlushesOnClose() {
        MiniLogger.MemoryAppender sink = new MiniLogger.MemoryAppender();
        MiniLogger.AsyncAppender async = new MiniLogger.AsyncAppender(sink, 128);
        try (MiniLogger log = new MiniLogger.Builder()
                .threshold(MiniLogger.Level.INFO)
                .formatter(MiniLogger.withTimestamp(MiniLogger.PLAIN))
                .appender(async)
                .build()) {
            log.log(MiniLogger.Level.DEBUG, "filtered out");
            for (int i = 0; i < 500; i++) {
                log.log(MiniLogger.Level.INFO, "msg-" + i);
            }
        } // close() = poison-pill flush
        assertEquals(500, sink.lines.size(), "async close must flush everything");
        assertTrue(sink.lines.get(0).contains("[INFO] msg-0"));
        assertTrue(sink.lines.get(0).matches("^\\d{4}-.*"), "timestamp decorator applied");
    }

    // ---------- 05 vending machine ----------

    @Test
    void vendingMachine_stateTransitionsAndIllegalEvents() {
        VendingMachine vm = new VendingMachine(Map.of(
                "COKE", new VendingMachine.Item("COKE", 65, 1)));

        assertEquals("Idle", vm.stateName());
        vm.selectItem("COKE");
        assertEquals("insert coins first", vm.lastMessage());

        vm.insertCoin(50);
        assertEquals("HasMoney", vm.stateName());
        vm.selectItem("COKE");
        assertTrue(vm.lastMessage().startsWith("insufficient"));

        vm.insertCoin(25);
        vm.selectItem("COKE");
        assertEquals("Idle", vm.stateName(), "dispense returns to Idle");
        assertEquals("dispensed, change 10", vm.lastMessage());
        assertEquals(0, vm.stockOf("COKE"));

        vm.insertCoin(100);
        vm.selectItem("COKE");
        assertTrue(vm.lastMessage().startsWith("unavailable"), "out of stock");
        assertEquals(100, vm.refund());
        assertEquals("Idle", vm.stateName());
    }
}
