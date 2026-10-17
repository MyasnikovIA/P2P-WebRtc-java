package ru.miacomsoft;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebVideoChat {

    static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final Path   WWW_ROOT = Paths.get("www").toAbsolutePath().normalize();
    static final ConcurrentHashMap<String, Room> ROOMS = new ConcurrentHashMap<>();
    static final int    DEFAULT_PORT = 8080;
    static final int    MAX_FRAME    = 16 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        System.out.println("WebVideoChat");
        System.out.println("  port : " + port);
        System.out.println("  www  : " + WWW_ROOT);
        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                Socket socket = server.accept();
                Thread.ofVirtual()
                        .name("conn-" + socket.getPort())
                        .start(() -> handleConnection(socket));
            }
        }
    }

    static void handleConnection(Socket socket) {
        try (socket) {
            BufferedInputStream in  = new BufferedInputStream(socket.getInputStream());
            OutputStream        out = socket.getOutputStream();

            String requestLine = readHttpLine(in);
            if (requestLine == null || requestLine.isBlank()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String hline;
            while ((hline = readHttpLine(in)) != null && !hline.isEmpty()) {
                int idx = hline.indexOf(':');
                if (idx > 0) {
                    headers.put(hline.substring(0, idx).trim().toLowerCase(),
                            hline.substring(idx + 1).trim());
                }
            }

            String path  = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path  = target.substring(0, q);
                query = target.substring(q + 1);
            }

            String upgrade = headers.getOrDefault("upgrade", "");
            if ("websocket".equalsIgnoreCase(upgrade) && path.startsWith("/ws/")) {
                handleWebSocket(socket, in, out, path, query, headers);
                return;
            }

            if ("GET".equals(method) || "HEAD".equals(method)) {
                serveStatic(out, path, "HEAD".equals(method));
            } else {
                writeHttpResponse(out, 405, "Method Not Allowed",
                        "text/plain; charset=utf-8",
                        "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
            }
        } catch (Exception ignored) {
        }
    }

    static void serveStatic(OutputStream out, String path, boolean headOnly) throws IOException {
        if (path.isEmpty() || "/".equals(path)) path = "/index.html";

        Path file = WWW_ROOT.resolve(path.substring(1)).normalize();
        if (!file.startsWith(WWW_ROOT)) {
            writeHttpResponse(out, 403, "Forbidden", "text/plain; charset=utf-8",
                    "Forbidden".getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            writeHttpResponse(out, 404, "Not Found", "text/plain; charset=utf-8",
                    "Not Found".getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        byte[] data = Files.readAllBytes(file);
        writeHttpResponse(out, 200, "OK", mimeType(file.getFileName().toString()), data, headOnly);
    }

    static String mimeType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js"))    return "application/javascript; charset=utf-8";
        if (n.endsWith(".css"))   return "text/css; charset=utf-8";
        if (n.endsWith(".json"))  return "application/json; charset=utf-8";
        if (n.endsWith(".png"))   return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg"))   return "image/svg+xml";
        if (n.endsWith(".ico"))   return "image/x-icon";
        return "application/octet-stream";
    }

    static void writeHttpResponse(OutputStream out, int code, String reason, String mime,
                                  byte[] body, boolean headOnly) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) out.write(body);
        out.flush();
    }

    static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                byte[] arr = baos.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') len--;
                return new String(arr, 0, len, StandardCharsets.ISO_8859_1);
            }
            baos.write(b);
        }
        return any ? baos.toString(StandardCharsets.ISO_8859_1) : null;
    }

    static void handleWebSocket(Socket socket, BufferedInputStream in, OutputStream out,
                                String path, String query, Map<String, String> headers)
            throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) return;

        String accept = base64Sha1(key + WS_GUID);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        Map<String, String> params = parseQuery(query);
        String roomId = params.getOrDefault("room", "main");

        WsConnection ws = new WsConnection(socket, in, out);

        switch (path) {
            case "/ws/publish"   -> handlePublisher(ws, roomId);
            case "/ws/subscribe" -> handleSubscriber(ws, roomId);
            default              -> ws.close(1008, "unknown endpoint");
        }
    }

    static void handlePublisher(WsConnection ws, String roomId) {
        Room room = ROOMS.computeIfAbsent(roomId, Room::new);

        synchronized (room) {
            if (room.publisherWs != null) {
                room.publisherWs.close(1000, "replaced");
            }
            room.publisherWs = ws;
            for (WsConnection v : room.viewers.values()) {
                v.close(1000, "publisher restarted");
            }
            room.viewers.clear();
        }
        System.out.println("[publish] +room=" + roomId + " " + ws.remote());

        try {
            while (!ws.isClosed()) {
                WsFrame frame = ws.readFrame();
                if (frame == null) break;

                switch (frame.opcode) {
                    case 0x8 -> { ws.close(1000, "bye"); return; }
                    case 0x9 -> ws.sendPong(frame.payload);
                    case 0x1 -> {
                        String text = new String(frame.payload, StandardCharsets.UTF_8);
                        try {
                            JSONObject msg = new JSONObject(text);
                            String target = msg.optString("target");
                            synchronized (room) {
                                WsConnection v = room.viewers.get(target);
                                if (v != null) v.sendText(text);
                            }
                        } catch (Exception e) {}
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (room) {
                if (room.publisherWs == ws) room.publisherWs = null;
            }
            ws.close(1000, "bye");
            System.out.println("[publish] -room=" + roomId);
        }
    }

    static void handleSubscriber(WsConnection ws, String roomId) {
        Room room = ROOMS.computeIfAbsent(roomId, Room::new);
        String viewerId = UUID.randomUUID().toString();

        try {
            synchronized (room) {
                room.viewers.put(viewerId, ws);
                ws.sendText(new JSONObject().put("viewerId", viewerId).toString());

                if (room.publisherWs != null) {
                    room.publisherWs.sendText(new JSONObject().put("viewerJoined", viewerId).toString());
                }
            }
            System.out.println("[subscribe] +room=" + roomId + " viewer=" + viewerId);

            while (!ws.isClosed()) {
                WsFrame frame = ws.readFrame();
                if (frame == null) break;

                switch (frame.opcode) {
                    case 0x8 -> { ws.close(1000, "bye"); return; }
                    case 0x9 -> ws.sendPong(frame.payload);
                    case 0x1 -> {
                        String text = new String(frame.payload, StandardCharsets.UTF_8);
                        try {
                            JSONObject msg = new JSONObject(text);
                            if ("publisher".equals(msg.optString("target"))) {
                                msg.put("viewerId", viewerId);
                                synchronized (room) {
                                    if (room.publisherWs != null) {
                                        room.publisherWs.sendText(msg.toString());
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (room) {
                room.viewers.remove(viewerId);
                if (room.publisherWs != null) {
                    try { room.publisherWs.sendText(new JSONObject().put("viewerLeft", viewerId).toString()); } catch(Exception e){}
                }
            }
            ws.close(1000, "bye");
            System.out.println("[subscribe] -room=" + roomId + " viewer=" + viewerId);
        }
    }

    static String base64Sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(
                md.digest(s.getBytes(StandardCharsets.ISO_8859_1)));
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx >= 0) {
                map.put(urlDecode(pair.substring(0, idx)),
                        urlDecode(pair.substring(idx + 1)));
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        return map;
    }

    static String urlDecode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    static class Room {
        final String id;
        volatile WsConnection publisherWs;
        final Map<String, WsConnection> viewers = new ConcurrentHashMap<>();
        Room(String id) { this.id = id; }
    }

    static class WsFrame {
        int opcode;
        byte[] payload;
    }

    static class RawFrame {
        boolean fin;
        int opcode;
        boolean masked;
        byte[] payload;
    }

    static class WsConnection {
        final Socket socket;
        final BufferedInputStream in;
        final OutputStream out;
        final Object writeLock = new Object();
        volatile boolean closed = false;

        WsConnection(Socket socket, BufferedInputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        String remote() { return socket.getRemoteSocketAddress().toString(); }
        boolean isClosed() { return closed || socket.isClosed(); }

        int readByte() throws IOException {
            int b = in.read();
            if (b == -1) throw new EOFException();
            return b;
        }

        void readFully(byte[] buf, int off, int len) throws IOException {
            int got = 0;
            while (got < len) {
                int r = in.read(buf, off + got, len - got);
                if (r == -1) throw new EOFException();
                got += r;
            }
        }

        RawFrame readRawFrame() throws IOException {
            int b0 = in.read();
            if (b0 == -1) return null;
            int b1 = in.read();
            if (b1 == -1) return null;

            RawFrame f = new RawFrame();
            f.fin    = (b0 & 0x80) != 0;
            f.opcode = b0 & 0x0F;
            f.masked = (b1 & 0x80) != 0;

            long len = b1 & 0x7F;
            if (len == 126)      len = ((long) readByte() << 8) | readByte();
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(); }

            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too big: " + len);

            byte[] mask = null;
            if (f.masked) { mask = new byte[4]; readFully(mask, 0, 4); }

            byte[] payload = new byte[(int) len];
            if (payload.length > 0) readFully(payload, 0, payload.length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            f.payload = payload;
            return f;
        }

        WsFrame readFrame() throws IOException {
            RawFrame first = readRawFrame();
            if (first == null) return null;

            if (first.opcode >= 0x8) {
                WsFrame f = new WsFrame();
                f.opcode = first.opcode;
                f.payload = first.payload;
                return f;
            }

            if (first.fin) {
                WsFrame f = new WsFrame();
                f.opcode = first.opcode;
                f.payload = first.payload;
                return f;
            }

            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            acc.write(first.payload);
            while (true) {
                RawFrame nxt = readRawFrame();
                if (nxt == null) return null;
                if ((long) acc.size() + nxt.payload.length > MAX_FRAME) {
                    throw new IOException("message too big");
                }
                acc.write(nxt.payload);
                if (nxt.fin) break;
            }
            WsFrame f = new WsFrame();
            f.opcode = first.opcode;
            f.payload = acc.toByteArray();
            return f;
        }

        void sendText(String text) throws IOException {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        void sendPong(byte[] payload) throws IOException {
            sendFrame(0xA, payload);
        }

        void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (writeLock) {
                if (closed) return;
                try {
                    ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
                    frame.write(0x80 | opcode);
                    int len = payload.length;
                    if (len < 126) {
                        frame.write(len);
                    } else if (len < 65536) {
                        frame.write(126);
                        frame.write((len >>> 8) & 0xFF);
                        frame.write(len & 0xFF);
                    } else {
                        frame.write(127);
                        for (int i = 7; i >= 0; i--) {
                            frame.write((int) (((long) len >>> (8 * i)) & 0xFF));
                        }
                    }
                    frame.write(payload);
                    out.write(frame.toByteArray());
                    out.flush();
                } catch (IOException e) {
                    closed = true;
                    throw e;
                }
            }
        }

        void close(int code, String reason) {
            if (closed) return;
            synchronized (writeLock) {
                if (!closed) {
                    try {
                        ByteArrayOutputStream payload = new ByteArrayOutputStream();
                        payload.write((code >>> 8) & 0xFF);
                        payload.write(code & 0xFF);
                        if (reason != null) {
                            payload.write(reason.getBytes(StandardCharsets.UTF_8));
                        }
                        sendFrame(0x8, payload.toByteArray());
                    } catch (IOException ignored) { }
                }
                closed = true;
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }
}