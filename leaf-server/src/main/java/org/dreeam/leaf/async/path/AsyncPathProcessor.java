package org.dreeam.leaf.async.path;

import com.destroystokyo.paper.util.SneakyThrow;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.pathfinder.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * used to handle the scheduling of async path processing
 */
public class AsyncPathProcessor {
    private static final Logger LOGGER = LogManager.getLogger("AsyncPathProcessor");
    private static final Set<UUID> queuedEntities = Sets.newConcurrentHashSet();
    private static long lastWarnMillis = System.currentTimeMillis();
    private static final ThreadPoolExecutor pathProcessingExecutor = new ThreadPoolExecutor(
        org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingMaxThreads,
        org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingMaxThreads,
        org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingKeepalive, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        new ThreadFactoryBuilder()
            .setNameFormat("Leaf Async Pathfinding Thread - %d")
            .setPriority(Thread.NORM_PRIORITY - 2)
            .build(),
        new RejectedTaskHandler()
    );

    static {
        pathProcessingExecutor.allowCoreThreadTimeOut(true);
    }

    private static class RejectedTaskHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable rejectedTask, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                try {
                    rejectedTask.run();
                } catch (Throwable th) {
                    SneakyThrow.sneaky(th);
                }
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async pathfinding processor is busy! Pathfinding tasks will be done in the server thread. Increasing max-threads in Leaf config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        }
    }

    protected static void removeFromQueue(UUID entityId) {
        queuedEntities.remove(entityId);
    }

    protected static CompletableFuture<Void> queue(@NotNull AsyncPath path) {
        if (queuedEntities.contains(path.getPathOwner())) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(path::process, pathProcessingExecutor)
            .orTimeout(60L, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                removeFromQueue(path.getPathOwner());
                if (throwable instanceof TimeoutException e) {
                    LOGGER.error("Async Pathfinding process timed out for entity {}", path.getPathOwner(), e);
                } else SneakyThrow.sneaky(throwable);
                return null;
            });
    }

    /**
     * takes a possibly unprocessed path, and waits until it is completed
     * the consumer will be immediately invoked if the path is already processed
     * the consumer will always be called on the main thread
     *
     * @param path            a path to wait on
     * @param afterProcessing a consumer to be called
     */
    public static void awaitProcessing(@Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        if (path != null && !path.isProcessed() && path instanceof AsyncPath asyncPath) {
            asyncPath.postProcessing(() ->
                MinecraftServer.getServer().scheduleOnMain(() -> afterProcessing.accept(path))
            );
        } else {
            afterProcessing.accept(path);
        }
    }
}
