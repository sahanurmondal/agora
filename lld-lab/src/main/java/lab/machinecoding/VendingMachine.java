package lab.machinecoding;

import java.util.HashMap;
import java.util.Map;

/**
 * Machine-coding 05 — vending machine: the canonical explicit State pattern.
 * Each state answers every event; illegal events are handled IN the state
 * (no giant if/else on an enum in the machine itself) — the same shape as
 * order/payment lifecycles and the circuit breaker in the flagship.
 */
public final class VendingMachine {

    public record Item(String code, long priceCents, int stock) {
    }

    private final Map<String, Item> inventory = new HashMap<>();
    private long insertedCents = 0;
    private String selected = null;
    private State state = new Idle();
    private String lastMessage = "";

    public VendingMachine(Map<String, Item> items) {
        inventory.putAll(items);
    }

    // events
    public void insertCoin(long cents) {
        state.insertCoin(cents);
    }

    public void selectItem(String code) {
        state.selectItem(code);
    }

    public long refund() {
        return state.refund();
    }

    public String stateName() {
        return state.getClass().getSimpleName();
    }

    public String lastMessage() {
        return lastMessage;
    }

    public int stockOf(String code) {
        return inventory.get(code).stock();
    }

    private interface State {
        void insertCoin(long cents);

        void selectItem(String code);

        long refund();
    }

    private final class Idle implements State {
        @Override
        public void insertCoin(long cents) {
            insertedCents = cents;
            state = new HasMoney();
            lastMessage = "credited " + cents;
        }

        @Override
        public void selectItem(String code) {
            lastMessage = "insert coins first";
        }

        @Override
        public long refund() {
            lastMessage = "nothing to refund";
            return 0;
        }
    }

    private final class HasMoney implements State {
        @Override
        public void insertCoin(long cents) {
            insertedCents += cents;
            lastMessage = "credited " + insertedCents;
        }

        @Override
        public void selectItem(String code) {
            Item item = inventory.get(code);
            if (item == null || item.stock() == 0) {
                lastMessage = "unavailable: " + code;
                return;
            }
            if (insertedCents < item.priceCents()) {
                lastMessage = "insufficient: need " + (item.priceCents() - insertedCents) + " more";
                return;
            }
            selected = code;
            state = new Dispensing();
            dispense();
        }

        @Override
        public long refund() {
            long back = insertedCents;
            insertedCents = 0;
            state = new Idle();
            lastMessage = "refunded " + back;
            return back;
        }
    }

    private final class Dispensing implements State {
        @Override
        public void insertCoin(long cents) {
            lastMessage = "busy dispensing";
        }

        @Override
        public void selectItem(String code) {
            lastMessage = "busy dispensing";
        }

        @Override
        public long refund() {
            lastMessage = "busy dispensing";
            return 0;
        }
    }

    private void dispense() {
        Item item = inventory.get(selected);
        inventory.put(selected, new Item(item.code(), item.priceCents(), item.stock() - 1));
        long change = insertedCents - item.priceCents();
        insertedCents = 0;
        selected = null;
        state = new Idle();
        lastMessage = "dispensed, change " + change;
    }
}
