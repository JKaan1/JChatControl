package dev.jkaanof.jchatcontrol.bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Appends violations to logs/violations-YYYY-MM-DD.log on a background thread. */
public final class ViolationLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path dir;
    private final boolean enabled;
    private final Logger logger;
    private final ExecutorService executor;

    public ViolationLog(Path dir, boolean enabled, Logger logger) {
        this.dir = dir;
        this.enabled = enabled;
        this.logger = logger;
        this.executor = enabled ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JChatControl-Log");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    public void log(String player, String context, String category, String source, String detail, String message) {
        if (!enabled) {
            return;
        }
        String line = LocalTime.now().format(TIME) + " [" + context + "] " + player + " | " + category + " | "
                + source + " | " + detail + " | " + message.replace('\n', ' ') + System.lineSeparator();
        Path file = dir.resolve("violations-" + LocalDate.now() + ".log");
        executor.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warning("Cannot write violation log: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
