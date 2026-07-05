package org.ywzj.rvp.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.ywzj.rvp.RVP_MOD;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RVP_TacticalMapCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int TILE_SIZE = 256;
    private static final int TILE_SIZE_BITS = 8;
    private static final String CACHE_ROOT = "ywzj_rvp_tactical_map";
    private static final Pattern TILE_FILE_PATTERN = Pattern.compile("tile_(-?\\d+)_(-?\\d+)\\.png");

    private static final Queue<LevelChunk> PENDING_CHUNKS = new ArrayDeque<>();
    private static final Map<TilePos, NativeImage> TILE_IMAGES = new HashMap<>();
    private static final Map<TilePos, DynamicTexture> TILE_TEXTURES = new HashMap<>();
    private static final Map<Long, short[]> CHUNK_HEIGHTS = new HashMap<>();
    private static final Set<TilePos> DIRTY_TILES = new HashSet<>();
    private static final Set<TilePos> DIRTY_TILES_TO_PERSIST = new HashSet<>();
    private static String currentWorldKey;
    private static ResourceLocation currentDimension;
    private static Path currentCacheDirectory;

    private RVP_TacticalMapCache() {}

    public static void setWorldContext(Level level) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }
        String nextWorldKey = resolveWorldKey();
        ResourceLocation nextDimension = clientLevel.dimension().location();
        if (Objects.equals(currentWorldKey, nextWorldKey) && Objects.equals(currentDimension, nextDimension)) {
            return;
        }
        clear();
        currentWorldKey = nextWorldKey;
        currentDimension = nextDimension;
        currentCacheDirectory = resolveCacheDirectory(nextWorldKey, nextDimension);
        loadPersistedState();
    }

    public static void queueChunkUpdate(LevelChunk chunk) {
        if (chunk == null) {
            return;
        }
        setWorldContext(chunk.getLevel());
        PENDING_CHUNKS.offer(chunk);
    }

    public static void processChunkUpdates(Level level, double focusX, double focusZ, int maxCount) {
        if (level == null || maxCount <= 0 || PENDING_CHUNKS.isEmpty()) {
            return;
        }
        setWorldContext(level);

        List<LevelChunk> chunks = new ArrayList<>(PENDING_CHUNKS);
        PENDING_CHUNKS.clear();
        chunks.sort(Comparator.comparingDouble(chunk -> chunkDistanceSq(chunk, focusX, focusZ)));

        int processed = 0;
        for (LevelChunk chunk : chunks) {
            if (processed < maxCount && chunk.getLevel() == level) {
                sampleChunk(level, chunk);
                processed++;
            } else {
                PENDING_CHUNKS.offer(chunk);
            }
        }
    }

    public static void uploadDirtyTextures() {
        if (DIRTY_TILES.isEmpty()) {
            return;
        }
        for (TilePos tilePos : List.copyOf(DIRTY_TILES)) {
            DynamicTexture texture = TILE_TEXTURES.get(tilePos);
            if (texture != null) {
                texture.upload();
            }
        }
        DIRTY_TILES.clear();
    }

    public static List<TilePos> getVisibleTiles(double minWorldX, double minWorldZ, double maxWorldX, double maxWorldZ) {
        int minTileX = Mth.floor(minWorldX) >> TILE_SIZE_BITS;
        int maxTileX = Mth.floor(maxWorldX) >> TILE_SIZE_BITS;
        int minTileZ = Mth.floor(minWorldZ) >> TILE_SIZE_BITS;
        int maxTileZ = Mth.floor(maxWorldZ) >> TILE_SIZE_BITS;

        List<TilePos> result = new ArrayList<>();
        for (int rz = minTileZ; rz <= maxTileZ; rz++) {
            for (int rx = minTileX; rx <= maxTileX; rx++) {
                TilePos pos = new TilePos(rx, rz);
                if (TILE_IMAGES.containsKey(pos)) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    public static ResourceLocation getTileTexture(TilePos tilePos) {
        if (!TILE_IMAGES.containsKey(tilePos)) {
            return null;
        }

        TILE_TEXTURES.computeIfAbsent(tilePos, pos -> {
            Minecraft mc = Minecraft.getInstance();
            ResourceLocation loc = tileLocation(pos);
            try {
                mc.getTextureManager().release(loc);
            } catch (Exception ignored) {
            }
            DynamicTexture texture = new DynamicTexture(Objects.requireNonNull(TILE_IMAGES.get(pos)));
            mc.getTextureManager().register(loc, texture);
            return texture;
        });
        return tileLocation(tilePos);
    }

    public static void clear() {
        persistToDisk();
        PENDING_CHUNKS.clear();
        DIRTY_TILES.clear();
        DIRTY_TILES_TO_PERSIST.clear();
        CHUNK_HEIGHTS.clear();
        TILE_IMAGES.values().forEach(NativeImage::close);
        TILE_IMAGES.clear();

        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<TilePos, DynamicTexture> entry : TILE_TEXTURES.entrySet()) {
            try {
                mc.getTextureManager().release(tileLocation(entry.getKey()));
            } catch (Exception ignored) {
            }
            entry.getValue().close();
        }
        TILE_TEXTURES.clear();
        currentWorldKey = null;
        currentDimension = null;
        currentCacheDirectory = null;
    }

    private static void persistToDisk() {
        if (currentCacheDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(tileDirectory());
            for (TilePos tilePos : List.copyOf(DIRTY_TILES_TO_PERSIST)) {
                NativeImage image = TILE_IMAGES.get(tilePos);
                if (image != null) {
                    image.writeToFile(tilePath(tilePos));
                }
            }
            writeChunkHeights();
            DIRTY_TILES_TO_PERSIST.clear();
        } catch (IOException exception) {
            LOGGER.warn("[RVP][TacticalMap] Failed to persist cache to {}", currentCacheDirectory, exception);
        }
    }

    private static void loadPersistedState() {
        if (currentCacheDirectory == null || !Files.isDirectory(currentCacheDirectory)) {
            return;
        }
        loadChunkHeights();
        Path tileDir = tileDirectory();
        if (!Files.isDirectory(tileDir)) {
            return;
        }
        try (var files = Files.list(tileDir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                Matcher matcher = TILE_FILE_PATTERN.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    return;
                }
                try (InputStream inputStream = Files.newInputStream(path)) {
                    TilePos tilePos = new TilePos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                    NativeImage image = NativeImage.read(inputStream);
                    NativeImage old = TILE_IMAGES.put(tilePos, image);
                    if (old != null) {
                        old.close();
                    }
                } catch (IOException exception) {
                    LOGGER.warn("[RVP][TacticalMap] Failed to load tile {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("[RVP][TacticalMap] Failed to scan cache directory {}", tileDir, exception);
        }
    }

    private static void writeChunkHeights() throws IOException {
        Path file = chunkHeightsPath();
        Files.createDirectories(file.getParent());
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(CHUNK_HEIGHTS.size());
            for (Map.Entry<Long, short[]> entry : CHUNK_HEIGHTS.entrySet()) {
                output.writeLong(entry.getKey());
                short[] heights = entry.getValue();
                output.writeInt(heights.length);
                for (short height : heights) {
                    output.writeShort(height);
                }
            }
        }
    }

    private static void loadChunkHeights() {
        Path file = chunkHeightsPath();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                long key = input.readLong();
                int length = input.readInt();
                short[] heights = new short[length];
                for (int idx = 0; idx < length; idx++) {
                    heights[idx] = input.readShort();
                }
                CHUNK_HEIGHTS.put(key, heights);
            }
        } catch (IOException exception) {
            LOGGER.warn("[RVP][TacticalMap] Failed to load chunk heights from {}", file, exception);
        }
    }

    private static Path tileDirectory() {
        return currentCacheDirectory.resolve("tiles");
    }

    private static Path tilePath(TilePos tilePos) {
        return tileDirectory().resolve("tile_" + tilePos.rx + "_" + tilePos.rz + ".png");
    }

    private static Path chunkHeightsPath() {
        return currentCacheDirectory.resolve("chunk_heights.bin");
    }

    private static Path resolveCacheDirectory(String worldKey, ResourceLocation dimension) {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("cache")
                .resolve(CACHE_ROOT)
                .resolve(worldKey)
                .resolve(sanitizeKey(dimension.toString()));
    }

    private static String resolveWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            return "sp_" + sanitizeKey(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        ServerData currentServer = mc.getCurrentServer();
        if (currentServer != null && currentServer.ip != null && !currentServer.ip.isBlank()) {
            return "mp_" + sanitizeKey(currentServer.ip);
        }
        return "unknown";
    }

    private static String sanitizeKey(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static void sampleChunk(Level level, LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        short[] heights = new short[16 * 16];

        for (int z = 0; z < 16; z++) {
            int prevHeight = 0;
            boolean prevSet = false;
            for (int x = 0; x < 16; x++) {
                int worldX = minX + x;
                int worldZ = minZ + z;

                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                heights[z * 16 + x] = (short) surfaceY;
                int y = surfaceY;
                if (y < level.getMinBuildHeight()) {
                    prevHeight = level.getMinBuildHeight();
                    prevSet = true;
                    continue;
                }

                BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(worldX, y, worldZ);
                var state = chunk.getBlockState(mutablePos);

                while (y > level.getMinBuildHeight() && state.getMapColor(level, mutablePos) == MapColor.NONE) {
                    y--;
                    mutablePos.setY(y);
                    state = chunk.getBlockState(mutablePos);
                }

                MapColor mapColor = state.getMapColor(level, mutablePos);
                if (mapColor == MapColor.NONE) {
                    prevHeight = y;
                    prevSet = true;
                    continue;
                }

                MapColor actualColor = mapColor;
                int actualY = y;
                if (!state.getFluidState().isEmpty()) {
                    actualColor = state.getFluidState().is(FluidTags.LAVA) ? MapColor.COLOR_ORANGE : MapColor.WATER;
                    actualY = surfaceY;
                }

                MapColor.Brightness brightness = !prevSet
                        ? MapColor.Brightness.NORMAL
                        : computeBrightness(actualY, prevHeight, worldX, worldZ);

                prevHeight = actualY;
                prevSet = true;

                if (actualColor != MapColor.NONE) {
                    int abgr = calculateAbgr(actualColor, brightness);
                    TilePos tilePos = new TilePos(worldX >> TILE_SIZE_BITS, worldZ >> TILE_SIZE_BITS);
                    int localX = worldX & (TILE_SIZE - 1);
                    int localZ = worldZ & (TILE_SIZE - 1);
                    getOrCreateTile(tilePos).setPixelRGBA(localX, localZ, abgr);
                    DIRTY_TILES.add(tilePos);
                    DIRTY_TILES_TO_PERSIST.add(tilePos);
                }
            }
        }
        CHUNK_HEIGHTS.put(chunkKey(chunk.getPos().x, chunk.getPos().z), heights);
    }

    public static Integer getCachedHeight(int worldX, int worldZ) {
        short[] heights = CHUNK_HEIGHTS.get(chunkKey(worldX >> 4, worldZ >> 4));
        if (heights == null) {
            return null;
        }
        return (int) heights[(worldZ & 15) * 16 + (worldX & 15)];
    }

    private static NativeImage getOrCreateTile(TilePos tilePos) {
        return TILE_IMAGES.computeIfAbsent(tilePos, pos -> new NativeImage(TILE_SIZE, TILE_SIZE, true));
    }

    private static double chunkDistanceSq(LevelChunk chunk, double focusX, double focusZ) {
        double dx = chunk.getPos().getMiddleBlockX() - focusX;
        double dz = chunk.getPos().getMiddleBlockZ() - focusZ;
        return dx * dx + dz * dz;
    }

    private static MapColor.Brightness computeBrightness(int currentY, int prevY, int worldX, int worldZ) {
        double d = (currentY - prevY) * 0.8 + (((worldX + worldZ) & 1) - 0.5) * 0.4;
        if (d > 0.6) {
            return MapColor.Brightness.HIGH;
        }
        if (d < -0.6) {
            return MapColor.Brightness.LOW;
        }
        return MapColor.Brightness.NORMAL;
    }

    private static int calculateAbgr(MapColor mapColor, MapColor.Brightness brightness) {
        if (mapColor == MapColor.NONE) {
            return 0;
        }
        int col = mapColor.col;
        int modifier = switch (brightness) {
            case LOW -> 120;
            case NORMAL -> 148;
            case HIGH -> 176;
            default -> 148;
        };
        int rawR = ((col >> 16) & 0xFF) * modifier / 255;
        int rawG = ((col >> 8) & 0xFF) * modifier / 255;
        int rawB = (col & 0xFF) * modifier / 255;
        int gray = (rawR * 30 + rawG * 59 + rawB * 11) / 100;
        int r = Mth.clamp((int) (gray + (rawR - gray) * 0.42f), 0, 255);
        int g = Mth.clamp((int) (gray + (rawG - gray) * 0.42f), 0, 255);
        int b = Mth.clamp((int) (gray + (rawB - gray) * 0.42f), 0, 255);
        r = (int) (r * 0.88f);
        g = (int) (g * 0.90f);
        b = (int) (b * 0.94f);
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }

    private static ResourceLocation tileLocation(TilePos tilePos) {
        return RVP_MOD.modLocation("tactical_map/tile_" + tilePos.rx + "_" + tilePos.rz);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public record TilePos(int rx, int rz) {
    }
}
