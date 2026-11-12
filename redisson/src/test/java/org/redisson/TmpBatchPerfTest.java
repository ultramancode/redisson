package org.redisson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;

// Temporary timing check. Same workload as RedissonBatchTest.testPerformance with fewer batches and no timeout.
public class TmpBatchPerfTest extends RedisDockerTest {

    private static final String OUT = System.getProperty("tmp.out", "target/TmpBatchPerfTest.txt");

    @Test
    public void test() throws Exception {
        RMap<String, String> map = redisson.getMap("map");
        Map<String, String> m = new HashMap<>();
        for (int j = 0; j < 1000; j++) {
            m.put("" + j, "" + j);
        }
        map.putAll(m);

        StringBuilder sb = new StringBuilder();
        for (ExecutionMode mode : new ExecutionMode[] {ExecutionMode.IN_MEMORY, ExecutionMode.REDIS_WRITE_ATOMIC}) {
            run(mode, m, 100);
            long start = System.nanoTime();
            int batches = 500;
            run(mode, m, batches);
            long ms = (System.nanoTime() - start) / 1_000_000;
            sb.append(mode).append(": ").append(batches).append(" batches in ").append(ms).append(" ms, ")
                    .append(String.format("%.2f", ms / (double) batches)).append(" ms per batch\n");
        }
        Files.write(Paths.get(OUT), sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void run(ExecutionMode mode, Map<String, String> m, int batches) {
        for (int i = 0; i < batches; i++) {
            RBatch batch = redisson.createBatch(BatchOptions.defaults().executionMode(mode));
            batch.<String, String>getMap("map").getAllAsync(m.keySet());
            batch.execute();
        }
    }
}
