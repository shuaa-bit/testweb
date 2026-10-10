package gym;

import java.lang.reflect.RecordComponent;
import java.util.*;

/** Tiny JSON reader/writer so the project needs no external libraries. */
final class Json {
    private Json() {}

    // ---------- writing ----------

    static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isRecord()) {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                for (RecordComponent rc : v.getClass().getRecordComponents()) {
                    m.put(rc.getName(), rc.getAccessor().invoke(v));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            write(sb, m);
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- reading ----------

    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw new IllegalArgumentException("Unexpected data after JSON value");
        return v;
    }

    /** Parse JSON text and map it onto a record type (String, int, double, boolean, List&lt;String&gt; fields). */
    static <T extends Record> T parseAs(String text, Class<T> type) {
        Object parsed = parse(text == null || text.isBlank() ? "{}" : text);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected a JSON object");
        return toRecord(map, type);
    }

    static <T extends Record> T toRecord(Map<?, ?> map, Class<T> type) {
        try {
            RecordComponent[] comps = type.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                args[i] = convert(map.get(comps[i].getName()), types[i]);
            }
            var ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not read " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object convert(Object v, Class<?> t) {
        if (t == String.class) return v == null ? null : String.valueOf(v);
        if (t == int.class) return v instanceof Number n ? n.intValue() : 0;
        if (t == double.class) return v instanceof Number n ? n.doubleValue() : 0.0;
        if (t == boolean.class) return v instanceof Boolean b && b;
        if (t == List.class) {
            List<String> out = new ArrayList<>();
            if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
            return out;
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> sb.append(e); // \" \\ \/
                }
            }
        }

        Double number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalArgumentException("Invalid JSON at position " + pos);
            return Double.parseDouble(s.substring(start, pos));
        }

        char peek() { return pos < s.length() ? s.charAt(pos) : '\0'; }

        void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
            pos++;
        }
    }
}
