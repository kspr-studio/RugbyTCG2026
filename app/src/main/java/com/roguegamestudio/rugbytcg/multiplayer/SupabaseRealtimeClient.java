package com.roguegamestudio.rugbytcg.multiplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SupabaseRealtimeClient {
    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
        void onMatchAction(SupabaseService.MatchAction action);
    }

    private static final long HEARTBEAT_MS = 15_000L;

    private final String publishableKey;
    private final String accessToken;
    private final String matchId;
    private final Listener listener;
    private final String socketUrl;
    private final String topic;
    private final OkHttpClient client;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger refCounter = new AtomicInteger(1);
    private final Object lock = new Object();

    private WebSocket socket;
    private ScheduledFuture<?> heartbeatFuture;
    private boolean disconnected = false;
    private boolean connected = false;

    public SupabaseRealtimeClient(String baseUrl,
                                  String publishableKey,
                                  String accessToken,
                                  String matchId,
                                  Listener listener) {
        if (isBlank(baseUrl)) throw new IllegalArgumentException("baseUrl is empty");
        if (isBlank(publishableKey)) throw new IllegalArgumentException("publishableKey is empty");
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        if (isBlank(matchId)) throw new IllegalArgumentException("matchId is empty");

        this.publishableKey = publishableKey.trim();
        this.accessToken = accessToken.trim();
        this.matchId = matchId.trim();
        this.listener = listener;
        this.topic = "realtime:public:match_actions";
        this.socketUrl = buildSocketUrl(baseUrl, this.publishableKey);
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void connect() {
        synchronized (lock) {
            if (disconnected || socket != null) return;
            Request request = new Request.Builder()
                    .url(socketUrl)
                    .addHeader("apikey", publishableKey)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();
            socket = client.newWebSocket(request, new SocketListener());
        }
    }

    public void disconnect() {
        synchronized (lock) {
            if (disconnected) return;
            disconnected = true;
            connected = false;
            cancelHeartbeatLocked();
            if (socket != null) {
                socket.close(1000, "client_close");
                socket = null;
            }
        }
        scheduler.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            sendJoin();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            handleDisconnected();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String message = null;
            if (t != null && t.getMessage() != null) {
                message = t.getMessage();
            } else if (response != null) {
                message = "HTTP " + response.code();
            } else {
                message = "Realtime socket failure";
            }
            notifyError(message);
            handleDisconnected();
        }
    }

    private void sendJoin() {
        JSONObject payload = new JSONObject();
        try {
            JSONObject config = new JSONObject();
            config.put("broadcast", new JSONObject().put("ack", false).put("self", false));
            config.put("presence", new JSONObject().put("key", ""));
            config.put("private", false);
            JSONArray postgresChanges = new JSONArray();
            postgresChanges.put(new JSONObject()
                    .put("event", "INSERT")
                    .put("schema", "public")
                    .put("table", "match_actions")
                    .put("filter", "match_id=eq." + matchId));
            config.put("postgres_changes", postgresChanges);
            payload.put("config", config);
            payload.put("access_token", accessToken);
        } catch (JSONException e) {
            notifyError("Realtime join payload error");
            return;
        }
        sendFrame(topic, "phx_join", payload);
    }

    private void handleMessage(String text) {
        if (isBlank(text)) return;
        try {
            JSONObject root = new JSONObject(text);
            String event = root.optString("event", "");
            if ("phx_reply".equals(event)) {
                handleReply(root);
                return;
            }
            if ("phx_error".equals(event)) {
                JSONObject payload = root.optJSONObject("payload");
                if (payload != null) {
                    notifyError(payload.toString());
                }
                return;
            }
            if ("postgres_changes".equals(event)) {
                SupabaseService.MatchAction action = parseMatchAction(root.optJSONObject("payload"));
                if (action != null) {
                    notifyMatchAction(action);
                }
            }
        } catch (Exception e) {
            notifyError("Realtime parse error");
        }
    }

    private void handleReply(JSONObject root) {
        String replyTopic = root.optString("topic", "");
        if (!topic.equals(replyTopic)) return;
        JSONObject payload = root.optJSONObject("payload");
        if (payload == null) return;
        String status = payload.optString("status", "");
        if ("ok".equalsIgnoreCase(status)) {
            boolean notify = false;
            synchronized (lock) {
                if (!connected && !disconnected) {
                    connected = true;
                    notify = true;
                }
                startHeartbeatLocked();
            }
            if (notify && listener != null) {
                notifyConnected();
            }
        } else {
            notifyError("Realtime join rejected");
        }
    }

    private void handleDisconnected() {
        boolean notify = false;
        synchronized (lock) {
            if (socket != null) {
                socket = null;
            }
            cancelHeartbeatLocked();
            if (connected) {
                connected = false;
                if (!disconnected) {
                    notify = true;
                }
            }
        }
        if (notify) {
            notifyDisconnected();
        }
    }

    private void startHeartbeatLocked() {
        cancelHeartbeatLocked();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> sendFrame("phoenix", "heartbeat", new JSONObject()),
                HEARTBEAT_MS,
                HEARTBEAT_MS,
                TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeatLocked() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void sendFrame(String frameTopic, String frameEvent, JSONObject payload) {
        JSONObject frame = new JSONObject();
        try {
            frame.put("topic", frameTopic);
            frame.put("event", frameEvent);
            frame.put("payload", payload != null ? payload : new JSONObject());
            frame.put("ref", String.valueOf(refCounter.getAndIncrement()));
        } catch (JSONException e) {
            return;
        }

        WebSocket ws;
        synchronized (lock) {
            if (disconnected) return;
            ws = socket;
        }
        if (ws == null) return;
        ws.send(frame.toString());
    }

    private SupabaseService.MatchAction parseMatchAction(JSONObject payload) {
        JSONObject row = extractRow(payload);
        if (row == null) return null;

        String rowMatchId = row.optString("match_id", "");
        if (!matchId.equals(rowMatchId)) return null;

        JSONObject actionPayload = row.optJSONObject("payload");
        if (actionPayload == null) {
            String payloadText = row.optString("payload", "");
            if (!isBlank(payloadText)) {
                try {
                    actionPayload = new JSONObject(payloadText);
                } catch (Exception ignored) {
                }
            }
        }
        if (actionPayload == null) actionPayload = new JSONObject();

        return new SupabaseService.MatchAction(
                row.optInt("seq", -1),
                row.optString("actor_user_id", ""),
                row.optString("action_type", ""),
                actionPayload,
                parseIso8601ToEpochMs(row.optString("created_at", ""))
        );
    }

    private JSONObject extractRow(JSONObject payload) {
        if (payload == null) return null;

        JSONObject data = payload.optJSONObject("data");
        if (data != null) {
            JSONObject record = data.optJSONObject("record");
            if (record != null) return record;
            JSONObject newer = data.optJSONObject("new");
            if (newer != null) return newer;
        }

        JSONObject record = payload.optJSONObject("record");
        if (record != null) return record;
        JSONObject newer = payload.optJSONObject("new");
        return newer;
    }

    private String buildSocketUrl(String baseUrl, String apikey) {
        URI uri = URI.create(baseUrl.trim());
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        String host = uri.getHost();
        int port = uri.getPort();
        String encodedKey;
        try {
            encodedKey = URLEncoder.encode(apikey, "UTF-8");
        } catch (Exception e) {
            encodedKey = apikey;
        }
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://").append(host);
        if (port > 0) {
            out.append(":").append(port);
        }
        out.append("/realtime/v1/websocket?apikey=").append(encodedKey).append("&vsn=1.0.0");
        return out.toString();
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private void notifyConnected() {
        if (listener == null) return;
        try {
            listener.onConnected();
        } catch (Throwable ignored) {
        }
    }

    private void notifyDisconnected() {
        if (listener == null) return;
        try {
            listener.onDisconnected();
        } catch (Throwable ignored) {
        }
    }

    private void notifyError(String message) {
        if (listener == null) return;
        try {
            listener.onError(message);
        } catch (Throwable ignored) {
        }
    }

    private void notifyMatchAction(SupabaseService.MatchAction action) {
        if (listener == null || action == null) return;
        try {
            listener.onMatchAction(action);
        } catch (Throwable ignored) {
        }
    }

    private long parseIso8601ToEpochMs(String raw) {
        if (isBlank(raw)) return 0L;
        String value = raw.trim();

        if (value.endsWith("Z")) {
            value = value.substring(0, value.length() - 1) + "+00:00";
        }

        int tIndex = value.indexOf('T');
        int plusIndex = value.lastIndexOf('+');
        int minusIndex = value.lastIndexOf('-');
        int tzIndex = Math.max(plusIndex, minusIndex);
        if (tzIndex <= tIndex) {
            tzIndex = value.length();
        }

        int dotIndex = value.indexOf('.', tIndex);
        if (dotIndex > 0 && dotIndex < tzIndex) {
            String fraction = value.substring(dotIndex + 1, tzIndex);
            if (fraction.length() > 3) {
                fraction = fraction.substring(0, 3);
            } else {
                while (fraction.length() < 3) {
                    fraction = fraction + "0";
                }
            }
            value = value.substring(0, dotIndex + 1) + fraction + value.substring(tzIndex);
        } else if (dotIndex < 0 && tzIndex < value.length()) {
            value = value.substring(0, tzIndex) + ".000" + value.substring(tzIndex);
        }

        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                sdf.setLenient(false);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = sdf.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }
}
