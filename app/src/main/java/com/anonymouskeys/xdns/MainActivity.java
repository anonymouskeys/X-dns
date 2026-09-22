package com.anonymouskeys.xdns;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST = 1001;
    private static final String KEY_CUSTOM_DOH = "custom_doh";

    private static final LinkedHashMap<String, String> BUILTIN_DOH = new LinkedHashMap<>();

    static {
        BUILTIN_DOH.put("Xfinity", "https://doh.xfinity.com/dns-query");
        BUILTIN_DOH.put("Flatuslifir", "https://dns.flatuslifir.is/dns-query");
        BUILTIN_DOH.put("Plan9 Hydra", "https://hydra.plan9-ns1.com/dns-query");
        BUILTIN_DOH.put("Plan9 Draco", "https://draco.plan9-ns2.com/dns-query");
        BUILTIN_DOH.put("Cloudflare", "https://cloudflare-dns.com/dns-query");
        BUILTIN_DOH.put("Google", "https://dns.google/dns-query");
        BUILTIN_DOH.put("Quad9", "https://dns.quad9.net/dns-query");
        BUILTIN_DOH.put("AdGuard", "https://dns.adguard-dns.com/dns-query");
        BUILTIN_DOH.put("DNS.SB", "https://doh.dns.sb/dns-query");
        BUILTIN_DOH.put("DNS4all", "https://doh.dns4all.eu/dns-query");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Spinner dohSpinner;
    private EditText customDoh;
    private TextView status;
    private TextView activeDoh;
    private TextView stats;
    private TextView speed;
    private TextView testResult;
    private TextView logText;
    private Button toggle;

    private final ArrayList<String> optionUrls = new ArrayList<>();
    private final ArrayList<String> optionLabels = new ArrayList<>();

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastSpeedSample = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(XDnsVpnService.PREFS, MODE_PRIVATE);
        buildUi();
        loadDohOptions();
        updateUi();
        handler.post(uiTicker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(uiTicker);
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(18);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 17, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        TextView title = text("X-dns", 34, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = text("DoH local VPN • v0.2.1", 15, Color.rgb(170, 174, 185));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle);

        status = text("", 21, Color.WHITE);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(24), 0, dp(8));
        root.addView(status);

        activeDoh = text("", 13, Color.rgb(180, 185, 198));
        activeDoh.setGravity(Gravity.CENTER_HORIZONTAL);
        activeDoh.setPadding(0, 0, 0, dp(14));
        root.addView(activeDoh);

        toggle = button("START");
        root.addView(toggle, matchButtonParams());

        toggle.setOnClickListener(v -> {
            if (XDnsVpnService.isRunning()) {
                Intent stop = new Intent(this, XDnsVpnService.class);
                stop.setAction(XDnsVpnService.ACTION_STOP);
                startService(stop);
                status.setText("STOPPING…");
                handler.postDelayed(this::updateUi, 400);
            } else {
                requestVpn();
            }
        });

        root.addView(section("DNS over HTTPS"));

        dohSpinner = new Spinner(this);
        root.addView(dohSpinner, fullWidth(dp(56)));

        customDoh = new EditText(this);
        customDoh.setHint("https://example.org/dns-query");
        customDoh.setSingleLine(true);
        customDoh.setTextColor(Color.WHITE);
        customDoh.setHintTextColor(Color.rgb(120, 125, 135));
        root.addView(customDoh, fullWidth(dp(54)));

        LinearLayout dnsButtons = horizontal();
        Button addDoh = button("ADD CUSTOM");
        Button testDoh = button("TEST SELECTED");
        dnsButtons.addView(addDoh, weighted());
        dnsButtons.addView(testDoh, weighted());
        root.addView(dnsButtons);

        Button testBuiltins = button("TEST ALL BUILT-IN DOH");
        root.addView(testBuiltins, matchButtonParams());

        Button discover = button("FIND FREE DOH ONLINE");
        root.addView(discover, matchButtonParams());

        testResult = text("DoH test: not run", 14, Color.rgb(180, 185, 198));
        testResult.setPadding(0, dp(8), 0, 0);
        root.addView(testResult);

        addDoh.setOnClickListener(v -> addCustomDoh());
        testDoh.setOnClickListener(v -> testCurrentDoh());
        testBuiltins.setOnClickListener(v -> testBuiltins());
        discover.setOnClickListener(v -> discoverFreeDoh());

        root.addView(section("Applications"));

        Button exclusions = button("EXCLUDE APPS");
        root.addView(exclusions, matchButtonParams());
        exclusions.setOnClickListener(v -> showAppExclusions());

        TextView excludedHint = text(
                "Excluded apps bypass X-dns completely. Restart X-dns after changing the list.",
                13,
                Color.rgb(150, 155, 165)
        );
        excludedHint.setPadding(0, dp(6), 0, 0);
        root.addView(excludedHint);

        root.addView(section("Connection statistics"));

        stats = text("", 15, Color.WHITE);
        stats.setLineSpacing(0, 1.15f);
        root.addView(stats);

        speed = text("", 15, Color.rgb(125, 205, 255));
        speed.setPadding(0, dp(6), 0, 0);
        root.addView(speed);

        root.addView(section("DNS log"));

        Button clear = button("CLEAR LOG");
        root.addView(clear, matchButtonParams());
        clear.setOnClickListener(v -> {
            DnsLog.clear();
            updateUi();
        });

        logText = text("No DNS queries yet.", 12, Color.rgb(205, 208, 215));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(0, dp(10), 0, dp(20));
        root.addView(logText);

        setContentView(scroll);
    }

    private void loadDohOptions() {
        optionUrls.clear();
        optionLabels.clear();

        for (Map.Entry<String, String> entry : BUILTIN_DOH.entrySet()) {
            optionLabels.add(entry.getKey() + "\n" + entry.getValue());
            optionUrls.add(entry.getValue());
        }

        Set<String> customs = new LinkedHashSet<>(
                prefs.getStringSet(KEY_CUSTOM_DOH, Collections.emptySet())
        );

        ArrayList<String> sortedCustom = new ArrayList<>(customs);
        Collections.sort(sortedCustom);

        for (String url : sortedCustom) {
            if (!optionUrls.contains(url)) {
                optionLabels.add("Custom\n" + url);
                optionUrls.add(url);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                optionLabels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dohSpinner.setAdapter(adapter);

        String selected = prefs.getString(
                XDnsVpnService.KEY_DOH_URL,
                XDnsVpnService.DEFAULT_DOH
        );

        int position = optionUrls.indexOf(selected);
        if (position < 0) position = 0;
        dohSpinner.setSelection(position);

        dohSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                                       View view, int position, long id) {
                if (position >= 0 && position < optionUrls.size()) {
                    prefs.edit()
                            .putString(XDnsVpnService.KEY_DOH_URL, optionUrls.get(position))
                            .apply();
                    updateUi();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void addCustomDoh() {
        String url = customDoh.getText().toString().trim();

        if (!url.startsWith("https://") || url.length() < 12) {
            Toast.makeText(this, "DoH URL must start with https://", Toast.LENGTH_LONG).show();
            return;
        }

        addCustomUrl(url);
        customDoh.setText("");
        Toast.makeText(this, "Custom DoH added.", Toast.LENGTH_SHORT).show();
    }

    private void addCustomUrl(String url) {
        Set<String> saved = new LinkedHashSet<>(
                prefs.getStringSet(KEY_CUSTOM_DOH, Collections.emptySet())
        );
        saved.add(url);

        prefs.edit()
                .putStringSet(KEY_CUSTOM_DOH, saved)
                .putString(XDnsVpnService.KEY_DOH_URL, url)
                .apply();

        loadDohOptions();

        int position = optionUrls.indexOf(url);
        if (position >= 0) dohSpinner.setSelection(position);
    }

    private String currentDoh() {
        int position = dohSpinner.getSelectedItemPosition();
        if (position >= 0 && position < optionUrls.size()) {
            return optionUrls.get(position);
        }
        return prefs.getString(XDnsVpnService.KEY_DOH_URL, XDnsVpnService.DEFAULT_DOH);
    }

    private void testCurrentDoh() {
        final String endpoint = currentDoh();
        testResult.setText("DoH test: testing…");

        new Thread(() -> {
            DohClient.Result result = DohClient.query(
                    endpoint,
                    DohClient.makeTestQuery("example.com")
            );

            runOnUiThread(() -> {
                if (result.ok()) {
                    String ip = DnsPacket.firstAddress(result.body);
                    testResult.setText(
                            "DoH test: OK • " + result.latencyMs + " ms • "
                                    + result.method + " • example.com → " + ip
                    );
                    testResult.setTextColor(Color.rgb(88, 214, 141));
                } else {
                    testResult.setText(
                            "DoH test: ERROR • "
                                    + (result.error == null ? "unknown" : result.error)
                                    + " • " + result.method
                    );
                    testResult.setTextColor(Color.rgb(255, 120, 120));
                }
            });
        }, "xdns-test").start();
    }

    private void testBuiltins() {
        final ArrayList<Map.Entry<String, String>> entries =
                new ArrayList<>(BUILTIN_DOH.entrySet());

        final ExecutorService pool = Executors.newFixedThreadPool(5);
        final ArrayList<DohTestItem> results =
                new ArrayList<>(Collections.nCopies(entries.size(), null));
        final AtomicInteger remaining = new AtomicInteger(entries.size());

        testResult.setText("Testing " + entries.size() + " built-in DoH servers…");
        testResult.setTextColor(Color.rgb(180, 185, 198));

        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            final Map.Entry<String, String> entry = entries.get(i);

            pool.submit(() -> {
                DohClient.Result result = DohClient.query(
                        entry.getValue(),
                        DohClient.makeTestQuery("example.com")
                );

                synchronized (results) {
                    results.set(index, new DohTestItem(
                            entry.getKey(),
                            entry.getValue(),
                            result
                    ));
                }

                if (remaining.decrementAndGet() == 0) {
                    pool.shutdown();
                    runOnUiThread(() -> showDohTestResults(results));
                }
            });
        }
    }

    private void showDohTestResults(List<DohTestItem> source) {
        ArrayList<DohTestItem> results = new ArrayList<>(source);

        results.sort((a, b) -> {
            if (a.result.ok() != b.result.ok()) return a.result.ok() ? -1 : 1;
            return Long.compare(a.result.latencyMs, b.result.latencyMs);
        });

        String[] labels = new String[results.size()];

        for (int i = 0; i < results.size(); i++) {
            DohTestItem item = results.get(i);
            String prefix = item.result.ok() ? "✓ " : "✗ ";
            labels[i] = prefix + item.name + " • " + item.result.shortStatus()
                    + "\n" + item.url;
        }

        new AlertDialog.Builder(this)
                .setTitle("Built-in DoH test")
                .setItems(labels, (dialog, which) -> {
                    DohTestItem selected = results.get(which);

                    if (selected.result.ok()) {
                        prefs.edit()
                                .putString(XDnsVpnService.KEY_DOH_URL, selected.url)
                                .apply();
                        loadDohOptions();
                        Toast.makeText(
                                this,
                                "Selected: " + selected.name,
                                Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        Toast.makeText(
                                this,
                                "That resolver failed on this network.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                })
                .setPositiveButton("CLOSE", null)
                .show();

        long working = results.stream().filter(x -> x.result.ok()).count();
        testResult.setText(
                "Built-ins: " + working + "/" + results.size() + " working"
        );
        testResult.setTextColor(
                working > 0 ? Color.rgb(88, 214, 141) : Color.rgb(255, 120, 120)
        );
    }

    private void discoverFreeDoh() {
        testResult.setText("Downloading public DoH catalog…");
        testResult.setTextColor(Color.rgb(180, 185, 198));

        new Thread(() -> {
            try {
                List<String> urls = DohCatalog.fetchPublicDohUrls();

                runOnUiThread(() -> showDiscoveredUrls(urls));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    testResult.setText("Catalog error: " + safeMessage(e));
                    testResult.setTextColor(Color.rgb(255, 120, 120));
                });
            }
        }, "xdns-catalog").start();
    }

    private void showDiscoveredUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            testResult.setText("Catalog returned no DoH URLs.");
            testResult.setTextColor(Color.rgb(255, 120, 120));
            return;
        }

        // Keep the dialog manageable; the source may contain hundreds.
        int max = Math.min(urls.size(), 120);
        String[] labels = new String[max];

        for (int i = 0; i < max; i++) {
            labels[i] = urls.get(i);
        }

        testResult.setText("Found " + urls.size() + " public DoH endpoints.");
        testResult.setTextColor(Color.rgb(88, 214, 141));

        new AlertDialog.Builder(this)
                .setTitle("Free public DoH catalog (" + urls.size() + ")")
                .setItems(labels, (dialog, which) -> {
                    String url = labels[which];
                    testAndOfferDiscovered(url);
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void testAndOfferDiscovered(String url) {
        testResult.setText("Testing discovered DoH… " + url);

        new Thread(() -> {
            DohClient.Result result = DohClient.query(
                    url,
                    DohClient.makeTestQuery("example.com")
            );

            runOnUiThread(() -> {
                if (result.ok()) {
                    String ip = DnsPacket.firstAddress(result.body);

                    new AlertDialog.Builder(this)
                            .setTitle("Working DoH")
                            .setMessage(
                                    url + "\n\n"
                                            + result.latencyMs + " ms • "
                                            + result.method
                                            + "\nexample.com → " + ip
                            )
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("ADD & SELECT", (d, w) -> {
                                addCustomUrl(url);
                                testResult.setText("Selected working DoH: " + url);
                                testResult.setTextColor(Color.rgb(88, 214, 141));
                            })
                            .show();
                } else {
                    testResult.setText(
                            "Discovered DoH failed: " + result.shortStatus()
                    );
                    testResult.setTextColor(Color.rgb(255, 120, 120));
                }
            });
        }, "xdns-discovered-test").start();
    }

    private void requestVpn() {
        Intent permissionIntent = VpnService.prepare(this);

        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        prefs.edit()
                .putString(XDnsVpnService.KEY_DOH_URL, currentDoh())
                .apply();

        Intent service = new Intent(this, XDnsVpnService.class);
        service.setAction(XDnsVpnService.ACTION_START);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        handler.postDelayed(this::updateUi, 350);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void showAppExclusions() {
        PackageManager pm = getPackageManager();

        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        LinkedHashMap<String, AppItem> unique = new LinkedHashMap<>();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;

            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;

            String label;
            try {
                CharSequence cs = info.loadLabel(pm);
                label = cs == null ? pkg : cs.toString();
            } catch (Exception e) {
                label = pkg;
            }

            unique.put(pkg, new AppItem(label, pkg));
        }

        ArrayList<AppItem> apps = new ArrayList<>(unique.values());
        apps.sort(Comparator.comparing(
                a -> a.label.toLowerCase(java.util.Locale.ROOT)
        ));

        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];

        Set<String> saved = new HashSet<>(
                prefs.getStringSet(
                        XDnsVpnService.KEY_EXCLUDED_APPS,
                        Collections.emptySet()
                )
        );
        Set<String> selected = new HashSet<>(saved);

        for (int i = 0; i < apps.size(); i++) {
            AppItem app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
            checked[i] = saved.contains(app.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Apps that bypass X-dns")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String pkg = apps.get(which).packageName;
                    if (isChecked) selected.add(pkg);
                    else selected.remove(pkg);
                })
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    prefs.edit()
                            .putStringSet(
                                    XDnsVpnService.KEY_EXCLUDED_APPS,
                                    new HashSet<>(selected)
                            )
                            .apply();

                    Toast.makeText(
                            this,
                            XDnsVpnService.isRunning()
                                    ? "Saved. Restart X-dns to apply exclusions."
                                    : "Exclusions saved.",
                            Toast.LENGTH_LONG
                    ).show();
                })
                .show();
    }

    private void updateUi() {
        boolean running = XDnsVpnService.isRunning();

        if (running) {
            status.setText("DNS VPN ACTIVE");
            status.setTextColor(Color.rgb(88, 214, 141));
            toggle.setText("STOP");
        } else {
            status.setText("DNS VPN OFF");
            status.setTextColor(Color.rgb(255, 120, 120));
            toggle.setText("START");
        }

        activeDoh.setText(
                "Selected DoH: "
                        + prefs.getString(
                                XDnsVpnService.KEY_DOH_URL,
                                XDnsVpnService.DEFAULT_DOH
                        )
        );

        stats.setText(DnsLog.statsText());
        logText.setText(DnsLog.getText());
        updateSpeed();
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();

        if (rx < 0 || tx < 0) {
            speed.setText("Device speed: unavailable");
            return;
        }

        if (lastRx >= 0 && lastTx >= 0 && lastSpeedSample > 0 && now > lastSpeedSample) {
            double seconds = (now - lastSpeedSample) / 1000.0;
            long rxRate = Math.max(0, (long) ((rx - lastRx) / seconds));
            long txRate = Math.max(0, (long) ((tx - lastTx) / seconds));

            speed.setText(
                    "Device speed: ↓ " + formatRate(rxRate)
                            + "   ↑ " + formatRate(txRate)
            );
        } else {
            speed.setText("Device speed: measuring…");
        }

        lastRx = rx;
        lastTx = tx;
        lastSpeedSample = now;
    }

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    private String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        double kib = bytesPerSecond / 1024.0;
        if (kib < 1024) {
            return String.format(java.util.Locale.US, "%.1f KiB/s", kib);
        }
        return String.format(java.util.Locale.US, "%.2f MiB/s", kib / 1024.0);
    }

    private TextView section(String name) {
        TextView view = text(name, 18, Color.WHITE);
        view.setPadding(0, dp(28), 0, dp(10));
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        return button;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(dp(2), dp(4), dp(2), 0);
        return params;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams matchButtonParams() {
        LinearLayout.LayoutParams params = fullWidth(dp(56));
        params.setMargins(0, dp(4), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }

    private static final class AppItem {
        final String label;
        final String packageName;

        AppItem(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    private static final class DohTestItem {
        final String name;
        final String url;
        final DohClient.Result result;

        DohTestItem(String name, String url, DohClient.Result result) {
            this.name = name;
            this.url = url;
            this.result = result;
        }
    }
}
