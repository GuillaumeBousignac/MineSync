package com.minecraftsync.sync;

import com.minecraftsync.util.MiniJson;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verrou de présence déposé sur le stockage distant quand un appareil joue sur le monde.
 * Il est rafraîchi régulièrement ; passé {@link #STALE_AFTER}, il est ignoré
 * (cas d'un PC éteint brutalement).
 */
public record RemoteLock(String deviceId, String deviceName, Instant heartbeat) {

    public static final String FILE_NAME = "lock.json";
    public static final Duration STALE_AFTER = Duration.ofMinutes(15);

    public boolean isFresh() {
        return heartbeat != null && heartbeat.isAfter(Instant.now().minus(STALE_AFTER));
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", deviceId);
        m.put("deviceName", deviceName);
        m.put("heartbeat", heartbeat.toString());
        return MiniJson.write(m);
    }

    public static RemoteLock fromJson(String json) {
        Map<String, Object> m = MiniJson.parseObject(json);
        Object hb = m.get("heartbeat");
        return new RemoteLock((String) m.get("deviceId"), (String) m.get("deviceName"),
                hb == null ? null : Instant.parse((String) hb));
    }
}
