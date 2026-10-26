import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class Server {
    static final Path DB = Paths.get("history.json");
    static final Path PAGE = Paths.get("index.html");
    static final int MAX_RUNS = 50;

    // ---------- tiny JSON helpers (only string values are needed) ----------
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static List<Map<String, String>> parse(String s) {
        List<Map<String, String>> list = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '{') {
                Map<String, String> m = new LinkedHashMap<>();
                i++;
                while (i < s.length() && s.charAt(i) != '}') {
                    if (s.charAt(i) == '"') {
                        int[] p = {i};
                        String key = readString(s, p);
                        i = p[0];
                        while (i < s.length() && s.charAt(i) != '"') i++;
                        p[0] = i;
                        String val = readString(s, p);
                        i = p[0];
                        m.put(key, val);
                    } else {
                        i++;
                    }
                }
                list.add(m);
            }
            i++;
        }
        return list;
    }

    // reads a JSON string starting at the opening quote; moves p[0] past the closing quote
    static String readString(String s, int[] p) {
        StringBuilder b = new StringBuilder();
        int i = p[0] + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') b.append('\n');
                else if (n == 'r') b.append('\r');
                else if (n == 't') b.append('\t');
                else if (n == 'u') {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } else b.append(n);
            } else {
                b.append(c);
            }
            i++;
        }
        p[0] = i + 1;
        return b.toString();
    }

    // ---------- history file ----------
    static List<Map<String, String>> readHistory() {
        try {
            return parse(new String(Files.readAllBytes(DB), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static String toJson(List<Map<String, String>> list) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, String> m = list.get(i);
            if (i > 0) b.append(",");
            b.append("{\"mode\":\"").append(esc(m.getOrDefault("mode", "")))
             .append("\",\"op\":\"").append(esc(m.getOrDefault("op", "")))
             .append("\",\"data\":\"").append(esc(m.getOrDefault("data", ""))).append("\"}");
        }
        return b.append("]").toString();
    }

    // ---------- HTTP ----------
    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (path.equals("/api/history") && method.equals("GET")) {
            send(ex, 200, "application/json", toJson(readHistory()).getBytes(StandardCharsets.UTF_8));
        } else if (path.equals("/api/history") && method.equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, String>> runs = parse(body);
            if (runs.isEmpty()) {
                send(ex, 400, "application/json", "{\"error\":\"bad request\"}".getBytes());
                return;
            }
            List<Map<String, String>> data = readHistory();
            data.add(runs.get(0));
            while (data.size() > MAX_RUNS) data.remove(0);
            Files.write(DB, toJson(data).getBytes(StandardCharsets.UTF_8));
            send(ex, 201, "application/json", "{\"saved\":true}".getBytes());
        } else if (method.equals("GET")) {
            send(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(PAGE));
        } else {
            send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes());
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(3000), 0);
        server.createContext("/", ex -> {
            try {
                handle(ex);
            } catch (Exception e) {
                ex.close();
            }
        });
        server.start();
        System.out.println("Running at http://localhost:3000");
    }
}
