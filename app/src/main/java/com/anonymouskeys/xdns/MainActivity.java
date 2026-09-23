package com.anonymouskeys.xdns;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.Uri;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private static final LinkedHashMap<String, String>
            BUILTIN_DOH = new LinkedHashMap<>();

    static {
        BUILTIN_DOH.put(
                "Xfinity",
                "https://doh.xfinity.com/dns-query"
        );
        BUILTIN_DOH.put(
                "Flatuslifir",
                "https://dns.flatuslifir.is/dns-query"
        );
        BUILTIN_DOH.put(
                "Plan9 Hydra",
                "https://hydra.plan9-ns1.com/dns-query"
        );
        BUILTIN_DOH.put(
                "Plan9 Draco",
                "https://draco.plan9-ns2.com/dns-query"
        );
        BUILTIN_DOH.put(
                "Cloudflare",
                "https://cloudflare-dns.com/dns-query"
        );
        BUILTIN_DOH.put(
                "Google",
                "https://dns.google/dns-query"
        );
        BUILTIN_DOH.put(
                "Quad9",
                "https://dns.quad9.net/dns-query"
        );
        BUILTIN_DOH.put(
                "AdGuard",
                "https://dns.adguard-dns.com/dns-query"
        );
        BUILTIN_DOH.put(
                "DNS.SB",
                "https://doh.dns.sb/dns-query"
        );
        BUILTIN_DOH.put(
                "DNS4all",
                "https://doh.dns4all.eu/dns-query"
        );
    }

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;

    private Spinner modeSpinner;
    private Spinner dohSpinner;
    private Spinner strategySpinner;

    private EditText customDoh;
    private EditText dpiTtl;
    private CheckBox forceTcp;

    private TextView status;
    private TextView activeDoh;
    private TextView autoResult;
    private TextView stats;
    private TextView routeHealth;
    private TextView speed;
    private TextView testResult;
    private TextView logText;

    private Button toggle;
    private Button autoButton;

    private final ArrayList<String> optionUrls =
            new ArrayList<>();

    private final ArrayList<String> optionLabels =
            new ArrayList<>();

    private final ArrayList<String> strategyIds =
            new ArrayList<>();

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastSpeedSample = -1;

    private volatile boolean autoTuning = false;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(
                XDnsVpnService.PREFS,
                MODE_PRIVATE
        );

        seedResolvers();
        buildUi();
        loadDohOptions();
        loadStrategies();
        loadMode();

        updateUi();
        handler.post(uiTicker);

        // Fresh install: populate the maintained public DoH catalog
        // automatically. Existing saved entries remain available immediately.
        refreshPublicCatalogSilently();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(uiTicker);
        super.onDestroy();
    }

    private void seedResolvers() {
        for (Map.Entry<String, String> entry
                : BUILTIN_DOH.entrySet()) {

            ResolverStore.seed(
                    prefs,
                    entry.getKey(),
                    entry.getValue(),
                    "builtin"
            );
        }
    }

    private void buildUi() {
        int pad = dp(18);

        ScrollView scroll =
                new ScrollView(this);

        scroll.setFillViewport(true);
        scroll.setBackgroundColor(
                Color.rgb(16, 17, 20)
        );

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                pad,
                pad,
                pad,
                pad * 2
        );

        scroll.addView(root);

        TextView title =
                text("X-dns", 34, Color.WHITE);

        title.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        root.addView(title);

        TextView subtitle =
                text(
                        "DoH + Dragon DPI • TLS Route Proof • v0.7.2",
                        15,
                        Color.rgb(170, 174, 185)
                );

        subtitle.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        root.addView(subtitle);

        TextView author =
                text(
                        "@anonymouskeys",
                        15,
                        Color.rgb(225, 230, 240)
                );

        author.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        author.setPadding(
                0,
                dp(6),
                0,
                0
        );

        author.setOnClickListener(
                v -> openTelegram()
        );

        author.setOnLongClickListener(v -> {
            copyTelegramLink();
            return true;
        });

        root.addView(author);

        TextView telegram =
                text(
                        "https://t.me/anonymouskeys  •  tap: open  •  hold: copy",
                        12,
                        Color.rgb(100, 195, 255)
                );

        telegram.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        telegram.setPadding(
                0,
                dp(3),
                0,
                0
        );

        telegram.setOnClickListener(
                v -> openTelegram()
        );

        telegram.setOnLongClickListener(v -> {
            copyTelegramLink();
            return true;
        });

        root.addView(telegram);

        status =
                text("", 21, Color.WHITE);

        status.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        status.setPadding(
                0,
                dp(24),
                0,
                dp(8)
        );

        root.addView(status);

        activeDoh =
                text(
                        "",
                        13,
                        Color.rgb(180, 185, 198)
                );

        activeDoh.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        activeDoh.setPadding(
                0,
                0,
                0,
                dp(14)
        );

        root.addView(activeDoh);

        root.addView(
                section("Mode")
        );

        modeSpinner =
                new Spinner(this);

        ArrayAdapter<String> modeAdapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_item,
                        new String[]{
                                "DoH only — encrypted DNS",
                                "Dragon DPI — full traffic"
                        }
                );

        modeAdapter.setDropDownViewResource(
                android.R.layout
                        .simple_spinner_dropdown_item
        );

        modeSpinner.setAdapter(modeAdapter);

        root.addView(
                modeSpinner,
                fullWidth(dp(56))
        );

        toggle =
                button("START");

        root.addView(
                toggle,
                matchButtonParams()
        );

        toggle.setOnClickListener(v -> {
            if (XDnsVpnService.isActive()) {
                Intent stop =
                        new Intent(
                                this,
                                XDnsVpnService.class
                        );

                stop.setAction(
                        XDnsVpnService.ACTION_STOP
                );

                startService(stop);

                status.setText("STOPPING…");

                handler.postDelayed(
                        this::updateUi,
                        500
                );

            } else {
                saveSettings();
                requestVpn();
            }
        });

        autoButton =
                button(
                        "AUTO: FRESH BEST PROFILE"
                );

        root.addView(
                autoButton,
                matchButtonParams()
        );

        autoButton.setOnClickListener(
                v -> runAutoTune()
        );

        autoResult =
                text(
                        "",
                        14,
                        Color.rgb(125, 205, 255)
                );

        autoResult.setPadding(
                0,
                dp(8),
                0,
                0
        );

        root.addView(autoResult);

        root.addView(
                section("DNS over HTTPS")
        );

        dohSpinner =
                new Spinner(this);

        root.addView(
                dohSpinner,
                fullWidth(dp(58))
        );

        customDoh =
                new EditText(this);

        customDoh.setHint(
                "https://example.org/dns-query"
        );

        customDoh.setSingleLine(true);
        customDoh.setTextColor(Color.WHITE);

        customDoh.setHintTextColor(
                Color.rgb(120, 125, 135)
        );

        root.addView(
                customDoh,
                fullWidth(dp(54))
        );

        LinearLayout dnsButtons =
                horizontal();

        Button addDoh =
                button("ADD CUSTOM");

        Button testDoh =
                button("TEST SELECTED");

        dnsButtons.addView(
                addDoh,
                weighted()
        );

        dnsButtons.addView(
                testDoh,
                weighted()
        );

        root.addView(dnsButtons);

        Button testBuiltins =
                button(
                        "TEST ALL BUILT-IN DOH"
                );

        root.addView(
                testBuiltins,
                matchButtonParams()
        );

        Button discover =
                button(
                        "FIND + SAVE + TEST FREE DOH"
                );

        root.addView(
                discover,
                matchButtonParams()
        );

        Button statusDb =
                button(
                        "SAVED RESOLVER STATUS"
                );

        root.addView(
                statusDb,
                matchButtonParams()
        );

        testResult =
                text(
                        "Resolver database: ready",
                        14,
                        Color.rgb(180, 185, 198)
                );

        testResult.setPadding(
                0,
                dp(8),
                0,
                0
        );

        root.addView(testResult);

        addDoh.setOnClickListener(
                v -> addCustomDoh()
        );

        testDoh.setOnClickListener(
                v -> testCurrentDoh()
        );

        testBuiltins.setOnClickListener(
                v -> testBuiltins()
        );

        discover.setOnClickListener(
                v -> discoverAndTest()
        );

        statusDb.setOnClickListener(
                v -> showResolverStatus()
        );

        root.addView(
                section("Dragon DPI")
        );

        strategySpinner =
                new Spinner(this);

        root.addView(
                strategySpinner,
                fullWidth(dp(56))
        );

        dpiTtl =
                new EditText(this);

        dpiTtl.setSingleLine(true);
        dpiTtl.setHint(
                "Fake TTL (default 8)"
        );

        dpiTtl.setTextColor(Color.WHITE);

        dpiTtl.setHintTextColor(
                Color.rgb(120, 125, 135)
        );

        dpiTtl.setInputType(
                android.text.InputType
                        .TYPE_CLASS_NUMBER
        );

        dpiTtl.setText(
                prefs.getString(
                        XDnsVpnService.KEY_DPI_TTL,
                        "8"
                )
        );

        root.addView(
                dpiTtl,
                fullWidth(dp(54))
        );

        TextView dpiHint =
                text(
                        "AUTO tests strategies from mild to Dragon Maximum "
                                + "through local ciadpi and performs a real "
                                + "HTTPS YouTube probe using the tested DoH.",
                        13,
                        Color.rgb(255, 190, 110)
                );

        dpiHint.setPadding(
                0,
                dp(6),
                0,
                0
        );

        root.addView(dpiHint);

        forceTcp = new CheckBox(this);
        forceTcp.setText(
                "Force TCP in Dragon mode (disable SOCKS UDP/QUIC)"
        );
        forceTcp.setTextColor(Color.WHITE);
        forceTcp.setChecked(
                prefs.getBoolean(
                        XDnsVpnService.KEY_FORCE_TCP,
                        true
                )
        );

        root.addView(forceTcp);

        TextView forceTcpHint =
                text(
                        "Recommended for YouTube. QUIC uses UDP/443 and can "
                                + "bypass the TCP DPI strategy. This option "
                                + "forces a fast fallback to HTTPS/TCP. "
                                + "Voice calls/games that need UDP may be affected.",
                        13,
                        Color.rgb(255, 190, 110)
                );

        forceTcpHint.setPadding(
                0,
                dp(4),
                0,
                0
        );

        root.addView(forceTcpHint);

        root.addView(
                section("Applications")
        );

        Button exclusions =
                button("EXCLUDE APPS");

        root.addView(
                exclusions,
                matchButtonParams()
        );

        exclusions.setOnClickListener(
                v -> showAppExclusions()
        );

        root.addView(
                section(
                        "Connection statistics"
                )
        );

        stats =
                text("", 15, Color.WHITE);

        stats.setLineSpacing(
                0,
                1.15f
        );

        root.addView(stats);

        routeHealth =
                text(
                        "",
                        15,
                        Color.rgb(120, 220, 165)
                );

        routeHealth.setPadding(
                0,
                dp(8),
                0,
                0
        );

        root.addView(routeHealth);

        speed =
                text(
                        "",
                        15,
                        Color.rgb(125, 205, 255)
                );

        speed.setPadding(
                0,
                dp(6),
                0,
                0
        );

        root.addView(speed);

        root.addView(
                section("Log")
        );

        Button clear =
                button("CLEAR LOG");

        root.addView(
                clear,
                matchButtonParams()
        );

        clear.setOnClickListener(v -> {
            DnsLog.clear();
            updateUi();
        });

        logText =
                text(
                        "No events yet.",
                        12,
                        Color.rgb(205, 208, 215)
                );

        logText.setTypeface(
                android.graphics.Typeface.MONOSPACE
        );

        logText.setTextIsSelectable(true);

        logText.setPadding(
                0,
                dp(10),
                0,
                dp(20)
        );

        root.addView(logText);

        setContentView(scroll);
    }

    private void loadMode() {
        String mode =
                prefs.getString(
                        XDnsVpnService.KEY_MODE,
                        XDnsVpnService.MODE_DOH
                );

        modeSpinner.setSelection(
                XDnsVpnService
                        .MODE_DRAGON_DPI
                        .equals(mode)
                        ? 1
                        : 0
        );

        modeSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView
                        .OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        prefs.edit()
                                .putString(
                                        XDnsVpnService.KEY_MODE,
                                        position == 1
                                                ? XDnsVpnService.MODE_DRAGON_DPI
                                                : XDnsVpnService.MODE_DOH
                                )
                                .apply();
                    }

                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> parent
                    ) {
                    }
                }
        );
    }

    private void loadStrategies() {
        int ttl = readTtl();

        List<DpiStrategies.Preset> presets =
                DpiStrategies.candidates(ttl);

        ArrayList<String> labels =
                new ArrayList<>();

        strategyIds.clear();

        for (DpiStrategies.Preset preset
                : presets) {
            strategyIds.add(preset.id);
            labels.add(preset.name);
        }

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_item,
                        labels
                );

        adapter.setDropDownViewResource(
                android.R.layout
                        .simple_spinner_dropdown_item
        );

        strategySpinner.setAdapter(adapter);

        String selected =
                prefs.getString(
                        XDnsVpnService.KEY_DPI_STRATEGY,
                        "maximum"
                );

        int position =
                strategyIds.indexOf(selected);

        if (position < 0) {
            position =
                    strategyIds.indexOf("maximum");
        }

        if (position >= 0) {
            strategySpinner.setSelection(
                    position
            );
        }
    }

    private void loadDohOptions() {
        String selected =
                prefs.getString(
                        XDnsVpnService.KEY_DOH_URL,
                        XDnsVpnService.DEFAULT_DOH
                );

        optionUrls.clear();
        optionLabels.clear();

        // Built-ins first.
        for (Map.Entry<String, String> entry
                : BUILTIN_DOH.entrySet()) {
            if (!optionUrls.contains(entry.getValue())) {
                addResolverOption(
                        entry.getKey(),
                        entry.getValue()
                );
            }
        }

        // Then every resolver in the persistent database: working, untested
        // and failed. This is the full public catalog after background import.
        ArrayList<ResolverStore.Entry> all =
                new ArrayList<>(
                        ResolverStore.all(prefs)
                );

        all.sort((a, b) -> {
            int ra = ResolverStore.OK.equals(a.status)
                    ? 0
                    : ResolverStore.UNKNOWN.equals(a.status)
                    ? 1
                    : 2;

            int rb = ResolverStore.OK.equals(b.status)
                    ? 0
                    : ResolverStore.UNKNOWN.equals(b.status)
                    ? 1
                    : 2;

            if (ra != rb) {
                return Integer.compare(ra, rb);
            }

            if (ra == 0) {
                int latency =
                        Long.compare(
                                a.latencyMs,
                                b.latencyMs
                        );

                if (latency != 0) {
                    return latency;
                }
            }

            String an =
                    a.name == null
                            ? a.url
                            : a.name;

            String bn =
                    b.name == null
                            ? b.url
                            : b.name;

            return an.compareToIgnoreCase(bn);
        });

        for (ResolverStore.Entry entry : all) {
            if (entry.url != null
                    && !optionUrls.contains(entry.url)) {
                addResolverOption(
                        entry.name == null
                                || entry.name.isEmpty()
                                ? "Public DoH"
                                : entry.name,
                        entry.url
                );
            }
        }

        // Preserve manually added URLs even if they are not in the catalog.
        Set<String> customs =
                new LinkedHashSet<>(
                        prefs.getStringSet(
                                KEY_CUSTOM_DOH,
                                Collections.emptySet()
                        )
                );

        ArrayList<String> sortedCustom =
                new ArrayList<>(customs);

        Collections.sort(sortedCustom);

        for (String url : sortedCustom) {
            if (!optionUrls.contains(url)) {
                ResolverStore.Entry entry =
                        ResolverStore.get(
                                prefs,
                                url
                        );

                addResolverOption(
                        entry == null
                                ? "Custom"
                                : entry.name,
                        url
                );
            }
        }

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout
                                .simple_spinner_item,
                        optionLabels
                );

        adapter.setDropDownViewResource(
                android.R.layout
                        .simple_spinner_dropdown_item
        );

        dohSpinner.setAdapter(adapter);

        int position =
                optionUrls.indexOf(selected);

        if (position < 0) {
            position = 0;
        }

        dohSpinner.setSelection(position);

        dohSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView
                        .OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        if (position >= 0
                                && position
                                < optionUrls.size()) {

                            prefs.edit()
                                    .putString(
                                            XDnsVpnService.KEY_DOH_URL,
                                            optionUrls.get(position)
                                    )
                                    .apply();

                            FastDoh.clearCache();
                            updateUi();
                        }
                    }

                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> parent
                    ) {
                    }
                }
        );
    }

    private void addResolverOption(
            String fallbackName,
            String url
    ) {
        ResolverStore.Entry e =
                ResolverStore.get(
                        prefs,
                        url
                );

        String prefix = "?";
        String suffix = "";

        if (e != null) {
            prefix = e.statusPrefix();

            if (ResolverStore.OK.equals(
                    e.status
            )) {
                suffix =
                        " • "
                                + e.latencyMs
                                + " ms";

                if (e.attempts > 0) {
                    suffix +=
                            " • "
                                    + e.successes
                                    + "/"
                                    + e.attempts;
                }

            } else if (ResolverStore.FAIL.equals(
                    e.status
            )) {
                suffix = " • failed";
            }
        }

        optionUrls.add(url);

        optionLabels.add(
                prefix
                        + " "
                        + fallbackName
                        + suffix
                        + "\n"
                        + url
        );
    }

    private boolean pendingAuto;

    private void runAutoTune() {
        if (XDnsVpnService.isTuning()) {
            Toast.makeText(this, "AUTO is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingAuto = true;
        autoResult.setText("AUTO • waiting for network test…");
        requestVpn();
    }

    private void addCustomDoh() {
        String url =
                customDoh.getText()
                        .toString()
                        .trim();

        if (!url.startsWith("https://")
                || url.length() < 12) {

            Toast.makeText(
                    this,
                    "DoH URL must start with https://",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        Set<String> saved =
                new LinkedHashSet<>(
                        prefs.getStringSet(
                                KEY_CUSTOM_DOH,
                                Collections.emptySet()
                        )
                );

        saved.add(url);

        prefs.edit()
                .putStringSet(
                        KEY_CUSTOM_DOH,
                        saved
                )
                .putString(
                        XDnsVpnService.KEY_DOH_URL,
                        url
                )
                .apply();

        ResolverStore.seed(
                prefs,
                "Custom",
                url,
                "custom"
        );

        customDoh.setText("");

        loadDohOptions();

        int position =
                optionUrls.indexOf(url);

        if (position >= 0) {
            dohSpinner.setSelection(
                    position
            );
        }

        testUrl(
                url,
                "Custom",
                true
        );
    }

    private String currentDoh() {
        int position =
                dohSpinner
                        .getSelectedItemPosition();

        if (position >= 0
                && position
                < optionUrls.size()) {
            return optionUrls.get(position);
        }

        return prefs.getString(
                XDnsVpnService.KEY_DOH_URL,
                XDnsVpnService.DEFAULT_DOH
        );
    }

    private void testCurrentDoh() {
        String url = currentDoh();

        ResolverStore.Entry e =
                ResolverStore.get(
                        prefs,
                        url
                );

        testUrl(
                url,
                e == null
                        ? "Selected"
                        : e.name,
                false
        );
    }

    private void testUrl(
            String url,
            String name,
            boolean selectOnSuccess
    ) {
        testResult.setText(
                "Testing " + url + " …"
        );

        testResult.setTextColor(
                Color.rgb(180, 185, 198)
        );

        new Thread(() -> {
            DohClient.Result result =
                    DohClient.query(
                            url,
                            DohClient
                                    .makeTestQuery(
                                            "example.com"
                                    )
                    );

            ResolverStore.saveResult(
                    prefs,
                    url,
                    name,
                    result
            );

            if (result.ok()
                    && selectOnSuccess) {

                Set<String> saved =
                        new LinkedHashSet<>(
                                prefs.getStringSet(
                                        KEY_CUSTOM_DOH,
                                        Collections.emptySet()
                                )
                        );

                saved.add(url);

                prefs.edit()
                        .putStringSet(
                                KEY_CUSTOM_DOH,
                                saved
                        )
                        .putString(
                                XDnsVpnService.KEY_DOH_URL,
                                url
                        )
                        .apply();
            }

            runOnUiThread(() -> {
                loadDohOptions();

                if (result.ok()) {
                    testResult.setText(
                            "✓ "
                                    + name
                                    + " • "
                                    + result.latencyMs
                                    + " ms • "
                                    + result.method
                    );

                    testResult.setTextColor(
                            Color.rgb(
                                    88,
                                    214,
                                    141
                            )
                    );

                } else {
                    testResult.setText(
                            "✗ "
                                    + name
                                    + " • "
                                    + (result.error
                                    == null
                                    ? "failed"
                                    : result.error)
                    );

                    testResult.setTextColor(
                            Color.rgb(
                                    255,
                                    120,
                                    120
                            )
                    );
                }
            });

        }, "xdns-resolver-test").start();
    }

    private void testBuiltins() {
        final ArrayList<Map.Entry<String, String>>
                entries =
                new ArrayList<>(
                        BUILTIN_DOH.entrySet()
                );

        final ExecutorService pool =
                Executors.newFixedThreadPool(5);

        final AtomicInteger remaining =
                new AtomicInteger(
                        entries.size()
                );

        final AtomicInteger working =
                new AtomicInteger();

        testResult.setText(
                "Testing built-ins: 0/"
                        + entries.size()
        );

        for (Map.Entry<String, String> entry
                : entries) {

            pool.submit(() -> {
                DohClient.Result result =
                        DohClient.query(
                                entry.getValue(),
                                DohClient
                                        .makeTestQuery(
                                                "example.com"
                                        )
                        );

                ResolverStore.saveResult(
                        prefs,
                        entry.getValue(),
                        entry.getKey(),
                        result
                );

                if (result.ok()) {
                    working.incrementAndGet();
                }

                int done =
                        entries.size()
                                - remaining
                                .decrementAndGet();

                runOnUiThread(() -> {
                    testResult.setText(
                            "Testing built-ins: "
                                    + done
                                    + "/"
                                    + entries.size()
                                    + " • working "
                                    + working.get()
                    );

                    if (done == entries.size()) {
                        loadDohOptions();
                        showResolverStatus();
                    }
                });

                if (remaining.get() == 0) {
                    pool.shutdown();
                }
            });
        }
    }

    private void refreshPublicCatalogSilently() {
        new Thread(() -> {
            try {
                List<String> urls =
                        DohCatalog.fetchPublicDohUrls();

                int added = 0;

                for (String url : urls) {
                    ResolverStore.Entry before =
                            ResolverStore.get(
                                    prefs,
                                    url
                            );

                    ResolverStore.rememberDiscovered(
                            prefs,
                            url
                    );

                    if (before == null) {
                        added++;
                    }
                }

                final int imported = added;

                runOnUiThread(() -> {
                    loadDohOptions();

                    if (imported > 0) {
                        testResult.setText(
                                "Public DoH catalog: "
                                        + urls.size()
                                        + " saved • "
                                        + imported
                                        + " new"
                        );
                    }
                });

            } catch (Exception e) {
                // Keep the already saved offline catalog. No popup on startup.
                DnsLog.addRaw(
                        "CATALOG • background refresh failed • "
                                + safeMessage(e)
                );
            }
        }, "xdns-catalog-refresh").start();
    }

    private void discoverAndTest() {
        testResult.setText(
                "Downloading public DoH catalog…"
        );

        testResult.setTextColor(
                Color.rgb(180, 185, 198)
        );

        new Thread(() -> {
            try {
                List<String> urls =
                        DohCatalog
                                .fetchPublicDohUrls();

                // Save every discovered endpoint before testing anything.
                for (String url : urls) {
                    ResolverStore.rememberDiscovered(
                            prefs,
                            url
                    );
                }

                runOnUiThread(() -> {
                    testResult.setText(
                            "Saved "
                                    + urls.size()
                                    + " DoH endpoints. Testing all…"
                    );

                    testDiscovered(urls);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    testResult.setText(
                            "Catalog error: "
                                    + safeMessage(e)
                    );

                    testResult.setTextColor(
                            Color.rgb(
                                    255,
                                    120,
                                    120
                            )
                    );
                });
            }
        }, "xdns-catalog").start();
    }

    private void testDiscovered(
            List<String> urls
    ) {
        if (urls == null || urls.isEmpty()) {
            return;
        }

        final ExecutorService pool =
                Executors
                        .newFixedThreadPool(12);

        final AtomicInteger remaining =
                new AtomicInteger(
                        urls.size()
                );

        final AtomicInteger working =
                new AtomicInteger();

        for (String url : urls) {
            pool.submit(() -> {
                ResolverStore.Entry existing =
                        ResolverStore.get(
                                prefs,
                                url
                        );

                String name =
                        existing == null
                                ? url
                                : existing.name;

                DohClient.Result result =
                        DohClient.query(
                                url,
                                DohClient
                                        .makeTestQuery(
                                                "example.com"
                                        )
                        );

                ResolverStore.saveResult(
                        prefs,
                        url,
                        name,
                        result
                );

                if (result.ok()) {
                    working.incrementAndGet();

                    Set<String> saved =
                            new LinkedHashSet<>(
                                    prefs.getStringSet(
                                            KEY_CUSTOM_DOH,
                                            Collections.emptySet()
                                    )
                            );

                    saved.add(url);

                    prefs.edit()
                            .putStringSet(
                                    KEY_CUSTOM_DOH,
                                    saved
                            )
                            .apply();
                }

                int done =
                        urls.size()
                                - remaining
                                .decrementAndGet();

                if (done % 5 == 0
                        || done == urls.size()) {

                    runOnUiThread(() -> {
                        testResult.setText(
                                "Public DoH: "
                                        + done
                                        + "/"
                                        + urls.size()
                                        + " tested • "
                                        + working.get()
                                        + " working"
                        );

                        if (done == urls.size()) {
                            loadDohOptions();

                            testResult
                                    .setTextColor(
                                            working.get() > 0
                                                    ? Color.rgb(
                                                    88,
                                                    214,
                                                    141
                                            )
                                                    : Color.rgb(
                                                    255,
                                                    120,
                                                    120
                                            )
                                    );
                        }
                    });
                }

                if (remaining.get() == 0) {
                    pool.shutdown();
                }
            });
        }
    }

    private void showResolverStatus() {
        ArrayList<ResolverStore.Entry> all =
                new ArrayList<>(
                        ResolverStore.all(prefs)
                );

        all.sort((a, b) -> {
            int rankA =
                    ResolverStore.OK.equals(
                            a.status
                    )
                            ? 0
                            : ResolverStore.UNKNOWN
                            .equals(a.status)
                            ? 1
                            : 2;

            int rankB =
                    ResolverStore.OK.equals(
                            b.status
                    )
                            ? 0
                            : ResolverStore.UNKNOWN
                            .equals(b.status)
                            ? 1
                            : 2;

            if (rankA != rankB) {
                return Integer.compare(
                        rankA,
                        rankB
                );
            }

            if (rankA == 0) {
                return Long.compare(
                        a.latencyMs,
                        b.latencyMs
                );
            }

            return a.name.compareToIgnoreCase(
                    b.name
            );
        });

        String[] labels =
                new String[all.size()];

        for (int i = 0; i < all.size(); i++) {
            labels[i] =
                    all.get(i).displayLine();
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Saved resolver status ("
                                + all.size()
                                + ")"
                )
                .setItems(
                        labels,
                        (dialog, which) -> {
                            ResolverStore.Entry e =
                                    all.get(which);

                            if (ResolverStore.OK
                                    .equals(e.status)) {

                                prefs.edit()
                                        .putString(
                                                XDnsVpnService.KEY_DOH_URL,
                                                e.url
                                        )
                                        .apply();

                                Set<String> saved =
                                        new LinkedHashSet<>(
                                                prefs.getStringSet(
                                                        KEY_CUSTOM_DOH,
                                                        Collections.emptySet()
                                                )
                                        );

                                saved.add(e.url);

                                prefs.edit()
                                        .putStringSet(
                                                KEY_CUSTOM_DOH,
                                                saved
                                        )
                                        .apply();

                                loadDohOptions();

                                Toast.makeText(
                                        this,
                                        "Selected "
                                                + e.name,
                                        Toast.LENGTH_SHORT
                                ).show();

                            } else {
                                testUrl(
                                        e.url,
                                        e.name,
                                        true
                                );
                            }
                        }
                )
                .setPositiveButton(
                        "CLOSE",
                        null
                )
                .show();
    }

    private void saveSettings() {
        String strategy =
                currentStrategyId();

        prefs.edit()
                .putString(
                        XDnsVpnService.KEY_DOH_URL,
                        currentDoh()
                )
                .putString(
                        XDnsVpnService.KEY_MODE,
                        modeSpinner
                                .getSelectedItemPosition()
                                == 1
                                ? XDnsVpnService.MODE_DRAGON_DPI
                                : XDnsVpnService.MODE_DOH
                )
                .putString(
                        XDnsVpnService.KEY_DPI_TTL,
                        String.valueOf(readTtl())
                )
                .putString(
                        XDnsVpnService.KEY_DPI_STRATEGY,
                        strategy
                )
                .putBoolean(
                        XDnsVpnService.KEY_FORCE_TCP,
                        forceTcp == null || forceTcp.isChecked()
                )
                .apply();
    }

    private int readTtl() {
        if (dpiTtl == null) {
            try {
                return Integer.parseInt(
                        prefs.getString(
                                XDnsVpnService.KEY_DPI_TTL,
                                "8"
                        )
                );
            } catch (Exception e) {
                return 8;
            }
        }

        try {
            int value =
                    Integer.parseInt(
                            dpiTtl.getText()
                                    .toString()
                                    .trim()
                    );

            return Math.max(
                    1,
                    Math.min(
                            255,
                            value
                    )
            );

        } catch (Exception e) {
            return 8;
        }
    }

    private String currentStrategyId() {
        int position =
                strategySpinner
                        .getSelectedItemPosition();

        if (position >= 0
                && position
                < strategyIds.size()) {
            return strategyIds.get(position);
        }

        return "maximum";
    }

    private void requestVpn() {
        Intent permissionIntent =
                VpnService.prepare(this);

        if (permissionIntent != null) {
            startActivityForResult(
                    permissionIntent,
                    VPN_REQUEST
            );
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent service =
                new Intent(
                        this,
                        XDnsVpnService.class
                );

        service.setAction(
                pendingAuto ? XDnsVpnService.ACTION_AUTO : XDnsVpnService.ACTION_START
        );
        pendingAuto = false;

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        handler.postDelayed(
                this::updateUi,
                500
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == VPN_REQUEST
                && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void showAppExclusions() {
        PackageManager pm =
                getPackageManager();

        Intent launcher =
                new Intent(
                        Intent.ACTION_MAIN
                );

        launcher.addCategory(
                Intent.CATEGORY_LAUNCHER
        );

        List<ResolveInfo> resolved =
                pm.queryIntentActivities(
                        launcher,
                        0
                );

        LinkedHashMap<String, AppItem> unique =
                new LinkedHashMap<>();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null
                    || info.activityInfo
                    .packageName == null) {
                continue;
            }

            String pkg =
                    info.activityInfo
                            .packageName;

            if (pkg.equals(
                    getPackageName()
            )) {
                continue;
            }

            String label;

            try {
                CharSequence cs =
                        info.loadLabel(pm);

                label =
                        cs == null
                                ? pkg
                                : cs.toString();

            } catch (Exception e) {
                label = pkg;
            }

            unique.put(
                    pkg,
                    new AppItem(
                            label,
                            pkg
                    )
            );
        }

        ArrayList<AppItem> apps =
                new ArrayList<>(
                        unique.values()
                );

        apps.sort(
                Comparator.comparing(
                        a -> a.label
                                .toLowerCase(
                                        java.util.Locale.ROOT
                                )
                )
        );

        String[] labels =
                new String[apps.size()];

        boolean[] checked =
                new boolean[apps.size()];

        Set<String> saved =
                new HashSet<>(
                        prefs.getStringSet(
                                XDnsVpnService
                                        .KEY_EXCLUDED_APPS,
                                Collections.emptySet()
                        )
                );

        Set<String> selected =
                new HashSet<>(saved);

        for (int i = 0;
             i < apps.size();
             i++) {

            AppItem app =
                    apps.get(i);

            labels[i] =
                    app.label
                            + "\n"
                            + app.packageName;

            checked[i] =
                    saved.contains(
                            app.packageName
                    );
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Apps that bypass X-dns"
                )
                .setMultiChoiceItems(
                        labels,
                        checked,
                        (dialog,
                         which,
                         isChecked) -> {

                            String pkg =
                                    apps.get(which)
                                            .packageName;

                            if (isChecked) {
                                selected.add(pkg);
                            } else {
                                selected.remove(pkg);
                            }
                        }
                )
                .setNegativeButton(
                        "CANCEL",
                        null
                )
                .setPositiveButton(
                        "SAVE",
                        (dialog, which) -> {
                            prefs.edit()
                                    .putStringSet(
                                            XDnsVpnService
                                                    .KEY_EXCLUDED_APPS,
                                            new HashSet<>(
                                                    selected
                                            )
                                    )
                                    .apply();

                            Toast.makeText(
                                    this,
                                    XDnsVpnService
                                            .isRunning()
                                            ? "Saved. Restart X-dns to apply."
                                            : "Exclusions saved.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                )
                .show();
    }

    private void openTelegram() {
        try {
            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                    "https://t.me/anonymouskeys"
                            )
                    );

            startActivity(intent);

        } catch (Exception e) {
            copyTelegramLink();

            Toast.makeText(
                    this,
                    "Telegram link copied.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void copyTelegramLink() {
        ClipboardManager clipboard =
                (ClipboardManager)
                        getSystemService(
                                CLIPBOARD_SERVICE
                        );

        if (clipboard == null) {
            Toast.makeText(
                    this,
                    "Clipboard unavailable.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        "X-dns Telegram",
                        "https://t.me/anonymouskeys"
                )
        );

        Toast.makeText(
                this,
                "Copied: https://t.me/anonymouskeys",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void updateUi() {
        boolean running =
                XDnsVpnService.isRunning();

        if (running) {
            if (XDnsVpnService
                    .MODE_DRAGON_DPI
                    .equals(
                            XDnsVpnService
                                    .runningMode()
                    )) {

                status.setText(
                        "DRAGON DPI ACTIVE"
                );

            } else {
                status.setText(
                        "DOH VPN ACTIVE"
                );
            }

            status.setTextColor(
                    Color.rgb(
                            88,
                            214,
                            141
                    )
            );

            toggle.setText("STOP");

        } else {
            status.setText("VPN OFF");

            status.setTextColor(
                    Color.rgb(
                            255,
                            120,
                            120
                    )
            );

            toggle.setText("START");
        }

        String selected =
                prefs.getString(
                        XDnsVpnService.KEY_DOH_URL,
                        XDnsVpnService.DEFAULT_DOH
                );

        ResolverStore.Entry e =
                ResolverStore.get(
                        prefs,
                        selected
                );

        activeDoh.setText(
                "Selected DoH: "
                        + (e == null
                        ? ""
                        : e.statusPrefix()
                        + " ")
                        + selected
        );

        autoTuning = XDnsVpnService.isTuning();
        autoButton.setEnabled(!autoTuning);
        if (XDnsVpnService.isActive() && !running) {
            status.setText(autoTuning ? "AUTO TESTING" : "WAITING / RECOVERING");
            toggle.setText("STOP");
        }
        if (XDnsVpnService.isActive()) {
            autoResult.setText(prefs.getString(XDnsVpnService.KEY_LAST_START_STAGE, ""));
        } else if (!autoTuning) {
            String profile =
                    prefs.getString(
                            XDnsVpnService.KEY_AUTO_PROFILE,
                            ""
                    );

            if (!profile.isEmpty()) {
                String stage = prefs.getString(
                        XDnsVpnService.KEY_LAST_START_STAGE,
                        ""
                );

                autoResult.setText(
                        "Saved AUTO: " + profile
                                + (stage.isEmpty()
                                ? ""
                                : "\nLast VPN stage: " + stage)
                );
            }
        }

        if (running
                && XDnsVpnService
                .MODE_DRAGON_DPI
                .equals(
                        XDnsVpnService
                                .runningMode()
                )) {

            long[] d =
                    XDnsVpnService
                            .getDpiStats();

            stats.setText(
                    "Strategy: "
                            + XDnsVpnService
                            .runningStrategy()
                            + "\nTUN TX: "
                            + DnsLog.formatBytes(
                            d.length > 1
                                    ? d[1]
                                    : 0
                    )
                            + "   RX: "
                            + DnsLog.formatBytes(
                            d.length > 3
                                    ? d[3]
                                    : 0
                    )
                            + "\nPackets TX/RX: "
                            + (d.length > 0
                            ? d[0]
                            : 0)
                            + " / "
                            + (d.length > 2
                            ? d[2]
                            : 0)
            );

        } else {
            stats.setText(
                    DnsLog.statsText()
            );
        }

        routeHealth.setText(
                RouteMemory.healthText(
                        prefs
                )
        );

        logText.setText(
                DnsLog.getText()
        );

        updateSpeed();
    }

    private void updateSpeed() {
        long now =
                System.currentTimeMillis();

        long rx =
                TrafficStats
                        .getTotalRxBytes();

        long tx =
                TrafficStats
                        .getTotalTxBytes();

        if (rx < 0 || tx < 0) {
            speed.setText(
                    "Device speed: unavailable"
            );
            return;
        }

        if (lastRx >= 0
                && lastTx >= 0
                && lastSpeedSample > 0
                && now > lastSpeedSample) {

            double seconds =
                    (now
                            - lastSpeedSample)
                            / 1000.0;

            long rxRate =
                    Math.max(
                            0,
                            (long) (
                                    (rx - lastRx)
                                            / seconds
                            )
                    );

            long txRate =
                    Math.max(
                            0,
                            (long) (
                                    (tx - lastTx)
                                            / seconds
                            )
                    );

            speed.setText(
                    "Device speed: ↓ "
                            + formatRate(rxRate)
                            + "   ↑ "
                            + formatRate(txRate)
            );

        } else {
            speed.setText(
                    "Device speed: measuring…"
            );
        }

        lastRx = rx;
        lastTx = tx;
        lastSpeedSample = now;
    }

    private final Runnable uiTicker =
            new Runnable() {
                @Override
                public void run() {
                    updateUi();

                    handler.postDelayed(
                            this,
                            1000
                    );
                }
            };

    private String formatRate(
            long bytesPerSecond
    ) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond
                    + " B/s";
        }

        double kib =
                bytesPerSecond / 1024.0;

        if (kib < 1024) {
            return String.format(
                    java.util.Locale.US,
                    "%.1f KiB/s",
                    kib
            );
        }

        return String.format(
                java.util.Locale.US,
                "%.2f MiB/s",
                kib / 1024.0
        );
    }

    private TextView section(
            String name
    ) {
        TextView view =
                text(
                        name,
                        18,
                        Color.WHITE
                );

        view.setPadding(
                0,
                dp(28),
                0,
                dp(10)
        );

        return view;
    }

    private TextView text(
            String value,
            float size,
            int color
    ) {
        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        return view;
    }

    private Button button(
            String label
    ) {
        Button button =
                new Button(this);

        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);

        return button;
    }

    private LinearLayout horizontal() {
        LinearLayout row =
                new LinearLayout(this);

        row.setOrientation(
                LinearLayout.HORIZONTAL
        );

        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        dp(52),
                        1f
                );

        params.setMargins(
                dp(2),
                dp(4),
                dp(2),
                0
        );

        return params;
    }

    private LinearLayout.LayoutParams fullWidth(
            int height
    ) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
    }

    private LinearLayout.LayoutParams matchButtonParams() {
        LinearLayout.LayoutParams params =
                fullWidth(dp(56));

        params.setMargins(
                0,
                dp(4),
                0,
                0
        );

        return params;
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private static String safeMessage(
            Exception e
    ) {
        String message =
                e.getMessage();

        return message == null
                || message.trim().isEmpty()
                ? e.getClass()
                .getSimpleName()
                : message;
    }

    private static final class AppItem {
        final String label;
        final String packageName;

        AppItem(
                String label,
                String packageName
        ) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
