package i.mrhua269.zutils.nms.v1_21_4.impl;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.common.util.WorldUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import i.mrhua269.zutils.api.WorldManager;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.validation.ContentValidationException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.generator.CraftWorldInfo;

public class FoliaWorldManagerImpl implements WorldManager {

    //TODO Did we ACTUALLY kill the region?
    private void killAllThreadedRegionsOnce(@NotNull ServerLevel level){
        level.regioniser.computeForAllRegions(region -> {
            for (;;) {
                boolean result;

                try {
                    final Class<?> threadedRegionClass = ThreadedRegionizer.ThreadedRegion.class;
                    final Method tryKillMethod = threadedRegionClass.getDeclaredMethod("tryKill");
                    tryKillMethod.setAccessible(true);
                    result = (boolean) tryKillMethod.invoke(region);
                }catch (Exception e){
                    break;
                }

                if (result) {
                    break;
                }
            }
        });
    }

    private void saveAllChunksNoCheck(ServerLevel world, @NotNull ChunkHolderManager holderManager, final boolean flush, final boolean shutdown, final boolean logProgress, final boolean first, final boolean last) {
        io.papermc.paper.threadedregions.RegionizedServer.ensureGlobalTickThread("Saving all chunks can be done only on global tick thread");

        final List<NewChunkHolder> holders = new java.util.ArrayList<>(holderManager.getChunkHolders().size() / 10);
        // we could iterate through all chunk holders with thread checks, however for many regions the iteration cost alone
        // will multiply. to avoid this, we can simply iterate through all owned sections
        final int regionShift = world.moonrise$getRegionChunkShift();
        final int width = 1 << regionShift;
        world.regioniser.computeForAllRegions(region -> {
            for (final LongIterator iterator = region.getOwnedSectionsUnsynchronised(); iterator.hasNext();) {
                final long sectionKey = iterator.nextLong();
                final int offsetX = CoordinateUtils.getChunkX(sectionKey) << regionShift;
                final int offsetZ = CoordinateUtils.getChunkZ(sectionKey) << regionShift;

                for (int dz = 0; dz < width; ++dz) {
                    for (int dx = 0; dx < width; ++dx) {
                        final NewChunkHolder holder = holderManager.getChunkHolder(offsetX | dx, offsetZ | dz);
                        if (holder != null) {
                            holders.add(holder);
                        }
                    }
                }
            }
        });
        // Folia end - region threading

        if (first && logProgress) { // Folia - region threading
            MinecraftServer.LOGGER.info("Saving all chunkholders for world '" + WorldUtil.getWorldName(world) + "'");
        }

        final DecimalFormat format = new DecimalFormat("#0.00");

        int saved = 0;

        long start = System.nanoTime();
        long lastLog = start;
        final int flushInterval = 200;
        int lastFlush = 0;

        int savedChunk = 0;
        int savedEntity = 0;
        int savedPoi = 0;

        if (shutdown) {
            // Normal unload process does not occur during shutdown: fire event manually
            // for mods that expect ChunkEvent.Unload to fire on shutdown (before LevelEvent.Unload)
            for (int i = 0, len = holders.size(); i < len; ++i) {
                final NewChunkHolder holder = holders.get(i);
                if (holder.getCurrentChunk() instanceof LevelChunk levelChunk) {
                    PlatformHooks.get().chunkUnloadFromWorld(levelChunk);
                }
            }
        }
        for (int i = 0, len = holders.size(); i < len; ++i) {
            final NewChunkHolder holder = holders.get(i);
            try {
                final NewChunkHolder.SaveStat saveStat = holder.save(shutdown);
                if (saveStat != null) {
                    if (saveStat.savedChunk()) {
                        ++savedChunk;
                        ++saved;
                    }
                    if (saveStat.savedEntityChunk()) {
                        ++savedEntity;
                        ++saved;
                    }
                    if (saveStat.savedPoiChunk()) {
                        ++savedPoi;
                        ++saved;
                    }
                }
            } catch (final Throwable thr) {
                MinecraftServer.LOGGER.error("Failed to save chunk (" + holder.chunkX + "," + holder.chunkZ + ") in world '" + WorldUtil.getWorldName(world) + "'", thr);
            }
            if (flush && (saved - lastFlush) > (flushInterval / 2)) {
                lastFlush = saved;
                MoonriseRegionFileIO.partialFlush(world, flushInterval / 2);
            }
            if (logProgress) {
                final long currTime = System.nanoTime();
                if ((currTime - lastLog) > TimeUnit.SECONDS.toNanos(10L)) {
                    lastLog = currTime;
                    MinecraftServer.LOGGER.info(
                            "Saved " + savedChunk + " block chunks, " + savedEntity + " entity chunks, " + savedPoi
                                    + " poi chunks in world '" + WorldUtil.getWorldName(world) + "', progress: "
                                    + format.format((double)(i+1)/(double)len * 100.0)
                    );
                }
            }
        }
        if (last && flush) { // Folia - region threading
            MoonriseRegionFileIO.flush(world);
            try {
                MoonriseRegionFileIO.flushRegionStorages(world);
            } catch (final IOException ex) {
                MinecraftServer.LOGGER.error("Exception when flushing regions in world '" + WorldUtil.getWorldName(world) + "'", ex);
            }
        }
        if (logProgress) {
            MinecraftServer.LOGGER.info(
                    "Saved " + savedChunk + " block chunks, " + savedEntity + " entity chunks, " + savedPoi
                            + " poi chunks in world '" + WorldUtil.getWorldName(world) + "' in "
                            + format.format(1.0E-9 * (System.nanoTime() - start)) + "s"
            );
        }
    }

    public void save(@NotNull ServerLevel level, boolean flush, boolean savingDisabled) {
        // Paper start - rewrite chunk system - add close param
        this.save(level, flush, savingDisabled, false);
    }

    public void save(@NotNull ServerLevel level, boolean flush, boolean savingDisabled, boolean close) {
        if (!savingDisabled) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(level.getWorld())); // CraftBukkit

            level.saveLevelData(!close);

            if (!close) this.saveAllChunksNoCheck(level, level.moonrise$getChunkTaskScheduler().chunkHolderManager, flush, false, false,true,true); // Paper - rewrite chunk system
            if (close) this.closeChunkProvider(level, true);

        } else if (close) {
            this.closeChunkProvider(level, false);
        }
    }

    private void closeChunkProvider(@NotNull ServerLevel handle, boolean save){
        this.closeChunkHolderManager(handle, handle.moonrise$getChunkTaskScheduler().chunkHolderManager, save, true,true, true, false);
        try {
            handle.chunkSource.getDataStorage().close();
        } catch (Exception exception) {
            MinecraftServer.LOGGER.error("Failed to close persistent world data");
            exception.printStackTrace();
        }
    }

    public void closeChunkHolderManager(final ServerLevel world, final ChunkHolderManager manager, final boolean save, final boolean halt, final boolean first, final boolean last, final boolean checkRegions) {
        if (first && halt) {
            MinecraftServer.LOGGER.info("Waiting 60s for chunk system to halt for world '" + world.getWorld().getName() + "'");
            if (!world.moonrise$getChunkTaskScheduler().halt(true, TimeUnit.SECONDS.toNanos(60L))) {
                MinecraftServer.LOGGER.warn("Failed to halt world generation/loading tasks for world '" + world.getWorld().getName() + "'");
            } else {
                MinecraftServer.LOGGER.info("Halted chunk system for world '" + world.getWorld().getName() + "'");
            }
        }

        if (save) {
            this.saveAllChunksNoCheck(world, manager, true, true, true, first, last); // Folia - region threading
        }

        if (last) { // Folia - region threading
            MoonriseRegionFileIO.flush(world);

            if (halt) {
                MinecraftServer.LOGGER.info("Waiting 60s for chunk I/O to halt for world '" + WorldUtil.getWorldName(world) + "'");
                if (!world.moonrise$getChunkTaskScheduler().haltIO(true, TimeUnit.SECONDS.toNanos(60L))) {
                    MinecraftServer.LOGGER.warn("Failed to halt I/O tasks for world '" + WorldUtil.getWorldName(world) + "'");
                } else {
                    MinecraftServer.LOGGER.info("Halted I/O scheduler for world '" + WorldUtil.getWorldName(world) + "'");
                }
            }

            // kill regionfile cache
            for (final MoonriseRegionFileIO.RegionFileType type : MoonriseRegionFileIO.RegionFileType.values()) {
                try {
                    MoonriseRegionFileIO.getControllerFor(world, type).getCache().close();
                } catch (final IOException ex) {
                    MinecraftServer.LOGGER.error("Failed to close '" + type.name() + "' regionfile cache for world '" + WorldUtil.getWorldName(world) + "'", ex);
                }
            }
        } // Folia - region threading
    }


    private void removeWorldFromRegionizedServer(ServerLevel level){
        try {
            final Class<RegionizedServer> targetClass = RegionizedServer.class;
            final Field worldListField = targetClass.getDeclaredField("worlds");
            worldListField.setAccessible(true);
            final List<ServerLevel> worldList = (List<ServerLevel>) worldListField.get(RegionizedServer.getInstance());

            worldList.remove(level);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean unloadWorld(@NotNull World world, boolean save) {
        io.papermc.paper.threadedregions.RegionizedServer.ensureGlobalTickThread("World unload can be done only on global tick thread");

        if (world == null) {
            return false;
        }

        final CraftServer craftServer = ((CraftServer) Bukkit.getServer());
        final MinecraftServer console = craftServer.getServer();
        ServerLevel handle = ((CraftWorld) world).getHandle();

        if (console.getLevel(handle.dimension()) == null) {
            return false;
        }

        if (handle.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            return false;
        }

        if (!handle.players().isEmpty()) {
            return false;
        }

        WorldUnloadEvent e = new WorldUnloadEvent(handle.getWorld());
        Bukkit.getPluginManager().callEvent(e);

        if (e.isCancelled()) {
            return false;
        }

        try {
            this.removeWorldFromRegionizedServer(handle);
            this.killAllThreadedRegionsOnce(handle);

            if (save) {
                this.save(handle, true, false);
            }

            this.closeChunkProvider(handle, save);
            handle.convertable.close();
        } catch (Exception ex) {
            Bukkit.getLogger().log(java.util.logging.Level.SEVERE, null, ex);
        }

        final Map<String, World> worlds;

        //Ugly reflection :(
        try {
            final Class<CraftServer> craftServerClass = CraftServer.class;
            final Field worldsField = craftServerClass.getDeclaredField("worlds");
            worldsField.setAccessible(true);
            worlds = ((Map<String, World>) worldsField.get(craftServer));
        }catch (Exception ex){
            throw new RuntimeException(ex);
        }

        worlds.remove(world.getName().toLowerCase(java.util.Locale.ENGLISH));
        console.removeLevel(handle);
        return true;
    }

    @Override
    public boolean unloadWorld(@NotNull String name, boolean save) {
        return this.unloadWorld(Bukkit.getWorld(name),save);
    }

    @Override
    public World createWorld(@NotNull WorldCreator creator) {
        io.papermc.paper.threadedregions.RegionizedServer.ensureGlobalTickThread("World create can be done only on global tick thread");
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        DedicatedServer console = craftServer.getServer();

        String name = creator.name();
        ChunkGenerator generator = creator.generator();
        BiomeProvider biomeProvider = creator.biomeProvider();
        File folder = new File(craftServer.getWorldContainer(), name);
        World world = craftServer.getWorld(name);

        // Paper start
        World worldByKey = craftServer.getWorld(creator.key());
        if (world != null || worldByKey != null) {
            if (world == worldByKey) {
                return world;
            }
            throw new IllegalArgumentException("Cannot create a world with key " + creator.key() + " and name " + name + " one (or both) already match a world that exists");
        }
        // Paper end

        if (folder.exists()) {
            Preconditions.checkArgument(folder.isDirectory(), "File (%s) exists and isn't a folder", name);
        }

        if (generator == null) {
            generator = craftServer.getGenerator(name);
        }

        if (biomeProvider == null) {
            biomeProvider = craftServer.getBiomeProvider(name);
        }


        ResourceKey<LevelStem> actualDimension = switch (creator.environment()) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> throw new IllegalArgumentException("Illegal dimension (" + creator.environment() + ")");
        };

        LevelStorageSource.LevelStorageAccess worldSession;
        try {
            worldSession = LevelStorageSource.createDefault(craftServer.getWorldContainer().toPath()).validateAndCreateAccess(name, actualDimension);
        } catch (IOException | ContentValidationException ex) {
            throw new RuntimeException(ex);
        }

        Dynamic<?> dynamic;
        if (worldSession.hasWorldData()) {
            net.minecraft.world.level.storage.LevelSummary worldinfo;

            try {
                dynamic = worldSession.getDataTag();
                worldinfo = worldSession.getSummary(dynamic);
            } catch (NbtException | ReportedNbtException | IOException ioexception) {
                LevelStorageSource.LevelDirectory convertable_b = worldSession.getLevelDirectory();

                MinecraftServer.LOGGER.warn("Failed to load world data from {}", convertable_b.dataFile(), ioexception);
                MinecraftServer.LOGGER.info("Attempting to use fallback");

                try {
                    dynamic = worldSession.getDataTagFallback();
                    worldinfo = worldSession.getSummary(dynamic);
                } catch (NbtException | ReportedNbtException | IOException ioexception1) {
                    MinecraftServer.LOGGER.error("Failed to load world data from {}", convertable_b.oldDataFile(), ioexception1);
                    MinecraftServer.LOGGER.error("Failed to load world data from {} and {}. World files may be corrupted. Shutting down.", convertable_b.dataFile(), convertable_b.oldDataFile());
                    return null;
                }

                worldSession.restoreLevelDataFromOld();
            }

            if (worldinfo.requiresManualConversion()) {
                MinecraftServer.LOGGER.info("This world must be opened in an older version (like 1.6.4) to be safely converted");
                return null;
            }

            if (!worldinfo.isCompatible()) {
                MinecraftServer.LOGGER.info("This world was created by an incompatible version.");
                return null;
            }
        } else {
            dynamic = null;
        }

        boolean hardcore = creator.hardcore();

        PrimaryLevelData worlddata;
        WorldLoader.DataLoadContext worldloader_a = MinecraftServer.getServer().worldLoader;
        RegistryAccess.Frozen iregistrycustom_dimension = worldloader_a.datapackDimensions();
        net.minecraft.core.Registry<LevelStem> iregistry = iregistrycustom_dimension.lookupOrThrow(Registries.LEVEL_STEM);
        if (dynamic != null) {
            LevelDataAndDimensions leveldataanddimensions = LevelStorageSource.getLevelDataAndDimensions(dynamic, worldloader_a.dataConfiguration(), iregistry, worldloader_a.datapackWorldgen());

            worlddata = (PrimaryLevelData) leveldataanddimensions.worldData();
            iregistrycustom_dimension = leveldataanddimensions.dimensions().dimensionsRegistryAccess();
        } else {
            LevelSettings worldsettings;
            WorldOptions worldoptions = new WorldOptions(creator.seed(), creator.generateStructures(), false);
            WorldDimensions worlddimensions;

            DedicatedServerProperties.WorldDimensionData properties = new DedicatedServerProperties.WorldDimensionData(GsonHelper.parse((creator.generatorSettings().isEmpty()) ? "{}" : creator.generatorSettings()), creator.type().name().toLowerCase(Locale.ROOT));

            worldsettings = new LevelSettings(name, GameType.byId(craftServer.getDefaultGameMode().getValue()), hardcore, Difficulty.EASY, false, new GameRules(worldloader_a.dataConfiguration().enabledFeatures()), worldloader_a.dataConfiguration());
            worlddimensions = properties.create(worldloader_a.datapackWorldgen());

            WorldDimensions.Complete worlddimensions_b = worlddimensions.bake(iregistry);
            Lifecycle lifecycle = worlddimensions_b.lifecycle().add(worldloader_a.datapackWorldgen().allRegistriesLifecycle());

            worlddata = new PrimaryLevelData(worldsettings, worldoptions, worlddimensions_b.specialWorldProperty(), lifecycle);
            iregistrycustom_dimension = worlddimensions_b.dimensionsRegistryAccess();
        }
        iregistry = iregistrycustom_dimension.lookupOrThrow(Registries.LEVEL_STEM);
        worlddata.customDimensions = iregistry;
        worlddata.checkName(name);
        worlddata.setModdedInfo(MinecraftServer.getServer().getServerModName(), MinecraftServer.getServer().getModdedStatus().shouldReportAsModified());

        if (MinecraftServer.getServer().options.has("forceUpgrade")) {
            net.minecraft.server.Main.forceUpgrade(worldSession, DataFixers.getDataFixer(), MinecraftServer.getServer().options.has("eraseCache"), () -> true, iregistrycustom_dimension, MinecraftServer.getServer().options.has("recreateRegionFiles"));
        }

        long j = BiomeManager.obfuscateSeed(worlddata.worldGenOptions().seed()); // Paper - use world seed
        List<CustomSpawner> list = ImmutableList.of(new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(worlddata));
        LevelStem worlddimension = iregistry.getValue(actualDimension);

        WorldInfo worldInfo = new CraftWorldInfo(worlddata, worldSession, creator.environment(), worlddimension.type().value(), worlddimension.generator(), craftServer.getHandle().getServer().registryAccess()); // Paper - Expose vanilla BiomeProvider from WorldInfo
        if (biomeProvider == null && generator != null) {
            biomeProvider = generator.getDefaultBiomeProvider(worldInfo);
        }

        ResourceKey<net.minecraft.world.level.Level> worldKey;
        String levelName = craftServer.getServer().getProperties().levelName;
        if (name.equals(levelName + "_nether")) {
            worldKey = net.minecraft.world.level.Level.NETHER;
        } else if (name.equals(levelName + "_the_end")) {
            worldKey = net.minecraft.world.level.Level.END;
        } else {
            worldKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(creator.key().namespace(), creator.key().value()));
        }

        // If set to not keep spawn in memory (changed from default) then adjust rule accordingly
        if (creator.keepSpawnLoaded() == net.kyori.adventure.util.TriState.FALSE) { // Paper
            worlddata.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        }

        ServerLevel internal = new ServerLevel(console, console.executor, worldSession, worlddata, worldKey, worlddimension, console.progressListenerFactory.create(11),
                worlddata.isDebugWorld(), j, creator.environment() == org.bukkit.World.Environment.NORMAL ? list : ImmutableList.of(), true, null, creator.environment(), generator, biomeProvider);

        internal.randomSpawnSelection = new ChunkPos(internal.getChunkSource().randomState().sampler().findSpawnPosition());
        int loadRegionRadius = ((1024) >> 4);
        for (int currX = -loadRegionRadius; currX <= loadRegionRadius; ++currX) {
            for (int currZ = -loadRegionRadius; currZ <= loadRegionRadius; ++currZ) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(currX, currZ);
                internal.chunkSource.addTicketAtLevel(
                        TicketType.UNKNOWN, pos, ChunkHolderManager.MAX_TICKET_LEVEL, pos
                );
            }
        }

        console.addLevel(internal);
        internal.setSpawnSettings(true);

        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addWorld(internal);

        Bukkit.getPluginManager().callEvent(new WorldLoadEvent(internal.getWorld()));

        return internal.getWorld();
    }
}
