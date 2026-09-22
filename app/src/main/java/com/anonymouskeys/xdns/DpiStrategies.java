package com.anonymouskeys.xdns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DpiStrategies {

    private DpiStrategies() {}

    public static final class Preset {
        public final String id;
        public final String name;
        public final List<String> args;

        Preset(String id, String name, List<String> args) {
            this.id = id;
            this.name = name;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
        }
    }

    public static List<Preset> candidates(int fakeTtl) {
        String ttl = String.valueOf(Math.max(1, Math.min(255, fakeTtl)));
        List<Preset> out = new ArrayList<>();

        out.add(new Preset("direct", "Direct SOCKS", Collections.emptyList()));
        out.add(new Preset("split_sni", "Split SNI",
                Arrays.asList("--split", "1+s")));
        out.add(new Preset("split_sni_middle", "Split SNI middle",
                Arrays.asList("--split", "0+sm")));
        out.add(new Preset("split_multi", "Multi split",
                Arrays.asList("--split", "1+s", "--split", "3+s")));
        out.add(new Preset("disorder", "Disorder",
                Arrays.asList("--disorder", "1")));
        out.add(new Preset("disorder_sni", "Split + disorder",
                Arrays.asList("--split", "1+s", "--disorder", "3+s")));
        out.add(new Preset("fake", "Fake TTL",
                Arrays.asList("--fake", "-1", "--ttl", ttl)));
        out.add(new Preset("fake_random", "Fake TLS random",
                Arrays.asList("--fake", "-1", "--ttl", ttl, "--fake-tls-mod", "rand")));
        out.add(new Preset("fake_disorder", "Fake + disorder",
                Arrays.asList("--disorder", "1", "--fake", "-1", "--ttl", ttl)));
        out.add(new Preset("fake_split", "Fake + split",
                Arrays.asList(
                        "--split", "1+s",
                        "--disorder", "3+s",
                        "--fake", "-1",
                        "--ttl", ttl
                )));
        out.add(new Preset("disoob", "Disorder OOB",
                Arrays.asList("--disoob", "3+s", "--disorder", "1")));
        out.add(new Preset("tls_record_sni", "TLS record split",
                Arrays.asList("--tlsrec", "3+s")));
        out.add(new Preset("auto_compat", "Auto compatible",
                Arrays.asList(
                        "--auto=torst", "--split", "1+s",
                        "--auto=torst", "--split", "1+s", "--disorder", "3+s",
                        "--auto=ssl_err", "--tlsrec", "3+s",
                        "--auto=torst", "--disorder", "1"
                )));
        out.add(new Preset("maximum", "Dragon Maximum / Auto",
                Arrays.asList(
                        "--disorder", "1", "--fake", "-1",
                        "--auto=torst",
                        "--split", "1+s",
                        "--disorder", "3+s",
                        "--fake", "-1",
                        "--ttl", ttl,
                        "--auto=ssl_err",
                        "--fake", "-1",
                        "--ttl", ttl,
                        "--fake-tls-mod", "rand",
                        "--auto=torst",
                        "--tlsrec", "3+s",
                        "--disorder", "1",
                        "--auto=torst",
                        "--disoob", "3+s",
                        "--disorder", "1",
                        "--auto=torst",
                        "--split", "1+s",
                        "--split", "3+s",
                        "--disorder", "5+s"
                )));

        return out;
    }

    public static Preset find(String id, int fakeTtl) {
        for (Preset preset : candidates(fakeTtl)) {
            if (preset.id.equals(id)) return preset;
        }
        for (Preset preset : candidates(fakeTtl)) {
            if ("maximum".equals(preset.id)) return preset;
        }
        throw new IllegalStateException("No DPI presets");
    }
}
