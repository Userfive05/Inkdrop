package com.inkdrop.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny dependency-free HTTP server for uploading files from a phone browser.
 * Pure Java (no Android APIs) so it can be tested on a desktop JVM.
 *
 * Routes:
 *   GET  /                      upload page
 *   GET  /api/list?dir=D               JSON list of folders + files in D (relative to root)
 *   POST /api/upload?dir=D&name=X      raw request body is the file
 *   GET  /dl?dir=D&name=X              download a file
 *   POST /api/delete?dir=D&name=X      delete a file
 *   POST /api/rename?dir=D&name=X&to=Y rename a file
 *   POST /api/move?dir=D&name=X&dest=E move a file into folder E
 *   POST /api/mkdir?dir=D&name=N       create folder N inside D
 */
public class FileServer {

    public interface Listener {
        void onEvent(String message);
    }

    private final int port;
    private final String html;
    private final Listener listener;
    private volatile File root;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    public FileServer(int port, File root, String html, Listener listener) {
        this.port = port;
        this.root = root;
        this.html = html;
        this.listener = listener;
    }

    public void setRoot(File root) { this.root = root; }
    public File getRoot() { return root; }
    public boolean isRunning() { return running; }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newFixedThreadPool(6);
        running = true;
        Thread t = new Thread(this::acceptLoop, "InkDrop-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running) log("Accept error: " + e.getMessage());
            } catch (Exception e) {
                // pool shut down
            }
        }
    }

    private void log(String m) { if (listener != null) listener.onEvent(m); }

    // ---------------------------------------------------------------- request handling

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 65536);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { send(out, 400, "text/plain", "Bad request"); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) query.put(decode(kv.substring(0, e)), decode(kv.substring(e + 1)));
                }
            }

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", html);
            } else if (method.equals("GET") && (path.equals("/api/list") || path.equals("/api/files"))) {
                File dir = resolveDir(query.get("dir"));
                if (dir == null || !dir.isDirectory()) send(out, 404, "text/plain", "Folder not found");
                else send(out, 200, "application/json; charset=utf-8", listJson(dir));
            } else if (method.equals("POST") && path.equals("/api/upload")) {
                upload(in, out, headers, query.get("dir"), query.get("name"));
            } else if (method.equals("GET") && path.equals("/dl")) {
                download(out, query.get("dir"), query.get("name"));
            } else if (method.equals("POST") && path.equals("/api/delete")) {
                delete(out, query.get("dir"), query.get("name"));
            } else if (method.equals("POST") && path.equals("/api/rename")) {
                rename(out, query.get("dir"), query.get("name"), query.get("to"));
            } else if (method.equals("POST") && path.equals("/api/move")) {
                move(out, query.get("dir"), query.get("name"), query.get("dest"));
            } else if (method.equals("POST") && path.equals("/api/mkdir")) {
                mkdir(out, query.get("dir"), query.get("name"));
            } else if (method.equals("GET") && path.equals("/favicon.ico")) {
                send(out, 204, "text/plain", "");
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            log("Request error: " + e.getMessage());
        }
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> headers, String rawDir, String rawName) throws IOException {
        String lenHeader = headers.get("content-length");
        if (lenHeader == null) { send(out, 411, "text/plain", "Content-Length required"); return; }
        long length;
        try { length = Long.parseLong(lenHeader); } catch (NumberFormatException e) { send(out, 400, "text/plain", "Bad length"); return; }

        File dir = resolveDir(rawDir);
        if (dir == null) { send(out, 400, "text/plain", "Bad folder"); return; }
        if (!dir.exists() && !dir.mkdirs()) { send(out, 500, "text/plain", "Cannot create folder " + dir); return; }
        if (dir.getUsableSpace() < length) { send(out, 507, "text/plain", "Not enough free space"); return; }

        String name = sanitize(rawName);
        File dest = uniqueFile(dir, name);
        File tmp = new File(dir, "." + dest.getName() + ".part");

        long remaining = length;
        byte[] buf = new byte[65536];
        boolean ok = false;
        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp), 65536)) {
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                fos.write(buf, 0, n);
                remaining -= n;
            }
            ok = remaining == 0;
        } finally {
            if (!ok) tmp.delete();
        }
        if (!ok) { log("Upload interrupted: " + name); send(out, 400, "text/plain", "Upload incomplete"); return; }
        if (!tmp.renameTo(dest)) { tmp.delete(); send(out, 500, "text/plain", "Could not save file"); return; }

        log("Received " + dest.getName() + " (" + human(length) + ")");
        send(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"name\":" + jsonStr(dest.getName()) + "}");
    }

    private void download(OutputStream out, String dir, String name) throws IOException {
        File f = resolve(dir, name);
        if (f == null || !f.isFile()) { send(out, 404, "text/plain", "Not found"); return; }
        String encoded = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + f.length() + "\r\n"
                + "Content-Disposition: attachment; filename*=UTF-8''" + encoded + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        try (InputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    private void delete(OutputStream out, String dir, String name) throws IOException {
        File f = resolve(dir, name);
        if (f == null || !f.isFile()) { send(out, 404, "text/plain", "Not found"); return; }
        if (f.delete()) {
            log("Deleted " + f.getName());
            send(out, 200, "application/json", "{\"ok\":true}");
        } else {
            send(out, 500, "text/plain", "Could not delete");
        }
    }

    private void rename(OutputStream out, String dir, String name, String to) throws IOException {
        File f = resolve(dir, name);
        if (f == null || !f.isFile()) { send(out, 404, "text/plain", "File not found"); return; }
        if (to == null || to.trim().isEmpty()) { send(out, 400, "text/plain", "Enter a new name"); return; }
        File target = resolve(dir, to);
        if (target == null) { send(out, 400, "text/plain", "Invalid name"); return; }
        if (target.getName().equals(f.getName())) { send(out, 200, "application/json", okName(f.getName())); return; }
        // Allow changing only the capitalisation on case-insensitive storage
        boolean caseOnly = target.getName().equalsIgnoreCase(f.getName());
        if (target.exists() && !caseOnly) { send(out, 409, "text/plain", "A file named \"" + target.getName() + "\" already exists"); return; }
        if (!f.renameTo(target)) { send(out, 500, "text/plain", "Could not rename"); return; }
        log("Renamed " + f.getName() + " → " + target.getName());
        send(out, 200, "application/json; charset=utf-8", okName(target.getName()));
    }

    private void move(OutputStream out, String dir, String name, String dest) throws IOException {
        File f = resolve(dir, name);
        if (f == null || !f.isFile()) { send(out, 404, "text/plain", "File not found"); return; }
        File destDir = resolveDir(dest);
        if (destDir == null || !destDir.isDirectory()) { send(out, 404, "text/plain", "Folder not found"); return; }
        File target = new File(destDir, f.getName());
        if (target.getCanonicalFile().equals(f.getCanonicalFile())) { send(out, 200, "application/json", okName(f.getName())); return; }
        if (target.exists()) { send(out, 409, "text/plain", "That folder already has a file named \"" + f.getName() + "\""); return; }
        if (!f.renameTo(target)) { send(out, 500, "text/plain", "Could not move"); return; }
        log("Moved " + f.getName() + " → /" + (dest == null ? "" : dest));
        send(out, 200, "application/json; charset=utf-8", okName(target.getName()));
    }

    private void mkdir(OutputStream out, String dir, String name) throws IOException {
        File parent = resolveDir(dir);
        if (parent == null || !parent.isDirectory()) { send(out, 404, "text/plain", "Folder not found"); return; }
        if (name == null || name.trim().isEmpty()) { send(out, 400, "text/plain", "Enter a folder name"); return; }
        File f = new File(parent, sanitize(name));
        if (!isInside(f)) { send(out, 400, "text/plain", "Invalid name"); return; }
        if (f.exists()) { send(out, 409, "text/plain", "\"" + f.getName() + "\" already exists"); return; }
        if (!f.mkdir()) { send(out, 500, "text/plain", "Could not create folder"); return; }
        log("Created folder " + f.getName());
        send(out, 200, "application/json; charset=utf-8", okName(f.getName()));
    }

    private static String okName(String n) { return "{\"ok\":true,\"name\":" + jsonStr(n) + "}"; }

    private String listJson(File dir) {
        File[] folders = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (folders == null) folders = new File[0];
        if (files == null) files = new File[0];
        Arrays.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"root\":").append(jsonStr(root.getName()))
          .append(",\"folder\":").append(jsonStr(dir.getAbsolutePath()))
          .append(",\"free\":").append(dir.getUsableSpace())
          .append(",\"folders\":[");
        for (int i = 0; i < folders.length; i++) {
            if (i > 0) sb.append(',');
            String[] kids = folders[i].list();
            sb.append("{\"name\":").append(jsonStr(folders[i].getName()))
              .append(",\"count\":").append(kids == null ? 0 : kids.length).append('}');
        }
        sb.append("],\"files\":[");
        for (int i = 0; i < files.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(jsonStr(files[i].getName()))
              .append(",\"size\":").append(files[i].length())
              .append(",\"mtime\":").append(files[i].lastModified()).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---------------------------------------------------------------- helpers

    /** Resolves a folder path relative to root; null if it tries to escape root. */
    private File resolveDir(String dir) throws IOException {
        File f = root;
        if (dir != null) {
            for (String seg : dir.split("[/\\\\]")) {
                if (seg.isEmpty()) continue;
                if (seg.equals(".") || seg.equals("..")) return null;
                f = new File(f, seg);
            }
        }
        return isInside(f) ? f : null;
    }

    /** Returns a file directly inside the given folder, or null if it tries to escape root. */
    private File resolve(String dir, String name) throws IOException {
        if (name == null) return null;
        File d = resolveDir(dir);
        if (d == null) return null;
        File f = new File(d, sanitize(name));
        if (!isInside(f) || !f.getCanonicalFile().getParentFile().equals(d.getCanonicalFile())) return null;
        return f;
    }

    private boolean isInside(File f) throws IOException {
        String r = root.getCanonicalPath();
        String p = f.getCanonicalPath();
        return p.equals(r) || p.startsWith(r + File.separator);
    }

    static String sanitize(String name) {
        if (name == null) name = "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c < 32 || "<>:\"|?*".indexOf(c) >= 0) sb.append('_'); else sb.append(c);
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 200) s = s.substring(s.length() - 200);
        return s.isEmpty() ? "upload.bin" : s;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }
}
