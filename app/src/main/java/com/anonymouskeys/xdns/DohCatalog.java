package com.anonymouskeys.xdns;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DohCatalog {

    // curl's public DoH wiki is a maintained, public list.
    public static final String CURL_WIKI_RAW =
            "https://raw.githubusercontent.com/wiki/curl/curl/DNS-over-HTTPS.md";

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build();

    private static final Pattern URL =
            Pattern.compile("https://[A-Za-z0-9._~-]+(?::[0-9]+)?(?:/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*)?");

    private DohCatalog() {}

    public static List<String> fetchPublicDohUrls() throws Exception {
        Request request = new Request.Builder()
                .url(CURL_WIKI_RAW)
                .header("User-Agent", "X-dns/0.2.1")
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Catalog HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("Empty catalog");

            String markdown = body.string();
            Set<String> unique = new LinkedHashSet<>();

            Matcher matcher = URL.matcher(markdown);
            while (matcher.find()) {
                String url = cleanup(matcher.group());

                // Keep likely DoH endpoints. False positives are still harmless
                // because the app tests an endpoint before it is selected.
                String low = url.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("/dns-query")
                        || low.contains("/doh/")
                        || low.contains("freedns.controld.com/")) {
                    unique.add(url);
                }
            }

            return new ArrayList<>(unique);
        }
    }

    private static String cleanup(String url) {
        while (url.endsWith(".")
                || url.endsWith(",")
                || url.endsWith(";")
                || url.endsWith(")")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
