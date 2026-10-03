package monoforest.runner;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** An owned, temporary copy of an immutable Lucene commit on Linux tmpfs. */
public final class RamIndex implements AutoCloseable {
    public final Path path;
    public final long bytes;
    public final double copyMillis;
    private RamIndex(Path path, long bytes, double copyMillis) {
        this.path = path; this.bytes = bytes; this.copyMillis = copyMillis;
    }

    public static RamIndex load(Path source, Path ramRoot, long reserveBytes) throws IOException {
        if (!Files.isDirectory(ramRoot) || !Files.isWritable(ramRoot))
            throw new IOException("RAM root must be an existing writable tmpfs directory: " + ramRoot);
        FileStore store = Files.getFileStore(ramRoot);
        if (!"tmpfs".equals(store.type()))
            throw new IOException("RAM root is " + store.type() + ", expected tmpfs: " + ramRoot);
        return copy(source, ramRoot, reserveBytes, true);
    }

    // Package-visible so snapshot/copy/cleanup can be tested without a Linux mount.
    static RamIndex copy(Path source, Path root, long reserveBytes, boolean checkMemory) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Index directory not found: " + source);
        long started = System.nanoTime();
        try (Directory directory = FSDirectory.open(source);
             Lock lock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            // The write lock prevents commits/deletions during the snapshot copy.
            Collection<String> names = reader.getIndexCommit().getFileNames();
            long bytes = 0;
            for (String name : names) bytes = Math.addExact(bytes, Files.size(source.resolve(name)));
            long margin = Math.max(64L << 20, bytes / 50); // filesystem overhead and metadata
            if (bytes + margin > Files.getFileStore(root).getUsableSpace())
                throw new IOException("Not enough tmpfs space for index (" + bytes + " bytes plus margin)");
            if (checkMemory) {
                long available = Memory.availableBytes();
                long required = Math.addExact(bytes + margin, Runtime.getRuntime().maxMemory() + reserveBytes);
                System.err.printf(Locale.ROOT,
                        "RAM preflight: required=%.2f GiB, estimated available=%.2f GiB (host/cgroup, clean file cache included)%n",
                        required / (double)(1L << 30), available / (double)(1L << 30));
                if (required > available)
                    throw new IOException("Not enough memory: index + JVM max heap + reserve needs " + required
                            + " bytes; estimated available including reclaimable clean file cache: " + available
                            + ". Reduce -Xmx, use a smaller index, or increase the JupyterHub memory allocation.");
            }
            Path target = Files.createTempDirectory(root, "monoforest-");
            RamIndex result = new RamIndex(target, bytes, 0);
            try {
                for (String name : names) Files.copy(source.resolve(name), target.resolve(name));
                lock.ensureValid();
                try (Directory copied = FSDirectory.open(target); DirectoryReader check = DirectoryReader.open(copied)) {
                    if (check.numDocs() != reader.numDocs()) throw new IOException("Copied index document count differs");
                }
                return new RamIndex(target, bytes, (System.nanoTime() - started) / 1e6);
            } catch (IOException | RuntimeException error) {
                try { result.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (!Files.exists(path)) return;
        // Only this instance's generated directory is removed, never the source/root.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(path);
    }

    static final class Memory {
        static long availableBytes() throws IOException {
            return availableBytes(Path.of("/proc"));
        }
        static long availableBytes(Path proc) throws IOException {
            long available = -1;
            for (String line : Files.readAllLines(proc.resolve("meminfo"))) {
                if (line.startsWith("MemAvailable:")) available = Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
            }
            if (available < 0) throw new IOException("Cannot determine MemAvailable on this Linux server");
            List<String> membership = Files.readAllLines(proc.resolve("self/cgroup"));
            for (String mount : Files.readAllLines(proc.resolve("self/mountinfo"))) {
                String[] halves = mount.split(" - ", 2), fields = halves[0].split(" "), fs = halves[1].split(" ");
                boolean v2 = fs[0].equals("cgroup2");
                if (!v2 && !(fs[0].equals("cgroup") && Arrays.asList(fs[2].split(",")).contains("memory"))) continue;
                Path mountPoint = Path.of(unescape(fields[4])), root = Path.of(unescape(fields[3]));
                for (String entry : membership) {
                    String[] parts = entry.split(":", 3);
                    if (v2 ? !parts[1].isEmpty() : !Arrays.asList(parts[1].split(",")).contains("memory")) continue;
                    Path group = Path.of(parts[2]);
                    Path current = group.startsWith(root) ? mountPoint.resolve(root.relativize(group)).normalize() : mountPoint;
                    if (!current.startsWith(mountPoint) || !Files.isDirectory(current)) current = mountPoint;
                    for (Path dir = current; dir != null && dir.startsWith(mountPoint); dir = dir.getParent()) {
                        Path limit = dir.resolve(v2 ? "memory.max" : "memory.limit_in_bytes");
                        Path used = dir.resolve(v2 ? "memory.current" : "memory.usage_in_bytes");
                        if (Files.isRegularFile(limit) && Files.isRegularFile(used)) {
                            String value = Files.readString(limit).trim();
                            if (!value.equals("max")) {
                                long currentBytes = Long.parseLong(Files.readString(used).trim());
                                long cleanCache = cleanFileCacheBytes(dir.resolve("memory.stat"), v2);
                                available = Math.min(available, cgroupHeadroom(Long.parseLong(value), currentBytes, cleanCache));
                            }
                        }
                    }
                }
            }
            return available;
        }
        /** Estimate only: clean disk-backed pages can be reclaimed, unlike tmpfs/anonymous memory.
         * memory.stat file includes shmem; active_file and inactive_file are overlapping views,
         * not additional free memory. Retain host MemAvailable and every visible ancestor limit.
         */
        static long cleanFileCacheBytes(Path stat, boolean v2) {
            Map<String, Long> values = new HashMap<>();
            try {
                for (String line : Files.readAllLines(stat)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 2) values.put(parts[0], Long.parseLong(parts[1]));
                }
            } catch (IOException | NumberFormatException error) {
                return 0; // No trustworthy breakdown: keep the conservative current/max bound.
            }
            String file = v2 ? "file" : "total_cache";
            String[] exclusions = v2
                    ? new String[]{"shmem", "file_dirty", "file_writeback", "unevictable"}
                    : new String[]{"total_shmem", "total_dirty", "total_writeback", "total_unevictable"};
            if (!values.containsKey(file) || values.get(file) < 0) return 0;
            long clean = values.get(file);
            for (String key : exclusions) {
                Long bytes = values.get(key);
                if (bytes == null || bytes < 0) return 0;
                // Exclusions can overlap; subtracting all of them errs conservatively.
                clean = Math.max(0, clean - bytes);
            }
            return clean;
        }
        static long cgroupHeadroom(long limit, long used, long cleanCache) {
            if (limit < 0 || used < 0 || cleanCache < 0) return 0;
            long nonReclaimable = used - Math.min(used, cleanCache);
            return Math.max(0, limit - nonReclaimable);
        }
        private static String unescape(String value) {
            return value.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\");
        }
    }
}
