package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResolverStore {

    private static final String KEY_DB = "resolver_status_db_v1";

    public static final String UNKNOWN = "unknown";
    public static final String OK = "ok";
    public static final String FAIL = "fail";

    private ResolverStore() {}

    public static final class Entry {
        public String name;
        public String url;
        public String source;
        public String status = UNKNOWN;
        public long latencyMs;
        public String method = "";
        public String error = "";
        public long checkedAt;

        public String statusPrefix() {
            if (OK.equals(status)) return "✓";
            if (FAIL.equals(status)) return "✗";
            return "?";
        }

        public String displayLine() {
            StringBuilder out = new StringBuilder();
            out.append(statusPrefix()).append(" ").append(name);

            if (OK.equals(status)) {
                out.append(" • ").append(latencyMs).append(" ms");
                if (method != null && !method.isEmpty()) {
                    out.append(" • ").append(method);
                }
            } else if (FAIL.equals(status) && error != null && !error.isEmpty()) {
                out.append(" • ").append(error);
            }

            out.append("\n").append(url);
            return out.toString();
        }
    }

    public static synchronized void seed(
            SharedPreferences prefs,
            String name,
            String url,
            String source
    ) {
        Map<String, Entry> db = load(prefs);

        if (!db.containsKey(url)) {
            Entry e = new Entry();
            e.name = name;
            e.url = url;
            e.source = source;
            db.put(url, e);
            save(prefs, db);
        }
    }

    public static synchronized void rememberDiscovered(
            SharedPreferences prefs,
            String url
    ) {
        Map<String, Entry> db = load(prefs);

        Entry e = db.get(url);
        if (e == null) {
            e = new Entry();
            e.url = url;
            e.name = hostName(url);
            e.source = "catalog";
            db.put(url, e);
            save(prefs, db);
        }
    }

    public static synchronized void saveResult(
            SharedPreferences prefs,
            String url,
            String fallbackName,
            DohClient.Result result
    ) {
        Map<String, Entry> db = load(prefs);

        Entry e = db.get(url);
        if (e == null) {
            e = new Entry();
            e.url = url;
            e.name = fallbackName == null || fallbackName.isEmpty()
                    ? hostName(url)
                    : fallbackName;
            e.source = "custom";
            db.put(url, e);
        }

        e.status = result.ok() ? OK : FAIL;
        e.latencyMs = result.latencyMs;
        e.method = result.method == null ? "" : result.method;
        e.error = result.ok()
                ? ""
                : (result.error == null ? "failed" : result.error);
        e.checkedAt = System.currentTimeMillis();

        save(prefs, db);
    }

    public static synchronized Entry get(SharedPreferences prefs, String url) {
        return load(prefs).get(url);
    }

    public static synchronized List<Entry> all(SharedPreferences prefs) {
        return new ArrayList<>(load(prefs).values());
    }

    public static synchronized List<Entry> working(SharedPreferences prefs) {
        List<Entry> out = new ArrayList<>();

        for (Entry e : load(prefs).values()) {
            if (OK.equals(e.status)) out.add(e);
        }

        out.sort((a, b) -> Long.compare(a.latencyMs, b.latencyMs));
        return out;
    }

    public static synchronized List<Entry> discovered(SharedPreferences prefs) {
        List<Entry> out = new ArrayList<>();

        for (Entry e : load(prefs).values()) {
            if ("catalog".equals(e.source)) out.add(e);
        }

        return out;
    }

    private static Map<String, Entry> load(SharedPreferences prefs) {
        LinkedHashMap<String, Entry> out = new LinkedHashMap<>();

        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_DB, "[]"));

            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);

                Entry e = new Entry();
                e.name = o.optString("name", "");
                e.url = o.optString("url", "");
                e.source = o.optString("source", "custom");
                e.status = o.optString("status", UNKNOWN);
                e.latencyMs = o.optLong("latencyMs", 0);
                e.method = o.optString("method", "");
                e.error = o.optString("error", "");
                e.checkedAt = o.optLong("checkedAt", 0);

                if (!e.url.isEmpty()) out.put(e.url, e);
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    private static void save(SharedPreferences prefs, Map<String, Entry> db) {
        JSONArray array = new JSONArray();

        try {
            for (Entry e : db.values()) {
                JSONObject o = new JSONObject();
                o.put("name", e.name == null ? "" : e.name);
                o.put("url", e.url == null ? "" : e.url);
                o.put("source", e.source == null ? "custom" : e.source);
                o.put("status", e.status == null ? UNKNOWN : e.status);
                o.put("latencyMs", e.latencyMs);
                o.put("method", e.method == null ? "" : e.method);
                o.put("error", e.error == null ? "" : e.error);
                o.put("checkedAt", e.checkedAt);
                array.put(o);
            }
        } catch (Exception ignored) {
        }

        prefs.edit().putString(KEY_DB, array.toString()).apply();
    }

    private static String hostName(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isEmpty() ? url : host;
        } catch (Exception e) {
            return url;
        }
    }
}
