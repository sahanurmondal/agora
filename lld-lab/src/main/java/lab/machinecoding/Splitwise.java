package lab.machinecoding;

import java.util.*;

/**
 * Machine-coding 03 — Splitwise.
 * Patterns: Strategy (split rules). Algorithm: min-cash-flow settlement —
 * repeatedly match the largest debtor with the largest creditor; ≤ n-1
 * transactions settle everyone.
 */
public final class Splitwise {

    public interface SplitStrategy {
        /** participant → share owed (must sum to amount). */
        Map<String, Long> shares(long amountCents, List<String> participants);
    }

    public static final SplitStrategy EQUAL = (amount, people) -> {
        Map<String, Long> out = new LinkedHashMap<>();
        long base = amount / people.size();
        long remainder = amount % people.size();
        for (int i = 0; i < people.size(); i++) {
            out.put(people.get(i), base + (i < remainder ? 1 : 0)); // spread the odd cents
        }
        return out;
    };

    public static SplitStrategy exact(Map<String, Long> exactShares) {
        return (amount, people) -> {
            if (exactShares.values().stream().mapToLong(Long::longValue).sum() != amount) {
                throw new IllegalArgumentException("exact shares must sum to amount");
            }
            return exactShares;
        };
    }

    public record Transfer(String from, String to, long amountCents) {
    }

    // net balance: positive = is owed money, negative = owes
    private final Map<String, Long> net = new HashMap<>();

    public void addExpense(String payer, long amountCents, List<String> participants, SplitStrategy strategy) {
        net.merge(payer, amountCents, Long::sum);
        strategy.shares(amountCents, participants)
                .forEach((person, share) -> net.merge(person, -share, Long::sum));
    }

    public long balanceOf(String person) {
        return net.getOrDefault(person, 0L);
    }

    /** Min-cash-flow: greedy max-debtor ↔ max-creditor matching. */
    public List<Transfer> simplify() {
        PriorityQueue<Map.Entry<String, Long>> creditors =
                new PriorityQueue<>((a, b) -> Long.compare(b.getValue(), a.getValue()));
        PriorityQueue<Map.Entry<String, Long>> debtors =
                new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
        net.entrySet().forEach(e -> {
            if (e.getValue() > 0) creditors.add(new AbstractMap.SimpleEntry<>(e));
            else if (e.getValue() < 0) debtors.add(new AbstractMap.SimpleEntry<>(e));
        });

        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            var credit = creditors.poll();
            var debt = debtors.poll();
            long settled = Math.min(credit.getValue(), -debt.getValue());
            transfers.add(new Transfer(debt.getKey(), credit.getKey(), settled));
            if (credit.getValue() > settled) {
                credit.setValue(credit.getValue() - settled);
                creditors.add(credit);
            }
            if (-debt.getValue() > settled) {
                debt.setValue(debt.getValue() + settled);
                debtors.add(debt);
            }
        }
        return transfers;
    }
}
