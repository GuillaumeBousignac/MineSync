package com.minecraftsync.minecraft;

import com.minecraftsync.sync.ConflictManager;
import com.minecraftsync.sync.TestWorlds;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInfoTest {

    @Test
    void readsVersionAndModdedFlag() throws Exception {
        Path tmp = Files.createTempDirectory("mcsync-world");
        try {
            Path modded = tmp.resolve("saves/Arzmania");
            TestWorlds.createWorld(modded, "1.21.10", List.of("fabric"), 1);
            WorldInfo info = WorldInfo.read(modded, "test");
            assertEquals("1.21.10", info.version());
            assertEquals(Boolean.TRUE, info.modded());
            assertEquals("Arzmania", info.levelName());

            Path vanilla = tmp.resolve("saves/Survival");
            TestWorlds.createWorld(vanilla, "1.20.4", List.of("vanilla"), 2);
            assertEquals(Boolean.FALSE, WorldInfo.read(vanilla, "test").modded());

            Path unknown = tmp.resolve("saves/Old");
            Files.createDirectories(unknown);
            Files.writeString(unknown.resolve("level.dat"), "pas du NBT");
            WorldInfo u = WorldInfo.read(unknown, "test");
            assertNull(u.version());
            assertNull(u.modded());

            assertTrue(WorldInfo.isWorld(modded));
            assertFalse(WorldInfo.isWorld(tmp.resolve("saves")));
            List<WorldInfo> found = new WorldDetector().findWorlds(
                    List.of(new WorldDetector.SavesLocation(tmp.resolve("saves"), "test")));
            assertEquals(3, found.size());
        } finally {
            ConflictManager.deleteRecursively(tmp);
        }
    }
}
