package voxel.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path directory;

    @Test
    void writesAndReplacesContentWithoutLeavingTemporaryFiles() throws IOException {
        Path target = this.directory.resolve("config.json");

        AtomicFiles.writeString(target, "{\"value\":1}");
        assertEquals("{\"value\":1}", Files.readString(target, StandardCharsets.UTF_8));

        AtomicFiles.writeString(target, "{\"value\":2}");
        assertEquals("{\"value\":2}", Files.readString(target, StandardCharsets.UTF_8));

        try (var files = Files.list(this.directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path target = this.directory.resolve("nested").resolve("config.json");

        AtomicFiles.writeString(target, "{}");

        assertTrue(Files.exists(target));
        assertEquals("{}", Files.readString(target, StandardCharsets.UTF_8));
    }
}
