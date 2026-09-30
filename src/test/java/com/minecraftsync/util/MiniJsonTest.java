package com.minecraftsync.util;

import com.minecraftsync.sync.Manifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void manifestRoundTrip() {
        Manifest m = new Manifest();
        m.setRevision(42);
        m.setSessionName("Ma \"Survie\" é\\n");
        m.setWorldFolderName("Survival");
        m.setMinecraftVersion("1.21.10");
        m.setModded(true);
        m.setDeviceName("PC-A");
        m.setUpdatedAt(Instant.parse("2026-09-30T10:15:30Z"));
        m.files().put("region/r.0.0.mca", new Manifest.Entry(123456789L, "abc", "id1"));
        m.files().put("data/ünïcødé file.dat", new Manifest.Entry(0, "def", "id2"));
        m.dirs().add("datapacks");

        Manifest back = Manifest.fromJson(m.toJson());
        assertEquals(42L, back.revision());
        assertEquals(m.sessionName(), back.sessionName());
        assertEquals(Boolean.TRUE, back.modded());
        assertEquals(m.updatedAt(), back.updatedAt());
        assertEquals(m.files(), back.files());
        assertTrue(back.dirs().contains("datapacks"));
    }

    @Test
    void parsesNestedValues() {
        Map<String, Object> o = MiniJson.parseObject("{\"a\": [1, 2.5, true, null, \"x\\u00e9\"], \"b\": {}}");
        assertEquals(5, ((java.util.List<?>) o.get("a")).size());
        assertEquals("xé", ((java.util.List<?>) o.get("a")).get(4));
    }
}
