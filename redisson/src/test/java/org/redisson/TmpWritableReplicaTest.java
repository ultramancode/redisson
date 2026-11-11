package org.redisson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.api.bitset.BitFieldArgs;
import org.redisson.api.bitset.BitOffset;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import static org.assertj.core.api.Assertions.assertThat;

// REDIS_READ_ATOMIC as the very first BITFIELD use of a client (BITFIELD_RO availability unknown)
public class TmpWritableReplicaTest extends RedisDockerTest {

    private static final String OUT = System.getProperty("tmp.out", "target/TmpWritableReplicaTest.txt");

    private static void emit(String line) {
        try {
            Files.write(Paths.get(OUT), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String root(Throwable t) {
        if (t instanceof TimeoutException) {
            return "HANG";
        }
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        String m = String.valueOf(c.getMessage()).split("\\. channel")[0];
        return "FAIL(" + (m.length() > 28 ? m.substring(0, 28) : m) + ")";
    }

    private String run(RedissonClient client, String call) {
        try {
            RBatch b = client.createBatch(BatchOptions.defaults().executionMode(ExecutionMode.REDIS_READ_ATOMIC));
            RFuture<?> f;
            Object expected;
            if (call.equals("getLong")) {
                f = b.getBitSet("ms").getLongAsync(0);
                expected = -1L;
            } else {
                f = b.getBitSet("ms").bitFieldAsync(BitFieldArgs.create().getUnsigned(8, BitOffset.bit(0)));
                expected = java.util.Collections.singletonList(255L);
            }
            b.executeAsync().toCompletableFuture().get(6, TimeUnit.SECONDS);
            Object v = f.toCompletableFuture().get(3, TimeUnit.SECONDS);
            return expected.equals(v) ? "OK" : "WRONG " + v;
        } catch (Throwable t) {
            return root(t);
        }
    }

    private void fresh(String label, String image, Config config) {
        for (String call : new String[] {"getLong", "bitField"}) {
            RedissonClient client = Redisson.create(config);
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    sb.append(i == 0 ? "" : ", ").append(run(client, call));
                }
                emit(image + " | " + label + " | fresh READ_ATOMIC x4 | " + call + " -> " + sb);
            } finally {
                client.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis:5.0.14", IMAGE})
    public void testPair(String image) throws Exception {
        try (Network network = Network.newNetwork();
             GenericContainer<?> master = createContainerByImage(image).withNetwork(network).withNetworkAliases("master");
             GenericContainer<?> slave = createContainerByImage(image, "--replicaof", "master", "6379", "--replica-read-only", "no").withNetwork(network)) {
            master.start();
            slave.start();
            master.execInContainer("redis-cli", "SETRANGE", "ms", "0", "ÿ");
            master.execInContainer("redis-cli", "BITFIELD", "ms", "SET", "i64", "0", "-1");
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(slave.execInContainer("redis-cli", "GETBIT", "ms", "0").getStdout().trim()).isEqualTo("1"));
            for (ReadMode readMode : new ReadMode[] {ReadMode.SLAVE, ReadMode.MASTER_SLAVE}) {
                Config config = new Config();
                config.useMasterSlaveServers()
                        .setMasterAddress("redis://" + master.getHost() + ":" + master.getFirstMappedPort())
                        .addSlaveAddress("redis://" + slave.getHost() + ":" + slave.getFirstMappedPort())
                        .setReadMode(readMode)
                        .setRetryAttempts(0);
                fresh("pair writable-replica " + readMode, image, config);
            }
        }
    }

}
