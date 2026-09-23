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

    private static final String KEY_DB = "resolver_status_db_v2";

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
        public int attempts;
        public int successes;

        public String statusPrefix() {
            if (OK.equals(status)) return "✓";
            if (FAIL.equals(status)) return "✗";
            return "?";
        }

        public String displayLine() {
            StringBuilder out = new StringBuilder();

            out.append(statusPrefix())
                    .append(" ")
                    .append(name == null || name.isEmpty() ? url : name);

            if (OK.equals(status)) {
                out.append(" • ")
                        .append(latencyMs)
                        .append(" ms");

                if (attempts > 0) {
                    out.append(" • ")
                            .append(successes)
                            .append("/")
                            .append(attempts);
                }

                if (method != null && !method.isEmpty()) {
                    out.append(" • ")
                            .append(method);
                }
            } else if (FAIL.equals(status)) {
                if (attempts > 0) {
                    out.append(" • ")
                            .append(successes)
                            .append("/")
                            .append(attempts);
                }

                if (error != null && !error.isEmpty()) {
                    out.append(" • ")
                            .append(error);
                }
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
        Entry e = getOrCreate(db, url, fallbackName);

        e.status = result.ok() ? OK : FAIL;
        e.latencyMs = result.latencyMs;
        e.method = result.method == null ? "" : result.method;
        e.error = result.ok()
                ? ""
                : (result.error == null ? "failed" : result.error);
        e.checkedAt = System.currentTimeMillis();
        e.attempts = 1;
        e.successes = result.ok() ? 1 : 0;

        save(prefs, db);
    }

    public static synchronized void saveBenchmark(
            SharedPreferences prefs,
            String url,
            String fallbackName,
            int attempts,
            int successes,
            long medianLatencyMs,
            String method,
            String error
    ) {
        Map<String, Entry> db = load(prefs);
        Entry e = getOrCreate(db, url, fallbackName);

        e.attempts = Math.max(0, attempts);
        e.successes = Math.max(0, successes);
        e.latencyMs = Math.max(0, medianLatencyMs);
        e.method = method == null ? "" : method;
        e.error = error == null ? "" : error;
        e.checkedAt = System.currentTimeMillis();

        // At least two successful probes out of three is a stable resolver.
        e.status = successes >= Math.max(1, (attempts + 1) / 2)
                ? OK
                : FAIL;

        save(prefs, db);
    }

    public static synchronized Entry get(
            SharedPreferences prefs,
            String url
    ) {
        return load(prefs).get(url);
    }

    public static synchronized void resetMeasurements(
            SharedPreferences prefs
    ) {
        Map<String, Entry> db =
                load(prefs);

        for (Entry e : db.values()) {
            e.status = UNKNOWN;
            e.latencyMs = 0;
            e.method = "";
            e.error = "";
            e.checkedAt = 0;
            e.attempts = 0;
            e.successes = 0;
        }

        save(
                prefs,
                db
        );
    }

    public static synchronized List<Entry> all(
            SharedPreferences prefs
    ) {
        return new ArrayList<>(load(prefs).values());
    }

    public static synchronized List<Entry> working(
            SharedPreferences prefs
    ) {
        List<Entry> out = new ArrayList<>();

        for (Entry e : load(prefs).values()) {
            if (OK.equals(e.status)) out.add(e);
        }

        out.sort((a, b) -> {
            int c = Long.compare(a.latencyMs, b.latencyMs);
            if (c != 0) return c;
            return Integer.compare(b.successes, a.successes);
        });

        return out;
    }

    public static synchronized List<Entry> discovered(
            SharedPreferences prefs
    ) {
        List<Entry> out = new ArrayList<>();

        for (Entry e : load(prefs).values()) {
            if ("catalog".equals(e.source)) out.add(e);
        }

        return out;
    }

    private static Entry getOrCreate(
            Map<String, Entry> db,
            String url,
            String fallbackName
    ) {
        Entry e = db.get(url);

        if (e == null) {
            e = new Entry();
            e.url = url;
            e.name = fallbackName == null || fallbackName.isEmpty()
                    ? hostName(url)
                    : fallbackName;
            e.source = "custom";
            db.put(url, e);
        } else if ((e.name == null || e.name.isEmpty())
                && fallbackName != null
                && !fallbackName.isEmpty()) {
            e.name = fallbackName;
        }

        return e;
    }

    private static Map<String, Entry> load(
            SharedPreferences prefs
    ) {
        LinkedHashMap<String, Entry> out =
                new LinkedHashMap<>();

        try {
            JSONArray array = new JSONArray(
                    prefs.getString(KEY_DB, "[]")
            );

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
                e.attempts = o.optInt("attempts", 0);
                e.successes = o.optInt("successes", 0);

                if (!e.url.isEmpty()) {
                    out.put(e.url, e);
                }
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    private static void save(
            SharedPreferences prefs,
            Map<String, Entry> db
    ) {
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
                o.put("attempts", e.attempts);
                o.put("successes", e.successes);

                array.put(o);
            }
        } catch (Exception ignored) {
        }

        prefs.edit()
                .putString(KEY_DB, array.toString())
                .apply();
    }

    private static String hostName(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isEmpty()
                    ? url
                    : host;
        } catch (Exception e) {
            return url;
        }
    }
}
