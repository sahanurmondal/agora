package lab.concurrency;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * JMH: 95/5 read/write mix over the three MetadataStore disciplines.
 * Quick run: mvn -q -f lld-lab/pom.xml package -DskipTests
 *            java -cp lld-lab/target/classes:$(mvn -q -f lld-lab/pom.xml dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1) lab.concurrency.RwLockBenchmark
 * Results land in lld-lab/NUMBERS.md.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(8)
public class RwLockBenchmark {

    @Param({"synchronized", "rwlock", "stamped"})
    public String impl;

    private MetadataStore store;

    @Setup
    public void setup() {
        store = switch (impl) {
            case "rwlock" -> new MetadataStore.RwLock();
            case "stamped" -> new MetadataStore.Stamped();
            default -> new MetadataStore.Synchronized();
        };
        for (int i = 0; i < 1024; i++) {
            store.put("k" + i, "v" + i);
        }
    }

    @Benchmark
    public String mixed95_5() {
        int k = ThreadLocalRandom.current().nextInt(1024);
        if (ThreadLocalRandom.current().nextInt(100) < 5) {
            store.put("k" + k, "w");
            return "w";
        }
        return store.get("k" + k);
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder().include(RwLockBenchmark.class.getSimpleName()).build()).run();
    }
}
