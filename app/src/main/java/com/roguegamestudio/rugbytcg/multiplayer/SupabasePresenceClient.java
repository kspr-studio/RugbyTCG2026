package com.roguegamestudio.rugbytcg.multiplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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

public class SupabasePresenceClient {
    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
        void onOnlineCountChanged(int onlineCount);
    }

    private static final long HEARTBEAT_MS = 15_000L;
    private static final String TOPIC = "realtime:online_players";

    private final String publishableKey;
    private final String accessToken;
    private final String presenceKey;
    private final Listener listener;
    private final String socketUrl;
    private final OkHttpClient client;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger refCounter = new AtomicInteger(1);
    private final Object lock = new Object();
    private final Map<String, Integer> onlineMembers = new HashMap<>();

    private WebSocket socket;
    private ScheduledFuture<?> heartbeatFuture;
    private boolean disconnected = false;
    private boolean connected = false;
    private int lastEmittedOnlineCount = -1;

    public SupabasePresenceClient(String baseUrl,
                                  String publishableKey,
                                  String accessToken,
                                  String presenceKey,
                                  Listener listener) {
        if (isBlank(baseUrl)) throw new IllegalArgumentException("baseUrl is empty");
        if (isBlank(publishableKey)) throw new IllegalArgumentException("publishableKey is empty");
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        if (isBlank(presenceKey)) throw new IllegalArgumentException("presenceKey is empty");

        this.publishableKey = publishableKey.trim();
        this.accessToken = accessToken.trim();
        this.presenceKey = presenceKey.trim();
        this.listener = listener;
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
            onlineMembers.clear();
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
            if (listener != null) {
                String message;
                if (t != null && t.getMessage() != null) {
                    message = t.getMessage();
                } else if (response != null) {
                    message = "HTTP " + response.code();
                } else {
                    message = "Realtime socket failure";
                }
                listener.onError(message);
            }
            handleDisconnected();
        }
    }

    private void sendJoin() {
        JSONObject payload = new JSONObject();
        try {
            JSONObject config = new JSONObject();
            config.put("broadcast", new JSONObject().put("ack", false).put("self", false));
            config.put("presence", new JSONObject().put("key", presenceKey));
            config.put("private", false);
            config.put("postgres_changes", new JSONArray());
            payload.put("config", config);
            payload.put("access_token", accessToken);
        } catch (JSONException e) {
            if (listener != null) {
                listener.onError("Realtime join payload error");
            }
            return;
        }
        sendFrame(TOPIC, "phx_join", payload);
    }

    private void sendPresenceTrack() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "presence");
            payload.put("event", "track");
            payload.put("payload", new JSONObject()
                    .put("user_id", presenceKey)
                    .put("status", "online")
                    .put("ts", System.currentTimeMillis()));
        } catch (JSONException e) {
            return;
        }
        sendFrame(TOPIC, "presence", payload);
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
                if (listener != null && payload != null) {
                    listener.onError(payload.toString());
                }
                return;
            }
            if ("presence_state".equals(event)) {
                applyPresenceState(root.optJSONObject("payload"));
                return;
            }
            if ("presence_diff".equals(event)) {
                applyPresenceDiff(root.optJSONObject("payload"));
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("Realtime parse error");
            }
        }
    }

    private void handleReply(JSONObject root) {
        String replyTopic = root.optString("topic", "");
        if (!TOPIC.equals(replyTopic)) return;

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
            sendPresenceTrack();
            if (notify && listener != null) {
                listener.onConnected();
            }
        } else if (listener != null) {
            listener.onError("Realtime join rejected");
        }
    }

    private void applyPresenceState(JSONObject payload) {
        synchronized (lock) {
            onlineMembers.clear();
            mergePresenceMapLocked(payload, false);
            notifyOnlineCountIfChangedLocked();
        }
    }

    private void applyPresenceDiff(JSONObject payload) {
        synchronized (lock) {
            if (payload == null) return;
            mergePresenceMapLocked(payload.optJSONObject("joins"), false);
            mergePresenceMapLocked(payload.optJSONObject("leaves"), true);
            notifyOnlineCountIfChangedLocked();
        }
    }

    private void mergePresenceMapLocked(JSONObject map, boolean subtract) {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isBlank(key)) continue;
            JSONObject entry = map.optJSONObject(key);
            if (entry == null) continue;
            JSONArray metas = entry.optJSONArray("metas");
            int delta = metas == null ? 0 : metas.length();
            if (delta <= 0) continue;

            int existing = onlineMembers.containsKey(key) ? onlineMembers.get(key) : 0;
            int next = subtract ? existing - delta : existing + delta;
            if (next <= 0) {
                onlineMembers.remove(key);
            } else {
                onlineMembers.put(key, next);
            }
        }
    }

    private void notifyOnlineCountIfChangedLocked() {
        int nowOnline = onlineMembers.size();
        if (nowOnline == lastEmittedOnlineCount) return;
        lastEmittedOnlineCount = nowOnline;
        if (listener != null) {
            listener.onOnlineCountChanged(nowOnline);
        }
    }

    private void handleDisconnected() {
        boolean notify = false;
        synchronized (lock) {
            if (socket != null) {
                socket = null;
            }
            cancelHeartbeatLocked();
            onlineMembers.clear();
            lastEmittedOnlineCount = -1;
            if (connected) {
                connected = false;
                if (!disconnected) {
                    notify = true;
                }
            }
        }
        if (notify && listener != null) {
            listener.onDisconnected();
        }
    }

    private void startHeartbeatLocked() {
        cancelHeartbeatLocked();
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                () -> sendFrame("phoenix", "heartbeat", new JSONObject()),
                HEARTBEAT_MS,
                HEARTBEAT_MS,
                TimeUnit.MILLISECONDS
        );
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
}
