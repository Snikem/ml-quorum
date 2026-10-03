package monoforest.runner;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class RamIndexMemoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long GIB = 1L << 30;
    private static long gib(double amount) { return (long)(amount * GIB); }
    private Path write(Path dir, String name, String value) throws Exception {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), value);
    }
    private String stats(long file, long shmem, long dirty, long writeback, long locked) {
        return "file " + file + "\nshmem " + shmem + "\nfile_dirty " + dirty
                + "\nfile_writeback " + writeback + "\nunevictable " + locked + "\n";
    }
    private Path proc(Path mount, long hostAvailable, String membership) throws Exception {
        Path root = temp.newFolder().toPath();
        write(root, "meminfo", "MemAvailable: " + hostAvailable / 1024 + " kB\n");
        write(root.resolve("self"), "cgroup", "0::" + membership + "\n");
        write(root.resolve("self"), "mountinfo", "1 0 0:1 / " + mount + " ro - cgroup2 cgroup ro\n");
        return root;
    }
    private void group(Path dir, String limit, long current, String stat) throws Exception {
        write(dir, "memory.max", limit);
        write(dir, "memory.current", Long.toString(current));
        if (stat != null) write(dir, "memory.stat", stat);
    }

    @Test public void jupyterCacheDoesNotLookLikeUnreclaimableProcessMemory() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath();
        // Reproduce the reported JH failure: old max-current estimate was only 4.9 GiB.
        long current = 120 * GIB - 5263609856L;
        String stat = stats(gib(112.96), 0, 0, 0, 0) + "anon " + gib(.35)
                + "\ninactive_file " + gib(84.23) + "\nactive_file " + gib(28.73) + "\n";
        group(mount, Long.toString(120 * GIB), current, stat);
        long available = RamIndex.Memory.availableBytes(proc(mount, 389 * GIB, "/"));
        assertEquals(5263609856L + gib(112.96), available);
        long index = 103574208932L;
        long required = index + index / 50 + 4 * GIB + 12 * GIB;
        assertTrue("96.46 GiB index + 4 GiB heap + reserve should pass this snapshot", available > required);
    }

    @Test public void tmpfsAndProcessesAreNotCreditedAsCleanCache() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath();
        group(mount, Long.toString(120 * GIB), 115 * GIB, stats(100 * GIB, 100 * GIB, 0, 0, 0));
        assertEquals(5 * GIB, RamIndex.Memory.availableBytes(proc(mount, 389 * GIB, "/")));
    }

    @Test public void excludesDirtyWritebackAndLockedPages() throws Exception {
        Path stat = write(temp.newFolder().toPath(), "memory.stat", stats(100, 30, 10, 5, 2));
        assertEquals(53, RamIndex.Memory.cleanFileCacheBytes(stat, true));
    }

    @Test public void hostAndAncestorLimitsStillApply() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath(), child = mount.resolve("child");
        group(mount, Long.toString(100 * GIB), 30 * GIB, stats(0, 0, 0, 0, 0));
        group(child, Long.toString(120 * GIB), 110 * GIB, stats(100 * GIB, 0, 0, 0, 0));
        assertEquals(70 * GIB, RamIndex.Memory.availableBytes(proc(mount, 389 * GIB, "/child")));
        assertEquals(40 * GIB, RamIndex.Memory.availableBytes(proc(mount, 40 * GIB, "/child")));
    }

    @Test public void unlimitedChildDoesNotHideParentLimit() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath(), child = mount.resolve("child");
        group(mount, Long.toString(100 * GIB), 30 * GIB, stats(0, 0, 0, 0, 0));
        group(child, "max", 20 * GIB, stats(10 * GIB, 0, 0, 0, 0));
        assertEquals(70 * GIB, RamIndex.Memory.availableBytes(proc(mount, 389 * GIB, "/child")));
    }

    @Test public void namespaceMountRootStillReadsContainerStatistics() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath();
        group(mount, Long.toString(120 * GIB), 110 * GIB, stats(100 * GIB, 0, 0, 0, 0));
        Path p = proc(mount, 389 * GIB, "/");
        write(p.resolve("self"), "mountinfo", "1 0 0:1 /kubepods/pod/container " + mount + " ro - cgroup2 cgroup ro\n");
        assertEquals(110 * GIB, RamIndex.Memory.availableBytes(p));
    }

    @Test public void missingOrMalformedStatsRetainConservativeBound() throws Exception {
        Path mount = temp.newFolder("cgroup").toPath();
        group(mount, Long.toString(120 * GIB), 115 * GIB, null);
        Path p = proc(mount, 389 * GIB, "/");
        assertEquals(5 * GIB, RamIndex.Memory.availableBytes(p));
        write(mount, "memory.stat", "file 100000\n"); // Missing shmem must not be treated as zero.
        assertEquals(5 * GIB, RamIndex.Memory.availableBytes(p));
        write(mount, "memory.stat", "file invalid\n");
        assertEquals(5 * GIB, RamIndex.Memory.availableBytes(p));
    }

    @Test public void v1UsesHierarchicalCountersNotLocalOnes() throws Exception {
        Path mount = temp.newFolder("v1").toPath();
        write(mount, "memory.limit_in_bytes", Long.toString(120 * GIB));
        write(mount, "memory.usage_in_bytes", Long.toString(115 * GIB));
        write(mount, "memory.stat", "cache 999\nshmem 0\ntotal_cache " + 110 * GIB
                + "\ntotal_shmem " + 10 * GIB + "\ntotal_dirty 0\ntotal_writeback 0\ntotal_unevictable 0\n");
        Path p = proc(mount, 389 * GIB, "/");
        write(p.resolve("self"), "cgroup", "5:memory:/\n");
        write(p.resolve("self"), "mountinfo", "1 0 0:1 / " + mount + " ro - cgroup cgroup ro,memory\n");
        assertEquals(105 * GIB, RamIndex.Memory.availableBytes(p));
    }

    @Test public void changingCountersCannotExceedLimitOrGoNegative() throws Exception {
        assertEquals(120, RamIndex.Memory.cgroupHeadroom(120, 100, 200));
        assertEquals(0, RamIndex.Memory.cgroupHeadroom(120, 150, 20));
        Path stat = write(temp.newFolder().toPath(), "memory.stat", stats(10, 20, 0, 0, 0));
        assertEquals(0, RamIndex.Memory.cleanFileCacheBytes(stat, true));
    }
}
