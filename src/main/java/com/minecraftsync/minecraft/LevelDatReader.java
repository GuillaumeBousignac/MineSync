package com.minecraftsync.minecraft;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Lecture minimale du format NBT (Named Binary Tag) utilisé par level.dat.
 * Renvoie des Map/List/valeurs Java simples.
 */
public final class LevelDatReader {

    private static final int MAX_DEPTH = 512;

    private LevelDatReader() {
    }

    /** Lit un fichier NBT (compressé gzip ou non) et renvoie la racine. */
    public static Map<String, Object> read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        boolean gzip = data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
        try (InputStream raw = new java.io.ByteArrayInputStream(data);
             InputStream in = gzip ? new GZIPInputStream(raw) : raw;
             DataInputStream din = new DataInputStream(new BufferedInputStream(in))) {
            int type = din.readUnsignedByte();
            if (type != 10) throw new IOException("racine NBT inattendue (type " + type + ")");
            din.readUTF(); // nom de la racine
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(din, 10, 0);
            return root;
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT trop profond");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int len = checkedLength(in.readInt());
                byte[] b = new byte[len];
                in.readFully(b);
                yield b;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elemType = in.readUnsignedByte();
                int len = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(len, 1024));
                for (int i = 0; i < len; i++) list.add(readPayload(in, elemType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    map.put(name, readPayload(in, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int len = checkedLength(in.readInt());
                int[] a = new int[len];
                for (int i = 0; i < len; i++) a[i] = in.readInt();
                yield a;
            }
            case 12 -> {
                int len = checkedLength(in.readInt());
                long[] a = new long[len];
                for (int i = 0; i < len; i++) a[i] = in.readLong();
                yield a;
            }
            case 0 -> null;
            default -> throw new IOException("type NBT inconnu : " + type);
        };
    }

    private static int checkedLength(int len) throws IOException {
        if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("longueur NBT invalide : " + len);
        return len;
    }
}
