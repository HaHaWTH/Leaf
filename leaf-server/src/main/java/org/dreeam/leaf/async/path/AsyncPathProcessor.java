package org.dreeam.leaf.async.path;

import com.destroystokyo.paper.util.SneakyThrow;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * used to handle the scheduling of async path processing
 */
public class AsyncPathProcessor {

    private static final String THREAD_PREFIX = "Leaf Async Pathfinding";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static long lastWarnMillis = System.currentTimeMillis();
    private static final ThreadPoolExecutor pathProcessingExecutor = new ThreadPoolExecutor(
        1,
        org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingMaxThreads,
        org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingKeepalive, TimeUnit.SECONDS,
        getQueueImpl(),
        new ThreadFactoryBuilder()
            .setNameFormat(THREAD_PREFIX + " Thread - %d")
            .setPriority(Thread.NORM_PRIORITY - 2)
            .build(),
        new RejectedTaskHandler()
    );

    private static class RejectedTaskHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable rejectedTask, ThreadPoolExecutor executor) {
            BlockingQueue<Runnable> workQueue = executor.getQueue();
            if (!executor.isShutdown()) {
                if (!workQueue.isEmpty()) {
                    List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                    workQueue.drainTo(pendingTasks);

                    for (Runnable pendingTask : pendingTasks) {
                        pendingTask.run();
                    }
                }

                rejectedTask.run();
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async pathfinding processor is busy! Pathfinding tasks will be done in the server thread. Increasing max-threads in Leaf config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        }
    }

    protected static CompletableFuture<Void> queue(@NotNull AsyncPath path) {
        return CompletableFuture.runAsync(path::process, pathProcessingExecutor)
            .orTimeout(60L, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                if (throwable instanceof TimeoutException e) {
                    LOGGER.warn("Async Pathfinding process timed out", e);
                } else SneakyThrow.sneaky(throwable);
                return null;
            });
    }

    /**
     * takes a possibly unprocessed path, and waits until it is completed
     * the consumer will be immediately invoked if the path is already processed
     * the consumer will always be called on the main thread
     *
     * @param entity          the entity that owns the path
     * @param path            a path to wait on
     * @param afterProcessing a consumer to be called
     */
    public static void awaitProcessing(Entity entity, @Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        if (path != null && !path.isProcessed() && path instanceof AsyncPath asyncPath) {
            asyncPath.postProcessing(() ->
                entity.getBukkitEntity().taskScheduler.schedule(nmsEntity -> afterProcessing.accept(path), null, 1)
            );
        } else {
            afterProcessing.accept(path);
        }
    }

    private static BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingMaxThreads * (Math.max(org.dreeam.leaf.config.modules.async.AsyncPathfinding.asyncPathfindingMaxThreads, 4));

        return new LinkedBlockingQueue<>(queueCapacity);
    }}
