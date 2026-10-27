package voxel.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import voxel.client.core.IGetVoxelRenderSystem;
import voxel.commonimpl.VoxelCommon;
import voxel.commonimpl.WorldIdentifier;
import voxel.commonimpl.importers.DHImporter;
import voxel.commonimpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class VoxelCommands {

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        return ClientCommandManager.literal("vx")
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxelCommands::reloadInstance))
                .then(importCommand());
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> registerFull() {
        return ClientCommandManager.literal("voxel")
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxelCommands::reloadInstance))
                .then(importCommand());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> importCommand() {
        var imports = ClientCommandManager.literal("import")
                .then(ClientCommandManager.literal("world")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxelCommands::importWorldSuggester)
                                .executes(VoxelCommands::importWorld)))
                .then(ClientCommandManager.literal("bobby")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxelCommands::importBobbySuggester)
                                .executes(VoxelCommands::importBobby)))
                .then(ClientCommandManager.literal("raw")
                        .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                .executes(VoxelCommands::importRaw)))
                .then(ClientCommandManager.literal("zip")
                        .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                .executes(VoxelCommands::importZip)
                                .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                         .executes(VoxelCommands::importZip))))
                .then(ClientCommandManager.literal("cancel")
                        .executes(VoxelCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports.then(distantHorizonsImport());
        }

        return imports;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> distantHorizonsImport() {
        return ClientCommandManager.literal("distant_horizons")
                .executes(VoxelCommands::importDistantHorizonsAuto)
                .then(ClientCommandManager.argument("sqlDbPath", StringArgumentType.string())
                        .suggests(VoxelCommands::importDistantHorizonsSuggester)
                        .executes(VoxelCommands::importDistantHorizons));
    }

    public static int reloadInstanceDirect() {
        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§c[Voxel] LOD Renderer is disabled in settings."), false);
            }
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr != null) {
            ((IGetVoxelRenderSystem)wr).shutdownRenderer();
        }

        VoxelCommon.shutdownInstance();
        System.gc();
        VoxelCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();

        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("§a[Voxel] LOD Renderer reloaded successfully! (Ctrl+R)"), true);
        }
        return 0;
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        return reloadInstanceDirect();
    }

    private static int importDistantHorizonsAuto(CommandContext<FabricClientCommandSource> ctx) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return commandError(ctx, "自动查找只支持单机存档；请把世界文件夹或 DistantHorizons.sqlite 路径作为参数传入。");
        }
        var dbFile = server.getWorldPath(LevelResource.ROOT).resolve("DistantHorizons.sqlite").toFile();
        if (!dbFile.isFile()) {
            return commandError(ctx, "未找到 " + dbFile.getAbsolutePath()
                    + "，请把世界文件夹或 DistantHorizons.sqlite 路径作为参数传入。");
        }
        return startDistantHorizonsImport(ctx, dbFile);
    }

    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
        }
        if (!dbFile.isFile()) {
            return commandError(ctx, "找不到 DistantHorizons.sqlite: " + dbFile.getAbsolutePath());
        }
        return startDistantHorizonsImport(ctx, dbFile);
    }

    private static int startDistantHorizonsImport(CommandContext<FabricClientCommandSource> ctx, File dbFile) {
        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            return commandError(ctx, "Voxel 在设置里被关闭了，无法导入。");
        }
        var level = Minecraft.getInstance().level;
        var engine = WorldIdentifier.ofEngine(level);
        if (engine == null) {
            return commandError(ctx, "请先进入一个世界再导入。");
        }

        boolean started = instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile, engine, level, instance.getServiceManager(), instance.savingServiceRateLimiter));
        if (!started) {
            return commandError(ctx, "已经有导入任务在运行，请等待完成或先执行 /vx import cancel。");
        }
        ctx.getSource().sendFeedback(Component.literal("开始导入 Distant Horizons: " + dbFile.getAbsolutePath()));
        return 0;
    }

    private static int commandError(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendError(Component.literal(message));
        return 1;
    }

    private static CompletableFuture<Suggestions> importDistantHorizonsSuggester(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        String raw = sb.getRemaining().replace('\\', '/');
        if (raw.startsWith("\"")) {
            raw = raw.substring(1);
        }
        if (raw.endsWith("\"")) {
            raw = raw.substring(0, raw.length()-1);
        }

        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
        Path dir = root;
        String prefix = "";
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) {
            prefix = raw.substring(0, slash+1);
            try {
                dir = root.resolve(raw.substring(0, slash));
            } catch (Exception e) {
                return sb.buildFuture();
            }
        }
        String remaining = raw.substring(slash+1);
        final String suggestionPrefix = prefix;

        try (var entries = Files.list(dir)) {
            entries.filter(path -> Files.isDirectory(path)
                            || path.getFileName().toString().endsWith(".sqlite"))
                    .map(path -> path.getFileName().toString()
                            + (Files.isDirectory(path) ? "/" : ""))
                    .filter(name -> SharedSuggestionProvider.matchesSubStr(remaining, name))
                    .sorted()
                    .limit(50)
                    .forEach(name -> sb.suggest(suggestionPrefix + name));
        } catch (Exception ignored) {
            // A missing or unreadable saves directory simply has no suggestions.
        }
        return sb.buildFuture();
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxelCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.literal("Voxel must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxelCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.literal("Voxel must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }

    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxelCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.literal("Voxel must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.literal("Voxel must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxelClientInstance)VoxelCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.literal("Voxel must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}
