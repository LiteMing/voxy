package me.cortex.voxy.common.stage;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.LeavesBlock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exports a Voxy storage directory for a non-interactive stage backdrop.
 *
 * <p>The source database is opened read-only. The exporter only reads level-0
 * data, then regenerates Voxy's complete mip hierarchy in a new RocksDB
 * database. This makes vertical cropping safe: no retained distant mip can
 * still contain terrain that was removed from level 0.</p>
 */
public final class VoxyStageCacheOptimizer {
    public static final int DEFAULT_COMPRESSION_LEVEL = 9;
    public static final int DEFAULT_SHELL_DEPTH = 0;
    public static final int MAX_SHELL_DEPTH = 16;
    public static final int NO_HORIZONTAL_RADIUS = -1;

    private static final ThreadLocalMemoryBuffer READ_SCRATCH = new ThreadLocalMemoryBuffer(
            SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private VoxyStageCacheOptimizer() {
    }

    public static Result crop(Path sourceStorage, Path destinationStorage, int minY) throws IOException {
        return crop(sourceStorage, destinationStorage, minY, Integer.MAX_VALUE,
                DEFAULT_COMPRESSION_LEVEL, DEFAULT_SHELL_DEPTH);
    }

    /**
     * Copies {@code [minY, maxY]} from a Voxy storage directory into a new
     * storage directory. Both values are block Y coordinates.
     */
    public static Result crop(Path sourceStorage, Path destinationStorage, int minY, int maxY,
                              int compressionLevel) throws IOException {
        return crop(sourceStorage, destinationStorage, minY, maxY, compressionLevel,
                DEFAULT_SHELL_DEPTH);
    }

    /**
     * Copies {@code [minY, maxY]} while optionally collapsing solid voxels that
     * are farther than {@code shellDepth} from exposed space. A depth of zero
     * preserves every retained block exactly.
     */
    public static Result crop(Path sourceStorage, Path destinationStorage, int minY, int maxY,
                              int compressionLevel, int shellDepth) throws IOException {
        return crop(sourceStorage, destinationStorage, minY, maxY, compressionLevel, shellDepth,
                0, 0, NO_HORIZONTAL_RADIUS);
    }

    /**
     * Copies {@code [minY, maxY]} and, when {@code horizontalRadius} is positive,
     * only blocks within that horizontal radius of the source anchor. A negative
     * radius leaves the horizontal range unrestricted.
     */
    public static Result crop(Path sourceStorage, Path destinationStorage, int minY, int maxY,
                              int compressionLevel, int shellDepth, int anchorX, int anchorZ,
                              int horizontalRadius) throws IOException {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not be greater than maxY");
        }
        if (compressionLevel < -5 || compressionLevel > 22) {
            throw new IllegalArgumentException("Zstd compression level must be between -5 and 22");
        }
        if (shellDepth < 0 || shellDepth > MAX_SHELL_DEPTH) {
            throw new IllegalArgumentException("Shell depth must be between 0 and " + MAX_SHELL_DEPTH);
        }
        if (horizontalRadius == 0 || horizontalRadius < NO_HORIZONTAL_RADIUS) {
            throw new IllegalArgumentException("Horizontal radius must be positive or -1 for unlimited");
        }

        Path source = sourceStorage.toAbsolutePath().normalize();
        Path destination = destinationStorage.toAbsolutePath().normalize();
        validateSource(source);
        validateDestination(source, destination);

        Path staging = destination.resolveSibling("." + destination.getFileName()
                + ".dynamicstage-optimize-" + UUID.randomUUID()).normalize();
        if (!staging.getParent().equals(destination.getParent())) {
            throw new IOException("Unsafe optimizer staging path");
        }

        Counter counter = new Counter();
        HorizontalClip clip = new HorizontalClip(anchorX, anchorZ, horizontalRadius);
        try {
            Files.createDirectories(staging);
            optimize(source, staging, minY, maxY, compressionLevel, shellDepth, clip, counter);
            compactForExport(staging);
            moveIntoPlace(staging, destination);
            return new Result(source, destination, minY, maxY, counter.sourceSections,
                    counter.outputSections, counter.sourceNonAirBlocks, counter.retainedNonAirBlocks,
                    counter.removedNonAirBlocks, counter.collapsedNonAirBlocks, shellDepth,
                    counter.radiusRemovedNonAirBlocks, clip.anchorX, clip.anchorZ, clip.radius,
                    directoryBytes(destination));
        } catch (Throwable error) {
            deleteTree(staging);
            if (error instanceof IOException io) {
                throw io;
            }
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Could not optimize Voxy stage cache", error);
        }
    }

    private static void optimize(Path sourcePath, Path destinationPath, int minY, int maxY,
                                 int compressionLevel, int shellDepth, HorizontalClip clip,
                                 Counter counter) {
        StorageBackend source = openStorage(sourcePath, true, 1);
        SectionSerializationStorage destination = new SectionSerializationStorage(
                openStorage(destinationPath, false, compressionLevel));
        WorldEngine output = null;
        try {
            copyMappings(source.getIdMappingsData(), destination);
            output = new WorldEngine(destination);
            WorldEngine engine = output;
            engine.setSaveCallback((ignored, section, nonBlocking, sectionAlreadyAcquired) -> {
                section.setNotDirty();
                engine.storage.saveSection(section);
                return false;
            });

            LevelZeroReader reader = new LevelZeroReader(source);
            source.iteratePositions(0, key -> copyLevelZeroSection(reader, key, engine,
                    minY, maxY, shellDepth, clip, counter));
        } finally {
            if (output != null) {
                output.free();
            } else {
                destination.close();
            }
            source.close();
        }
    }

    private static StorageBackend openStorage(Path path, boolean readOnly, int compressionLevel) {
        return new CompressionStorageAdaptor(new ZSTDCompressor(compressionLevel),
                new RocksDBStorageBackend(path.toString(), readOnly));
    }

    private static void compactForExport(Path path) {
        RocksDBStorageBackend storage = new RocksDBStorageBackend(path.toString(), false);
        try {
            storage.compactForExport();
        } finally {
            storage.close();
        }
    }

    private static void copyMappings(Int2ObjectOpenHashMap<byte[]> mappings,
                                     SectionSerializationStorage destination) {
        for (var entry : mappings.int2ObjectEntrySet()) {
            destination.putIdMapping(entry.getIntKey(), ByteBuffer.wrap(entry.getValue()));
        }
    }

    private static void copyLevelZeroSection(LevelZeroReader reader, long key, WorldEngine output,
                                             int minY, int maxY, int shellDepth, HorizontalClip clip,
                                             Counter counter) {
        WorldSection section = reader.get(key);
        if (section == null) {
            throw new IllegalStateException("Could not read Voxy level-0 section "
                    + WorldEngine.pprintPos(key));
        }

        counter.sourceSections++;
        long[] sourceBlocks = section._unsafeGetRawDataArray();
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    VoxelizedSection voxelized = VoxelizedSection.createEmpty().setPosition(
                            section.x * 2 + sx, section.y * 2 + sy, section.z * 2 + sz);
                    copyVoxelizedSection(sourceBlocks, section.x, section.y, section.z,
                            sx, sy, sz, voxelized, minY, maxY, clip, counter);
                    if (shellDepth > 0) {
                        collapseSolidInterior(reader, voxelized, output.getMapper(), minY, maxY,
                                shellDepth, clip, counter);
                    }
                    if (voxelized.lvl0NonAirCount == 0) {
                        continue;
                    }
                    WorldVoxilizedSectionMipper.mipSection(voxelized, output.getMapper());
                    WorldUpdater.insertUpdate(output, voxelized);
                    counter.outputSections++;
                }
            }
        }
    }

    private static void copyVoxelizedSection(long[] source, int sectionX, int sectionY, int sectionZ,
                                             int subX, int subY, int subZ, VoxelizedSection destination,
                                             int minY, int maxY, HorizontalClip clip, Counter counter) {
        int originX = subX * 16;
        int originY = subY * 16;
        int originZ = subZ * 16;
        for (int y = 0; y < 16; y++) {
            int absoluteY = sectionY * 32 + originY + y;
            boolean keepY = absoluteY >= minY && absoluteY <= maxY;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int sourceIndex = ((originY + y) << 10) | ((originZ + z) << 5) | (originX + x);
                    int destinationIndex = (y << 8) | (z << 4) | x;
                    long state = source[sourceIndex];
                    if (!Mapper.isAir(state)) {
                        counter.sourceNonAirBlocks++;
                    }
                    boolean keepHorizontal = clip.contains(sectionX * 32 + originX + x,
                            sectionZ * 32 + originZ + z);
                    if ((!keepY || !keepHorizontal) && !Mapper.isAir(state)) {
                        state = Mapper.airWithLight(Mapper.getLightId(state));
                        counter.removedNonAirBlocks++;
                        if (keepY && !keepHorizontal) {
                            counter.radiusRemovedNonAirBlocks++;
                        }
                    }
                    if (!Mapper.isAir(state)) {
                        destination.lvl0NonAirCount++;
                        counter.retainedNonAirBlocks++;
                    }
                    destination.section[destinationIndex] = state;
                }
            }
        }
    }

    private static void collapseSolidInterior(LevelZeroReader source, VoxelizedSection section,
                                              Mapper mapper, int minY, int maxY, int shellDepth,
                                              HorizontalClip clip, Counter counter) {
        int size = 16 + shellDepth * 2;
        int volume = size * size * size;
        boolean[] solid = new boolean[volume];
        byte[] distances = new byte[volume];
        Arrays.fill(distances, (byte) -1);
        int[] queue = new int[volume];
        int head = 0;
        int tail = 0;

        int minX = section.x * 16 - shellDepth;
        int minSectionY = section.y * 16 - shellDepth;
        int minZ = section.z * 16 - shellDepth;
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int index = gridIndex(size, x, y, z);
                    long state = source.stateAt(minX + x, minSectionY + y, minZ + z,
                            minY, maxY, clip);
                    solid[index] = isSolidInteriorCandidate(state, mapper);
                    if (!solid[index]) {
                        distances[index] = 0;
                        queue[tail++] = index;
                    }
                }
            }
        }

        while (head < tail) {
            int index = queue[head++];
            int distance = Byte.toUnsignedInt(distances[index]);
            if (distance >= shellDepth) {
                continue;
            }
            int x = index % size;
            int yz = index / size;
            int z = yz % size;
            int y = yz / size;
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x - 1, y, z, distance);
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x + 1, y, z, distance);
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x, y - 1, z, distance);
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x, y + 1, z, distance);
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x, y, z - 1, distance);
            tail = queueSolidNeighbor(size, solid, distances, queue, tail, x, y, z + 1, distance);
        }

        Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        long representative = Mapper.AIR;
        int representativeCount = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int grid = gridIndex(size, x + shellDepth, y + shellDepth, z + shellDepth);
                    if (!solid[grid] || distances[grid] != -1) {
                        continue;
                    }
                    long state = section.section[(y << 8) | (z << 4) | x];
                    int count = counts.addTo(state, 1) + 1;
                    if (count > representativeCount) {
                        representative = state;
                        representativeCount = count;
                    }
                }
            }
        }
        if (representativeCount == 0) {
            return;
        }

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int grid = gridIndex(size, x + shellDepth, y + shellDepth, z + shellDepth);
                    int index = (y << 8) | (z << 4) | x;
                    if (solid[grid] && distances[grid] == -1
                            && section.section[index] != representative) {
                        section.section[index] = representative;
                        counter.collapsedNonAirBlocks++;
                    }
                }
            }
        }
    }

    private static int queueSolidNeighbor(int size, boolean[] solid, byte[] distances, int[] queue,
                                          int tail, int x, int y, int z, int distance) {
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) {
            return tail;
        }
        int index = gridIndex(size, x, y, z);
        if (!solid[index] || distances[index] != -1) {
            return tail;
        }
        distances[index] = (byte) (distance + 1);
        queue[tail] = index;
        return tail + 1;
    }

    private static int gridIndex(int size, int x, int y, int z) {
        return (y * size + z) * size + x;
    }

    private static boolean isSolidInteriorCandidate(long state, Mapper mapper) {
        if (Mapper.isAir(state) || mapper.getBlockStateOpacity(state) < 15) {
            return false;
        }
        var blockState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(state));
        return blockState.getFluidState().isEmpty() && !(blockState.getBlock() instanceof LeavesBlock);
    }

    /** Small read-through cache for the level-0 neighborhood needed by shell distance checks. */
    private static final class LevelZeroReader {
        private static final int CACHE_SIZE = 96;

        private final StorageBackend storage;
        private final Map<Long, WorldSection> sections = new LinkedHashMap<>(CACHE_SIZE, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, WorldSection> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        private final Set<Long> absent = new HashSet<>();

        private LevelZeroReader(StorageBackend storage) {
            this.storage = storage;
        }

        private WorldSection get(long key) {
            WorldSection cached = sections.get(key);
            if (cached != null) {
                return cached;
            }
            if (absent.contains(key)) {
                return null;
            }
            WorldSection section = WorldSection._createRawUntrackedUnsafeSection(
                    WorldEngine.getLevel(key), WorldEngine.getX(key), WorldEngine.getY(key),
                    WorldEngine.getZ(key));
            MemoryBuffer data = storage.getSectionData(key,
                    READ_SCRATCH.get().createUntrackedUnfreeableReference());
            if (data == null) {
                absent.add(key);
                return null;
            }
            if (!SaveLoadSystem3.deserialize(section, data)) {
                throw new IllegalStateException("Could not decode Voxy level-0 section "
                        + WorldEngine.pprintPos(key));
            }
            sections.put(key, section);
            return section;
        }

        private long stateAt(int blockX, int blockY, int blockZ, int minY, int maxY,
                             HorizontalClip clip) {
            if (blockY < minY || blockY > maxY || !clip.contains(blockX, blockZ)) {
                return Mapper.AIR;
            }
            int sectionX = Math.floorDiv(blockX, 32);
            int sectionY = Math.floorDiv(blockY, 32);
            int sectionZ = Math.floorDiv(blockZ, 32);
            long key = WorldEngine.getWorldSectionId(0, sectionX, sectionY, sectionZ);
            WorldSection section = get(key);
            if (section == null) {
                return Mapper.AIR;
            }
            int x = Math.floorMod(blockX, 32);
            int y = Math.floorMod(blockY, 32);
            int z = Math.floorMod(blockZ, 32);
            return section._unsafeGetRawDataArray()[(y << 10) | (z << 5) | x];
        }
    }

    private record HorizontalClip(int anchorX, int anchorZ, int radius) {
        private boolean contains(int blockX, int blockZ) {
            if (radius == NO_HORIZONTAL_RADIUS) {
                return true;
            }
            long deltaX = (long) blockX - anchorX;
            long deltaZ = (long) blockZ - anchorZ;
            long radiusSquared = (long) radius * radius;
            return deltaX * deltaX + deltaZ * deltaZ <= radiusSquared;
        }
    }

    private static void validateSource(Path source) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source is not a Voxy RocksDB storage directory: " + source);
        }
        try (var files = Files.list(source)) {
            if (files.noneMatch(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.getFileName().toString().startsWith("MANIFEST-"))) {
                throw new IOException("Source does not contain a Voxy RocksDB manifest: " + source);
            }
        }
    }

    private static void validateDestination(Path source, Path destination) throws IOException {
        if (source.equals(destination)) {
            throw new IOException("Optimizer output must not replace the source cache");
        }
        if (destination.getParent() == null || destination.getFileName() == null) {
            throw new IOException("Optimizer output path is invalid");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Optimizer output already exists: " + destination);
        }
        Files.createDirectories(destination.getParent());
    }

    private static void moveIntoPlace(Path staging, Path destination) throws IOException {
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, destination);
        }
    }

    private static long directoryBytes(Path root) throws IOException {
        final long[] bytes = new long[1];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                bytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error)
                        throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    public record Result(Path sourceStorage, Path destinationStorage, int minY, int maxY,
                         long sourceLevelZeroSections, long outputVoxelizedSections,
                         long sourceNonAirBlocks, long retainedNonAirBlocks,
                         long removedNonAirBlocks, long collapsedNonAirBlocks,
                         int shellDepth, long radiusRemovedNonAirBlocks,
                         int anchorX, int anchorZ, int horizontalRadius, long outputBytes) {
    }

    private static final class Counter {
        private long sourceSections;
        private long outputSections;
        private long sourceNonAirBlocks;
        private long retainedNonAirBlocks;
        private long removedNonAirBlocks;
        private long collapsedNonAirBlocks;
        private long radiusRemovedNonAirBlocks;
    }
}
