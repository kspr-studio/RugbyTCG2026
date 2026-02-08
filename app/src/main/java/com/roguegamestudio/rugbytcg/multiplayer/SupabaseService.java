package com.roguegamestudio.rugbytcg.multiplayer;

import android.net.Uri;

import com.roguegamestudio.rugbytcg.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class SupabaseService {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    public static final int MULTIPLAYER_PROTOCOL_VERSION = BuildConfig.VERSION_CODE;

    private final String baseUrl;
    private final String publishableKey;

    public SupabaseService(String baseUrl, String publishableKey) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing SUPABASE_URL");
        }
        if (publishableKey == null || publishableKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing SUPABASE_PUBLISHABLE_KEY");
        }
        String trimmedBase = baseUrl.trim();
        this.baseUrl = trimmedBase.endsWith("/") ? trimmedBase.substring(0, trimmedBase.length() - 1) : trimmedBase;
        this.publishableKey = publishableKey.trim();
    }

    public VersionStatus fetchClientVersionStatusPublic(int clientVersionCode) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("p_client_version", clientVersionCode);
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/get_client_version_status_v1", body, null);
        if (rows.length() <= 0) {
            throw new IOException("get_client_version_status_v1 returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new VersionStatus(
                row.optBoolean("accepted", false),
                row.optString("reason", ""),
                row.optInt("latest_client_version", 0),
                row.optInt("min_supported_client_version", 0),
                row.optBoolean("up_to_date", false)
        );
    }

    public SupabaseSession signInAnonymously() throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("data", new JSONObject());
        JSONObject root = postJson(baseUrl + "/auth/v1/signup", body, null);
        return parseSession(root);
    }

    public SupabaseSession refreshSession(String refreshToken) throws IOException, JSONException {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new IllegalArgumentException("refreshToken is empty");
        }
        JSONObject body = new JSONObject();
        body.put("refresh_token", refreshToken);
        JSONObject root = postJson(baseUrl + "/auth/v1/token?grant_type=refresh_token", body, null);
        return parseSession(root);
    }

    public SupabaseProfile fetchProfile(String accessToken, String userId) throws IOException, JSONException {
        Uri uri = Uri.parse(baseUrl + "/rest/v1/profiles").buildUpon()
                .appendQueryParameter("user_id", "eq." + userId)
                .appendQueryParameter("select", "user_id,public_id,username,display_name,is_guest")
                .build();
        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        if (rows.length() <= 0) {
            throw new IOException("No profile row found for user");
        }
        JSONObject row = rows.getJSONObject(0);
        return new SupabaseProfile(
                row.optString("user_id", ""),
                row.optString("public_id", ""),
                row.optString("username", ""),
                row.optString("display_name", ""),
                row.optBoolean("is_guest", true)
        );
    }

    public SendChallengeResult sendChallenge(String accessToken, String target) throws IOException, JSONException {
        if (isBlank(accessToken)) {
            throw new IllegalArgumentException("accessToken is empty");
        }
        if (isBlank(target)) {
            throw new IllegalArgumentException("target is empty");
        }
        JSONObject body = new JSONObject();
        body.put("p_target", target.trim());
        body.put("p_mode", "casual");
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/send_challenge", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("send_challenge returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new SendChallengeResult(
                row.optString("challenge_id", ""),
                row.optString("expires_at", "")
        );
    }

    public IncomingChallenge fetchLatestIncomingChallenge(String accessToken, String userId)
            throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(userId)) return null;

        Uri uri = Uri.parse(baseUrl + "/rest/v1/challenge_requests").buildUpon()
                .appendQueryParameter("to_user_id", "eq." + userId)
                .appendQueryParameter("status", "eq.pending")
                .appendQueryParameter("select", "id,from_user_id,expires_at,mode,created_at")
                .appendQueryParameter("order", "created_at.desc")
                .appendQueryParameter("limit", "1")
                .build();

        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        if (rows.length() <= 0) return null;

        JSONObject row = rows.getJSONObject(0);
        String fromUserId = row.optString("from_user_id", "");
        if (isBlank(fromUserId)) return null;

        SupabaseProfile fromProfile = fetchProfile(accessToken, fromUserId);
        return new IncomingChallenge(
                row.optString("id", ""),
                fromUserId,
                fromProfile.publicId,
                fromProfile.username,
                fromProfile.displayName,
                row.optString("expires_at", ""),
                row.optString("mode", "casual")
        );
    }

    public RespondChallengeResult respondChallenge(String accessToken, String challengeId, boolean accept)
            throws IOException, JSONException {
        if (isBlank(accessToken)) {
            throw new IllegalArgumentException("accessToken is empty");
        }
        if (isBlank(challengeId)) {
            throw new IllegalArgumentException("challengeId is empty");
        }
        JSONObject body = new JSONObject();
        body.put("p_challenge_id", challengeId);
        body.put("p_accept", accept);
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/respond_challenge", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("respond_challenge returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new RespondChallengeResult(
                row.optString("status", ""),
                row.optString("match_id", "")
        );
    }

    public ActiveMatch fetchActiveMatch(String accessToken, String userId) throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(userId)) return null;

        Uri uri = Uri.parse(baseUrl + "/rest/v1/matches").buildUpon()
                .appendQueryParameter("or", "(player_a.eq." + userId + ",player_b.eq." + userId + ")")
                .appendQueryParameter("status", "in.(pending,active)")
                .appendQueryParameter("select", "id,status,player_a,player_b,created_at")
                .appendQueryParameter("order", "created_at.desc")
                .appendQueryParameter("limit", "1")
                .build();

        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        if (rows.length() <= 0) return null;

        JSONObject row = rows.getJSONObject(0);
        String playerA = row.optString("player_a", "");
        String playerB = row.optString("player_b", "");
        String opponentUserId = userId.equals(playerA) ? playerB : playerA;
        SupabaseProfile opponent = fetchProfile(accessToken, opponentUserId);

        return new ActiveMatch(
                row.optString("id", ""),
                row.optString("status", ""),
                opponentUserId,
                opponent.publicId,
                opponent.username,
                opponent.displayName
        );
    }

    public ForfeitMatchResult forfeitMyActiveMatch(String accessToken) throws IOException, JSONException {
        if (isBlank(accessToken)) {
            throw new IllegalArgumentException("accessToken is empty");
        }
        JSONObject body = new JSONObject();
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/forfeit_my_active_match", body, accessToken);
        if (rows.length() <= 0) {
            return new ForfeitMatchResult("", "none");
        }
        JSONObject row = rows.getJSONObject(0);
        return new ForfeitMatchResult(
                row.optString("match_id", ""),
                row.optString("status", "")
        );
    }

    public SubmitActionResult submitMatchAction(String accessToken,
                                                String matchId,
                                                String actionType,
                                                JSONObject payload)
            throws IOException, JSONException {
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        if (isBlank(matchId)) throw new IllegalArgumentException("matchId is empty");
        if (isBlank(actionType)) throw new IllegalArgumentException("actionType is empty");

        JSONObject body = new JSONObject();
        body.put("p_match_id", matchId);
        body.put("p_action_type", actionType);
        body.put("p_payload", payload != null ? payload : new JSONObject());

        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/submit_match_action", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("submit_match_action returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new SubmitActionResult(
                row.optBoolean("accepted", false),
                row.optInt("seq", -1),
                row.optString("reason", "")
        );
    }

    public JoinMatchResult joinMatchV2(String accessToken, String matchId)
            throws IOException, JSONException {
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        if (isBlank(matchId)) throw new IllegalArgumentException("matchId is empty");

        JSONObject body = new JSONObject();
        body.put("p_match_id", matchId);
        body.put("p_client_version", MULTIPLAYER_PROTOCOL_VERSION);

        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/join_match_v2", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("join_match_v2 returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new JoinMatchResult(
                row.optBoolean("accepted", false),
                row.optString("reason", ""),
                row.optString("match_id", matchId),
                row.optString("status", ""),
                row.optString("player_a", ""),
                row.optString("player_b", ""),
                row.optString("your_user_id", ""),
                row.optInt("protocol_version", 0),
                row.optInt("last_seq", -1),
                row.optString("turn_owner", ""),
                row.optLong("turn_remaining_ms", -1L),
                row.optLong("match_elapsed_ms", -1L),
                row.optBoolean("awaiting_rekickoff", false),
                row.optInt("kickoff_generation", 0),
                parseJsonPayload(row, "canonical_state"),
                row.optBoolean("local_kickoff_ready", false),
                row.optBoolean("remote_kickoff_ready", false)
        );
    }

    public SubmitActionV2Result submitMatchActionV2(String accessToken,
                                                    String matchId,
                                                    String actionType,
                                                    JSONObject payload,
                                                    Integer expectedSeq)
            throws IOException, JSONException {
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        if (isBlank(matchId)) throw new IllegalArgumentException("matchId is empty");
        if (isBlank(actionType)) throw new IllegalArgumentException("actionType is empty");

        JSONObject body = new JSONObject();
        body.put("p_match_id", matchId);
        body.put("p_action_type", actionType);
        body.put("p_payload", payload != null ? payload : new JSONObject());
        if (expectedSeq == null) {
            body.put("p_expected_seq", JSONObject.NULL);
        } else {
            body.put("p_expected_seq", expectedSeq);
        }
        body.put("p_client_version", MULTIPLAYER_PROTOCOL_VERSION);

        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/submit_match_action_v2", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("submit_match_action_v2 returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new SubmitActionV2Result(
                row.optBoolean("accepted", false),
                row.optInt("seq", -1),
                row.optString("reason", ""),
                row.optInt("last_seq", -1),
                row.optString("turn_owner", ""),
                row.optLong("turn_remaining_ms", -1L),
                row.optLong("match_elapsed_ms", -1L),
                row.optBoolean("awaiting_rekickoff", false),
                row.optInt("kickoff_generation", 0),
                parseJsonPayload(row, "canonical_state")
        );
    }

    public MatchActionPage fetchMatchActionsSinceV2(String accessToken,
                                                    String matchId,
                                                    int afterSeq,
                                                    int pageSize)
            throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(matchId)) return new MatchActionPage(new ArrayList<>(), false);

        JSONObject body = new JSONObject();
        body.put("p_match_id", matchId);
        body.put("p_after_seq", Math.max(-1, afterSeq));
        body.put("p_page_size", Math.max(1, Math.min(pageSize, 500)));
        body.put("p_client_version", MULTIPLAYER_PROTOCOL_VERSION);

        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/fetch_actions_since_v2", body, accessToken);
        List<MatchAction> actions = parseMatchActions(rows);
        boolean hasMore = false;
        if (rows != null && rows.length() > 0) {
            JSONObject last = rows.optJSONObject(rows.length() - 1);
            if (last != null) {
                hasMore = last.optBoolean("has_more", false);
            }
        }
        return new MatchActionPage(actions, hasMore);
    }

    public PresenceHeartbeatResult heartbeatPresenceV2(String accessToken,
                                                       String matchId,
                                                       boolean appActive)
            throws IOException, JSONException {
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");

        JSONObject body = new JSONObject();
        if (isBlank(matchId)) {
            body.put("p_match_id", JSONObject.NULL);
        } else {
            body.put("p_match_id", matchId);
        }
        body.put("p_app_active", appActive);
        body.put("p_client_version", MULTIPLAYER_PROTOCOL_VERSION);
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/heartbeat_presence_v2", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("heartbeat_presence_v2 returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new PresenceHeartbeatResult(
                row.optBoolean("accepted", false),
                row.optString("reason", ""),
                row.optInt("online_count", -1),
                row.optBoolean("awaiting_rekickoff", false),
                row.optInt("kickoff_generation", 0)
        );
    }

    public OnlineCountResult fetchOnlineCountV2(String accessToken)
            throws IOException, JSONException {
        if (isBlank(accessToken)) throw new IllegalArgumentException("accessToken is empty");
        JSONObject body = new JSONObject();
        body.put("p_client_version", MULTIPLAYER_PROTOCOL_VERSION);
        JSONArray rows = postJsonArray(baseUrl + "/rest/v1/rpc/get_online_count_v2", body, accessToken);
        if (rows.length() <= 0) {
            throw new IOException("get_online_count_v2 returned no rows");
        }
        JSONObject row = rows.getJSONObject(0);
        return new OnlineCountResult(
                row.optBoolean("accepted", false),
                row.optString("reason", ""),
                row.optInt("online_count", -1)
        );
    }

    public int countDistinctReadyPlayers(String accessToken, String matchId) throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(matchId)) return 0;

        Uri uri = Uri.parse(baseUrl + "/rest/v1/match_actions").buildUpon()
                .appendQueryParameter("match_id", "eq." + matchId)
                .appendQueryParameter("action_type", "eq.match_ready")
                .appendQueryParameter("select", "actor_user_id")
                .appendQueryParameter("order", "seq.asc")
                .build();
        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String actorId = row.optString("actor_user_id", "");
            if (!isBlank(actorId)) distinct.add(actorId);
        }
        return distinct.size();
    }

    public List<MatchAction> fetchReadyActions(String accessToken, String matchId) throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(matchId)) return new ArrayList<>();
        Uri uri = Uri.parse(baseUrl + "/rest/v1/match_actions").buildUpon()
                .appendQueryParameter("match_id", "eq." + matchId)
                .appendQueryParameter("action_type", "eq.match_ready")
                .appendQueryParameter("select", "seq,actor_user_id,action_type,payload,created_at")
                .appendQueryParameter("order", "seq.asc")
                .build();
        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        return parseMatchActions(rows);
    }

    public List<MatchAction> fetchMatchActionsSince(String accessToken, String matchId, int afterSeq)
            throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(matchId)) return new ArrayList<>();
        int normalizedAfter = Math.max(-1, afterSeq);
        Uri uri = Uri.parse(baseUrl + "/rest/v1/match_actions").buildUpon()
                .appendQueryParameter("match_id", "eq." + matchId)
                .appendQueryParameter("seq", "gt." + normalizedAfter)
                .appendQueryParameter("select", "seq,actor_user_id,action_type,payload,created_at")
                .appendQueryParameter("order", "seq.asc")
                .appendQueryParameter("limit", "200")
                .build();
        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        return parseMatchActions(rows);
    }

    public MatchSnapshot fetchMatchSnapshot(String accessToken, String matchId) throws IOException, JSONException {
        if (isBlank(accessToken) || isBlank(matchId)) return null;
        Uri uri = Uri.parse(baseUrl + "/rest/v1/matches").buildUpon()
                .appendQueryParameter("id", "eq." + matchId)
                .appendQueryParameter("select", "id,status,player_a,player_b,winner_user_id")
                .appendQueryParameter("limit", "1")
                .build();
        JSONArray rows = getJsonArray(uri.toString(), accessToken);
        if (rows.length() <= 0) return null;
        JSONObject row = rows.getJSONObject(0);
        return new MatchSnapshot(
                row.optString("id", ""),
                row.optString("status", ""),
                row.optString("player_a", ""),
                row.optString("player_b", ""),
                row.optString("winner_user_id", "")
        );
    }

    private SupabaseSession parseSession(JSONObject root) throws JSONException, IOException {
        JSONObject user = root.optJSONObject("user");
        JSONObject session = root.optJSONObject("session");
        JSONObject tokenSource = (session != null) ? session : root;

        String accessToken = tokenSource.optString("access_token", "");
        String refreshToken = tokenSource.optString("refresh_token", "");
        long expiresAt = tokenSource.optLong("expires_at", 0L);
        long expiresIn = tokenSource.optLong("expires_in", 0L);
        if (expiresAt <= 0L && expiresIn > 0L) {
            expiresAt = (System.currentTimeMillis() / 1000L) + expiresIn;
        }
        String userId = user != null ? user.optString("id", "") : "";

        if (isBlank(accessToken) || isBlank(refreshToken) || isBlank(userId)) {
            throw new IOException("Supabase auth response missing session fields: " + root.toString());
        }
        return new SupabaseSession(accessToken, refreshToken, expiresAt, userId);
    }

    private JSONObject postJson(String endpoint, JSONObject body, String bearerToken)
            throws IOException, JSONException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("apikey", publishableKey);
            if (!isBlank(bearerToken)) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (BufferedOutputStream os = new BufferedOutputStream(conn.getOutputStream())) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            String responseBody = readBody(conn, code);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " on " + endpoint + ": " + responseBody);
            }
            if (responseBody == null || responseBody.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(responseBody);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray postJsonArray(String endpoint, JSONObject body, String bearerToken)
            throws IOException, JSONException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("apikey", publishableKey);
            if (!isBlank(bearerToken)) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (BufferedOutputStream os = new BufferedOutputStream(conn.getOutputStream())) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            String responseBody = readBody(conn, code);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " on " + endpoint + ": " + responseBody);
            }
            if (responseBody == null || responseBody.trim().isEmpty()) {
                return new JSONArray();
            }
            return new JSONArray(responseBody);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray getJsonArray(String endpoint, String bearerToken) throws IOException, JSONException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("apikey", publishableKey);
            if (!isBlank(bearerToken)) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            int code = conn.getResponseCode();
            String responseBody = readBody(conn, code);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " on " + endpoint + ": " + responseBody);
            }
            if (responseBody == null || responseBody.trim().isEmpty()) {
                return new JSONArray();
            }
            return new JSONArray(responseBody);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readBody(HttpURLConnection conn, int code) throws IOException {
        BufferedInputStream is = null;
        try {
            if (code >= 200 && code < 300) {
                if (conn.getInputStream() == null) return "";
                is = new BufferedInputStream(conn.getInputStream());
            } else {
                if (conn.getErrorStream() == null) return "";
                is = new BufferedInputStream(conn.getErrorStream());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (is != null) is.close();
        }
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private JSONObject parseJsonPayload(JSONObject row, String key) {
        if (row == null || isBlank(key)) return new JSONObject();
        JSONObject payload = row.optJSONObject(key);
        if (payload != null) return payload;
        String text = row.optString(key, "");
        if (isBlank(text)) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private List<MatchAction> parseMatchActions(JSONArray rows) {
        List<MatchAction> out = new ArrayList<>();
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject payload = row.optJSONObject("payload");
            if (payload == null) {
                String payloadText = row.optString("payload", "");
                if (!isBlank(payloadText)) {
                    try {
                        payload = new JSONObject(payloadText);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (payload == null) {
                payload = new JSONObject();
            }
            out.add(new MatchAction(
                    row.optInt("seq", -1),
                    row.optString("actor_user_id", ""),
                    row.optString("action_type", ""),
                    payload,
                    parseIso8601ToEpochMs(row.optString("created_at", ""))
            ));
        }
        return out;
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

    public static class SendChallengeResult {
        public final String challengeId;
        public final String expiresAt;

        public SendChallengeResult(String challengeId, String expiresAt) {
            this.challengeId = challengeId;
            this.expiresAt = expiresAt;
        }
    }

    public static class IncomingChallenge {
        public final String challengeId;
        public final String fromUserId;
        public final String fromPublicId;
        public final String fromUsername;
        public final String fromDisplayName;
        public final String expiresAt;
        public final String mode;

        public IncomingChallenge(String challengeId,
                                 String fromUserId,
                                 String fromPublicId,
                                 String fromUsername,
                                 String fromDisplayName,
                                 String expiresAt,
                                 String mode) {
            this.challengeId = challengeId;
            this.fromUserId = fromUserId;
            this.fromPublicId = fromPublicId;
            this.fromUsername = fromUsername;
            this.fromDisplayName = fromDisplayName;
            this.expiresAt = expiresAt;
            this.mode = mode;
        }
    }

    public static class RespondChallengeResult {
        public final String status;
        public final String matchId;

        public RespondChallengeResult(String status, String matchId) {
            this.status = status;
            this.matchId = matchId;
        }
    }

    public static class ActiveMatch {
        public final String matchId;
        public final String status;
        public final String opponentUserId;
        public final String opponentPublicId;
        public final String opponentUsername;
        public final String opponentDisplayName;

        public ActiveMatch(String matchId,
                           String status,
                           String opponentUserId,
                           String opponentPublicId,
                           String opponentUsername,
                           String opponentDisplayName) {
            this.matchId = matchId;
            this.status = status;
            this.opponentUserId = opponentUserId;
            this.opponentPublicId = opponentPublicId;
            this.opponentUsername = opponentUsername;
            this.opponentDisplayName = opponentDisplayName;
        }
    }

    public static class ForfeitMatchResult {
        public final String matchId;
        public final String status;

        public ForfeitMatchResult(String matchId, String status) {
            this.matchId = matchId;
            this.status = status;
        }
    }

    public static class SubmitActionResult {
        public final boolean accepted;
        public final int seq;
        public final String reason;

        public SubmitActionResult(boolean accepted, int seq, String reason) {
            this.accepted = accepted;
            this.seq = seq;
            this.reason = reason;
        }
    }

    public static class SubmitActionV2Result {
        public final boolean accepted;
        public final int seq;
        public final String reason;
        public final int lastSeq;
        public final String turnOwner;
        public final long turnRemainingMs;
        public final long matchElapsedMs;
        public final boolean awaitingRekickoff;
        public final int kickoffGeneration;
        public final JSONObject canonicalState;

        public SubmitActionV2Result(boolean accepted,
                                    int seq,
                                    String reason,
                                    int lastSeq,
                                    String turnOwner,
                                    long turnRemainingMs,
                                    long matchElapsedMs,
                                    boolean awaitingRekickoff,
                                    int kickoffGeneration,
                                    JSONObject canonicalState) {
            this.accepted = accepted;
            this.seq = seq;
            this.reason = reason;
            this.lastSeq = lastSeq;
            this.turnOwner = turnOwner;
            this.turnRemainingMs = turnRemainingMs;
            this.matchElapsedMs = matchElapsedMs;
            this.awaitingRekickoff = awaitingRekickoff;
            this.kickoffGeneration = kickoffGeneration;
            this.canonicalState = canonicalState != null ? canonicalState : new JSONObject();
        }
    }

    public static class JoinMatchResult {
        public final boolean accepted;
        public final String reason;
        public final String matchId;
        public final String status;
        public final String playerA;
        public final String playerB;
        public final String yourUserId;
        public final int protocolVersion;
        public final int lastSeq;
        public final String turnOwner;
        public final long turnRemainingMs;
        public final long matchElapsedMs;
        public final boolean awaitingRekickoff;
        public final int kickoffGeneration;
        public final JSONObject canonicalState;
        public final boolean localKickoffReady;
        public final boolean remoteKickoffReady;

        public JoinMatchResult(boolean accepted,
                               String reason,
                               String matchId,
                               String status,
                               String playerA,
                               String playerB,
                               String yourUserId,
                               int protocolVersion,
                               int lastSeq,
                               String turnOwner,
                               long turnRemainingMs,
                               long matchElapsedMs,
                               boolean awaitingRekickoff,
                               int kickoffGeneration,
                               JSONObject canonicalState,
                               boolean localKickoffReady,
                               boolean remoteKickoffReady) {
            this.accepted = accepted;
            this.reason = reason;
            this.matchId = matchId;
            this.status = status;
            this.playerA = playerA;
            this.playerB = playerB;
            this.yourUserId = yourUserId;
            this.protocolVersion = protocolVersion;
            this.lastSeq = lastSeq;
            this.turnOwner = turnOwner;
            this.turnRemainingMs = turnRemainingMs;
            this.matchElapsedMs = matchElapsedMs;
            this.awaitingRekickoff = awaitingRekickoff;
            this.kickoffGeneration = kickoffGeneration;
            this.canonicalState = canonicalState != null ? canonicalState : new JSONObject();
            this.localKickoffReady = localKickoffReady;
            this.remoteKickoffReady = remoteKickoffReady;
        }
    }

    public static class MatchActionPage {
        public final List<MatchAction> actions;
        public final boolean hasMore;

        public MatchActionPage(List<MatchAction> actions, boolean hasMore) {
            this.actions = actions != null ? actions : new ArrayList<>();
            this.hasMore = hasMore;
        }
    }

    public static class PresenceHeartbeatResult {
        public final boolean accepted;
        public final String reason;
        public final int onlineCount;
        public final boolean awaitingRekickoff;
        public final int kickoffGeneration;

        public PresenceHeartbeatResult(boolean accepted,
                                       String reason,
                                       int onlineCount,
                                       boolean awaitingRekickoff,
                                       int kickoffGeneration) {
            this.accepted = accepted;
            this.reason = reason;
            this.onlineCount = onlineCount;
            this.awaitingRekickoff = awaitingRekickoff;
            this.kickoffGeneration = kickoffGeneration;
        }
    }

    public static class OnlineCountResult {
        public final boolean accepted;
        public final String reason;
        public final int onlineCount;

        public OnlineCountResult(boolean accepted, String reason, int onlineCount) {
            this.accepted = accepted;
            this.reason = reason;
            this.onlineCount = onlineCount;
        }
    }

    public static class VersionStatus {
        public final boolean accepted;
        public final String reason;
        public final int latestClientVersion;
        public final int minSupportedClientVersion;
        public final boolean upToDate;

        public VersionStatus(boolean accepted,
                             String reason,
                             int latestClientVersion,
                             int minSupportedClientVersion,
                             boolean upToDate) {
            this.accepted = accepted;
            this.reason = reason;
            this.latestClientVersion = latestClientVersion;
            this.minSupportedClientVersion = minSupportedClientVersion;
            this.upToDate = upToDate;
        }
    }

    public static class MatchAction {
        public final int seq;
        public final String actorUserId;
        public final String actionType;
        public final JSONObject payload;
        public final long createdAtEpochMs;

        public MatchAction(int seq, String actorUserId, String actionType, JSONObject payload, long createdAtEpochMs) {
            this.seq = seq;
            this.actorUserId = actorUserId;
            this.actionType = actionType;
            this.payload = payload != null ? payload : new JSONObject();
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    public static class MatchSnapshot {
        public final String matchId;
        public final String status;
        public final String playerA;
        public final String playerB;
        public final String winnerUserId;

        public MatchSnapshot(String matchId, String status, String playerA, String playerB, String winnerUserId) {
            this.matchId = matchId;
            this.status = status;
            this.playerA = playerA;
            this.playerB = playerB;
            this.winnerUserId = winnerUserId;
        }
    }
}
