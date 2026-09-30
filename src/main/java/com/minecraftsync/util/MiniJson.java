package com.minecraftsync.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecteur/écrivain JSON minimal, sans dépendance, pour le manifeste et le verrou distants.
 * Types produits : Map (LinkedHashMap), List, String, Long, Double, Boolean, null.
 */
public final class MiniJson {

    private MiniJson() {
    }

    // ---------- Écriture ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            sb.append(n.doubleValue());
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            List<Object> items = new ArrayList<>();
            list.forEach(items::add);
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < items.size(); i++) {
                pad(sb, indent + 1);
                write(sb, items.get(i), indent + 1);
                if (i < items.size() - 1) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- Lecture ----------

    public static Object parse(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != json.length()) throw p.error("contenu inattendu après la valeur");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Objet JSON attendu");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON invalide (" + msg + ") à la position " + pos);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            if (pos >= s.length()) throw error("fin inattendue");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) throw error("'" + c + "' attendu");
            pos++;
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("littéral inconnu");
            pos += word.length();
            return value;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') return map;
                if (c != ',') throw error("',' ou '}' attendu");
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') return list;
                if (c != ',') throw error("',' ou ']' attendu");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                pos++;
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("séquence \\u incomplète");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("échappement inconnu");
                }
            }
        }

        Object number() {
            int start = pos;
            boolean decimal = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) throw error("valeur attendue");
            String n = s.substring(start, pos);
            return decimal ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
    }
}
