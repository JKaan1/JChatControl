package dev.jkaanof.jchatcontrol.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class FileUtil {

    private FileUtil() {
    }

    /** Replaces target with tmp, atomically when the file system supports it. */
    public static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reads a word list: one entry per line, '#' starts a comment, blank lines ignored. */
    public static List<String> readWordList(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // allow several comma separated words on one line
            if (line.indexOf(',') >= 0) {
                for (String part : line.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
            } else {
                out.add(line);
            }
        }
        return out;
    }

    public static void writeLines(Path file, String header, Collection<String> lines) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        StringBuilder sb = new StringBuilder(lines.size() * 10 + 128);
        if (header != null) {
            for (String h : header.split("\n")) {
                sb.append("# ").append(h).append('\n');
            }
        }
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        replace(tmp, file);
    }
}
