package org.redisson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
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

// a user that may run everything except write commands: BITFIELD_RO is allowed, BITFIELD is not
public class TmpAclReaderTest extends RedisDockerTest {

    private static final String OUT = System.getProperty("tmp.out", "target/TmpAclReaderTest.txt");

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
        return "FAIL(" + (m.length() > 22 ? m.substring(0, 22) : m) + ")";
    }

    private String run(RedissonClient client, ExecutionMode mode, String call) {
        try {
            Object expected = call.equals("getLong") ? (Object) (-1L) : java.util.Collections.singletonList(255L);
            RFuture<?> f;
            if (mode == null) {
                f = call.equals("getLong") ? client.getBitSet("ms").getLongAsync(0)
                        : client.getBitSet("ms").bitFieldAsync(BitFieldArgs.create().getUnsigned(8, BitOffset.bit(0)));
            } else {
                RBatch b = client.createBatch(BatchOptions.defaults().executionMode(mode));
                f = call.equals("getLong") ? b.getBitSet("ms").getLongAsync(0)
                        : b.getBitSet("ms").bitFieldAsync(BitFieldArgs.create().getUnsigned(8, BitOffset.bit(0)));
                b.executeAsync().toCompletableFuture().get(6, TimeUnit.SECONDS);
            }
            Object v = f.toCompletableFuture().get(5, TimeUnit.SECONDS);
            return expected.equals(v) ? "OK" : "WRONG " + v;
        } catch (Throwable t) {
            return root(t);
        }
    }

    @Test
    public void test() throws Exception {
        try (Network network = Network.newNetwork();
             GenericContainer<?> master = createContainer().withNetwork(network).withNetworkAliases("master");
             GenericContainer<?> slave = createContainer("--replicaof", "master", "6379").withNetwork(network)) {
            master.start();
            slave.start();
            master.execInContainer("redis-cli", "BITFIELD", "ms", "SET", "i64", "0", "-1");
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(slave.execInContainer("redis-cli", "GETBIT", "ms", "0").getStdout().trim()).isEqualTo("1"));
            for (GenericContainer<?> node : new GenericContainer<?>[] {master, slave}) {
                String r = node.execInContainer("redis-cli", "ACL", "SETUSER", "reader", "on", ">secret", "~*", "&*",
                        "+@all", "-@write").getStdout().trim();
                assertThat(r).isEqualTo("OK");
            }
            emit("check on master as reader: BITFIELD -> " + master.execInContainer("redis-cli", "--user", "reader", "--pass", "secret",
                    "--no-auth-warning", "BITFIELD", "ms", "GET", "u8", "0").getStdout().trim().split("\n")[0]
                    + " | BITFIELD_RO -> " + master.execInContainer("redis-cli", "--user", "reader", "--pass", "secret",
                    "--no-auth-warning", "BITFIELD_RO", "ms", "GET", "u8", "0").getStdout().trim().split("\n")[0]);

            ExecutionMode[] modes = {null, ExecutionMode.IN_MEMORY, ExecutionMode.IN_MEMORY_ATOMIC,
                    ExecutionMode.REDIS_READ_ATOMIC, ExecutionMode.REDIS_WRITE_ATOMIC};
            for (ReadMode readMode : new ReadMode[] {ReadMode.SLAVE, ReadMode.MASTER_SLAVE}) {
                for (ExecutionMode mode : modes) {
                    for (String call : new String[] {"getLong", "bitField"}) {
                        Config config = new Config();
                        config.setUsername("reader").setPassword("secret");
                        config.useMasterSlaveServers()
                                .setMasterAddress("redis://" + master.getHost() + ":" + master.getFirstMappedPort())
                                .addSlaveAddress("redis://" + slave.getHost() + ":" + slave.getFirstMappedPort())
                                .setReadMode(readMode)
                                .setRetryAttempts(0);
                        RedissonClient client = Redisson.create(config);
                        try {
                            String r = run(client, mode, call) + ", " + run(client, mode, call) + ", " + run(client, mode, call);
                            emit(String.format("%-12s | %-17s | %-8s -> %s", readMode, mode == null ? "no batch" : mode, call, r));
                        } finally {
                            client.shutdown();
                        }
                    }
                }
            }
        }
    }
}
