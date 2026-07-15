package lab.concurrency;

/**
 * Exercise 07 — the deadlock IS the exercise.
 *
 * transferNaive locks (from, to) in argument order: two opposing transfers
 * lock a→b and b→a — circular wait, all four Coffman conditions met.
 * transferOrdered breaks "circular wait" by always locking the lower id
 * first: a GLOBAL lock order makes cycles impossible. (The tryLock+backoff
 * alternative breaks "hold and wait" instead; both are valid fixes.)
 */
public final class DeadlockDemo {

    public static final class Account {
        final long id;
        long balance;

        public Account(long id, long balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    /** Deadlocks when called concurrently with opposing pairs. */
    public static void transferNaive(Account from, Account to, long amount) {
        synchronized (from) {
            sleepBriefly(); // widen the race window so the test reliably deadlocks
            synchronized (to) {
                from.balance -= amount;
                to.balance += amount;
            }
        }
    }

    /** Cycle-proof: lock order = ascending account id, regardless of direction. */
    public static void transferOrdered(Account from, Account to, long amount) {
        Account first = from.id < to.id ? from : to;
        Account second = from.id < to.id ? to : from;
        synchronized (first) {
            synchronized (second) {
                from.balance -= amount;
                to.balance += amount;
            }
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
