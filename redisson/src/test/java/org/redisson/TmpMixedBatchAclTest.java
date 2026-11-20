package org.redisson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.bitset.BitFieldArgs;
import org.redisson.api.bitset.BitOffset;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import static org.assertj.core.api.Assertions.assertThat;

// Temporary check without assertions. A user that may not run write commands sends a batch
// that mixes a bit field read with a command that goes through the write path but isn't a write command.
public class TmpMixedBatchAclTest extends RedisDockerTest {

    private static final String OUT = System.getProperty("tmp.out", "target/TmpMixedBatchAclTest.txt");

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
        return "FAIL(" + (m.length() > 60 ? m.substring(0, 60) : m) + ")";
    }

    private String run(RedissonClient client, ExecutionMode mode, String companion, String call) {
        try {
            Object expected = call.equals("getLong") ? (Object) (-1L) : Collections.singletonList(255L);
            RBatch b = client.createBatch(BatchOptions.defaults().executionMode(mode));
            if (companion.equals("publish")) {
                b.getTopic("tmpTopic", StringCodec.INSTANCE).publishAsync("m");
            } else if (companion.equals("eval")) {
                b.getScript(StringCodec.INSTANCE).evalAsync("ms", RScript.Mode.READ_WRITE, "return 1",
                        RScript.ReturnType.LONG, Collections.emptyList());
            } else if (companion.equals("set")) {
                b.getBucket("tmpBucket", StringCodec.INSTANCE).setAsync("v");
            }
            RFuture<?> f = call.equals("getLong") ? b.getBitSet("ms").getLongAsync(0)
                    : b.getBitSet("ms").bitFieldAsync(BitFieldArgs.create().getUnsigned(8, BitOffset.bit(0)));
            b.executeAsync().toCompletableFuture().get(6, TimeUnit.SECONDS);
            Object v = f.toCompletableFuture().get(5, TimeUnit.SECONDS);
            return expected.equals(v) ? "OK" : "WRONG " + v;
        } catch (Throwable t) {
            return root(t);
        }
    }

    private static String cli(GenericContainer<?> node, String... args) throws Exception {
        String[] cmd = new String[args.length + 6];
        cmd[0] = "redis-cli";
        cmd[1] = "--user";
        cmd[2] = "reader";
        cmd[3] = "--pass";
        cmd[4] = "secret";
        cmd[5] = "--no-auth-warning";
        System.arraycopy(args, 0, cmd, 6, args.length);
        return node.execInContainer(cmd).getStdout().trim().split("\n")[0];
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
            emit("as reader on master: BITFIELD -> " + cli(master, "BITFIELD", "ms", "GET", "u8", "0")
                    + " | BITFIELD_RO -> " + cli(master, "BITFIELD_RO", "ms", "GET", "u8", "0")
                    + " | PUBLISH -> " + cli(master, "PUBLISH", "tmpTopic", "m")
                    + " | EVAL -> " + cli(master, "EVAL", "return 1", "1", "ms")
                    + " | SET -> " + cli(master, "SET", "tmpBucket", "v"));

            ExecutionMode[] modes = {ExecutionMode.IN_MEMORY, ExecutionMode.IN_MEMORY_ATOMIC,
                    ExecutionMode.REDIS_WRITE_ATOMIC};
            for (ReadMode readMode : new ReadMode[] {ReadMode.SLAVE, ReadMode.MASTER_SLAVE}) {
                for (String state : new String[] {"fresh", "known"}) {
                    for (ExecutionMode mode : modes) {
                        for (String companion : new String[] {"none", "publish", "eval", "set"}) {
                            for (String call : new String[] {"bitField", "getLong"}) {
                                Config config = new Config();
                                config.setUsername("reader").setPassword("secret");
                                config.useMasterSlaveServers()
                                        .setMasterAddress("redis://" + master.getHost() + ":" + master.getFirstMappedPort())
                                        .addSlaveAddress("redis://" + slave.getHost() + ":" + slave.getFirstMappedPort())
                                        .setReadMode(readMode)
                                        .setRetryAttempts(0);
                                RedissonClient client = Redisson.create(config);
                                try {
                                    if (state.equals("known")) {
                                        // a read outside of a batch makes BITFIELD_RO known to be available
                                        client.getBitSet("ms").getLong(0);
                                    }
                                    String r = run(client, mode, companion, call) + ", " + run(client, mode, companion, call);
                                    emit(String.format("%-12s | %-5s | %-18s | %-7s | %-8s -> %s",
                                            readMode, state, mode, companion, call, r));
                                } finally {
                                    client.shutdown();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
