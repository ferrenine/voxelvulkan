package voxel.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Small file helpers for data that must never be observed half-written.
 *
 * <p>A crash in the middle of {@link Files#writeString(Path, CharSequence)} can
 * leave a truncated JSON file behind. Writing to a sibling temporary file and
 * renaming it over the target keeps the previous contents intact until the new
 * contents are complete. The rename is atomic on every file system that
 * supports it; the non-atomic fallback is still better than an in-place write
 * because the target is replaced in one operation.</p>
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeString(Path path, String contents) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(contents, "contents");

        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent directory: " + path);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
