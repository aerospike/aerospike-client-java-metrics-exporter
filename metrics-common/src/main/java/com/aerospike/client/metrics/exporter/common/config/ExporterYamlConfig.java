/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Strict, typed representation of the exporter YAML document.
 *
 * <p>Fields remain nullable here because defaults and conditional validation
 * are applied only after YAML has been merged with environment variables,
 * system properties and builder values.</p>
 */
public final class ExporterYamlConfig {
    private static final String ROOT = "aerospike_metrics_exporter";

    private final Exporter exporter;

    private ExporterYamlConfig(Exporter exporter) {
        this.exporter = exporter;
    }

    static ExporterYamlConfig parse(Object loaded) {
        if (loaded == null) {
            return new ExporterYamlConfig(null);
        }
        Map<String, Object> root = mapping(loaded, "YAML root");
        rejectUnknown(root, Collections.singleton(ROOT), "");
        return new ExporterYamlConfig(
            root.get(ROOT) == null ? null : Exporter.parse(root.get(ROOT), ROOT)
        );
    }

    public Exporter exporter() {
        return exporter;
    }

    /**
     * Convert validated source values to the flattened keys used by the
     * existing source-precedence merger.
     */
    public Map<String, String> toFlatMap() {
        if (exporter == null) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        exporter.flatten(ROOT + ".", values);
        return Collections.unmodifiableMap(values);
    }

    public static final class Exporter {
        private static final Set<String> FIELDS = Set.of(
            "snapshot_stale_timeout", "reload", "prometheus", "otel"
        );

        private final String snapshotStaleTimeout;
        private final Reload reload;
        private final Prometheus prometheus;
        private final Otel otel;

        private Exporter(
                String snapshotStaleTimeout,
                Reload reload,
                Prometheus prometheus,
                Otel otel
        ) {
            this.snapshotStaleTimeout = snapshotStaleTimeout;
            this.reload = reload;
            this.prometheus = prometheus;
            this.otel = otel;
        }

        static Exporter parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new Exporter(
                string(map, "snapshot_stale_timeout", field),
                nested(map, "reload", field, Reload::parse),
                nested(map, "prometheus", field, Prometheus::parse),
                nested(map, "otel", field, Otel::parse)
            );
        }

        public String snapshotStaleTimeout() {
            return snapshotStaleTimeout;
        }

        public Reload reload() {
            return reload;
        }

        public Prometheus prometheus() {
            return prometheus;
        }

        public Otel otel() {
            return otel;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "snapshot_stale_timeout", snapshotStaleTimeout);
            if (reload != null) {
                reload.flatten(prefix + "reload.", values);
            }
            if (prometheus != null) {
                prometheus.flatten(prefix + "prometheus.", values);
            }
            if (otel != null) {
                otel.flatten(prefix + "otel.", values);
            }
        }
    }

    public static final class Reload {
        private static final Set<String> FIELDS = Set.of("enabled", "interval");

        private final Boolean enabled;
        private final String interval;

        private Reload(Boolean enabled, String interval) {
            this.enabled = enabled;
            this.interval = interval;
        }

        static Reload parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new Reload(
                bool(map, "enabled", field),
                string(map, "interval", field)
            );
        }

        public Boolean enabled() {
            return enabled;
        }

        public String interval() {
            return interval;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "enabled", enabled);
            put(values, prefix + "interval", interval);
        }
    }

    public static final class Prometheus {
        private static final Set<String> FIELDS = Set.of(
            "bind_address", "port", "path", "http_timeout", "basic_auth", "tls"
        );

        private final String bindAddress;
        private final Integer port;
        private final String path;
        private final String httpTimeout;
        private final BasicAuth basicAuth;
        private final PrometheusTls tls;

        private Prometheus(
                String bindAddress,
                Integer port,
                String path,
                String httpTimeout,
                BasicAuth basicAuth,
                PrometheusTls tls
        ) {
            this.bindAddress = bindAddress;
            this.port = port;
            this.path = path;
            this.httpTimeout = httpTimeout;
            this.basicAuth = basicAuth;
            this.tls = tls;
        }

        static Prometheus parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new Prometheus(
                string(map, "bind_address", field),
                integer(map, "port", field),
                string(map, "path", field),
                string(map, "http_timeout", field),
                nested(map, "basic_auth", field, BasicAuth::parse),
                nested(map, "tls", field, PrometheusTls::parse)
            );
        }

        public String bindAddress() {
            return bindAddress;
        }

        public Integer port() {
            return port;
        }

        public String path() {
            return path;
        }

        public String httpTimeout() {
            return httpTimeout;
        }

        public BasicAuth basicAuth() {
            return basicAuth;
        }

        public PrometheusTls tls() {
            return tls;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "bind_address", bindAddress);
            put(values, prefix + "port", port);
            put(values, prefix + "path", path);
            put(values, prefix + "http_timeout", httpTimeout);
            if (basicAuth != null) {
                basicAuth.flatten(prefix + "basic_auth.", values);
            }
            if (tls != null) {
                tls.flatten(prefix + "tls.", values);
            }
        }
    }

    public static final class BasicAuth {
        private static final Set<String> FIELDS = Set.of("username", "password");

        private final String username;
        private final String password;

        private BasicAuth(String username, String password) {
            this.username = username;
            this.password = password;
        }

        static BasicAuth parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new BasicAuth(
                string(map, "username", field),
                string(map, "password", field)
            );
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "username", username);
            put(values, prefix + "password", password);
        }
    }

    public static final class PrometheusTls {
        private static final Set<String> FIELDS = Set.of(
            "enabled", "cert_file", "key_file", "key_password",
            "client_auth", "client_ca_file"
        );

        private final Boolean enabled;
        private final String certFile;
        private final String keyFile;
        private final String keyPassword;
        private final String clientAuth;
        private final String clientCaFile;

        private PrometheusTls(
                Boolean enabled,
                String certFile,
                String keyFile,
                String keyPassword,
                String clientAuth,
                String clientCaFile
        ) {
            this.enabled = enabled;
            this.certFile = certFile;
            this.keyFile = keyFile;
            this.keyPassword = keyPassword;
            this.clientAuth = clientAuth;
            this.clientCaFile = clientCaFile;
        }

        static PrometheusTls parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new PrometheusTls(
                bool(map, "enabled", field),
                string(map, "cert_file", field),
                string(map, "key_file", field),
                string(map, "key_password", field),
                string(map, "client_auth", field),
                string(map, "client_ca_file", field)
            );
        }

        public Boolean enabled() {
            return enabled;
        }

        public String certFile() {
            return certFile;
        }

        public String keyFile() {
            return keyFile;
        }

        public String keyPassword() {
            return keyPassword;
        }

        public String clientAuth() {
            return clientAuth;
        }

        public String clientCaFile() {
            return clientCaFile;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "enabled", enabled);
            put(values, prefix + "cert_file", certFile);
            put(values, prefix + "key_file", keyFile);
            put(values, prefix + "key_password", keyPassword);
            put(values, prefix + "client_auth", clientAuth);
            put(values, prefix + "client_ca_file", clientCaFile);
        }
    }

    public static final class Otel {
        private static final Set<String> FIELDS = Set.of(
            "service_name", "endpoint", "protocol", "headers", "timeout",
            "export_interval", "compression", "temporality", "proxy", "tls"
        );

        private final String serviceName;
        private final String endpoint;
        private final String protocol;
        private final Map<String, String> headers;
        private final String timeout;
        private final String exportInterval;
        private final String compression;
        private final String temporality;
        private final Proxy proxy;
        private final OtelTls tls;

        private Otel(
                String serviceName,
                String endpoint,
                String protocol,
                Map<String, String> headers,
                String timeout,
                String exportInterval,
                String compression,
                String temporality,
                Proxy proxy,
                OtelTls tls
        ) {
            this.serviceName = serviceName;
            this.endpoint = endpoint;
            this.protocol = protocol;
            this.headers = headers;
            this.timeout = timeout;
            this.exportInterval = exportInterval;
            this.compression = compression;
            this.temporality = temporality;
            this.proxy = proxy;
            this.tls = tls;
        }

        static Otel parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new Otel(
                string(map, "service_name", field),
                string(map, "endpoint", field),
                string(map, "protocol", field),
                stringMap(map, "headers", field),
                string(map, "timeout", field),
                string(map, "export_interval", field),
                string(map, "compression", field),
                string(map, "temporality", field),
                nested(map, "proxy", field, Proxy::parse),
                nested(map, "tls", field, OtelTls::parse)
            );
        }

        public String serviceName() {
            return serviceName;
        }

        public String endpoint() {
            return endpoint;
        }

        public String protocol() {
            return protocol;
        }

        public Map<String, String> headers() {
            return headers;
        }

        public String timeout() {
            return timeout;
        }

        public String exportInterval() {
            return exportInterval;
        }

        public String compression() {
            return compression;
        }

        public String temporality() {
            return temporality;
        }

        public Proxy proxy() {
            return proxy;
        }

        public OtelTls tls() {
            return tls;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "service_name", serviceName);
            put(values, prefix + "endpoint", endpoint);
            put(values, prefix + "protocol", protocol);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                put(values, prefix + "headers." + header.getKey(), header.getValue());
            }
            put(values, prefix + "timeout", timeout);
            put(values, prefix + "export_interval", exportInterval);
            put(values, prefix + "compression", compression);
            put(values, prefix + "temporality", temporality);
            if (proxy != null) {
                proxy.flatten(prefix + "proxy.", values);
            }
            if (tls != null) {
                tls.flatten(prefix + "tls.", values);
            }
        }
    }

    public static final class Proxy {
        private static final Set<String> FIELDS = Collections.singleton("url");

        private final String url;

        private Proxy(String url) {
            this.url = url;
        }

        static Proxy parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new Proxy(string(map, "url", field));
        }

        public String url() {
            return url;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "url", url);
        }
    }

    public static final class OtelTls {
        private static final Set<String> FIELDS = Set.of(
            "ca_file", "client_cert_file", "client_key_file", "client_key_password"
        );

        private final String caFile;
        private final String clientCertFile;
        private final String clientKeyFile;
        private final String clientKeyPassword;

        private OtelTls(
                String caFile,
                String clientCertFile,
                String clientKeyFile,
                String clientKeyPassword
        ) {
            this.caFile = caFile;
            this.clientCertFile = clientCertFile;
            this.clientKeyFile = clientKeyFile;
            this.clientKeyPassword = clientKeyPassword;
        }

        static OtelTls parse(Object value, String field) {
            Map<String, Object> map = mapping(value, field);
            rejectUnknown(map, FIELDS, field);
            return new OtelTls(
                string(map, "ca_file", field),
                string(map, "client_cert_file", field),
                string(map, "client_key_file", field),
                string(map, "client_key_password", field)
            );
        }

        public String caFile() {
            return caFile;
        }

        public String clientCertFile() {
            return clientCertFile;
        }

        public String clientKeyFile() {
            return clientKeyFile;
        }

        public String clientKeyPassword() {
            return clientKeyPassword;
        }

        void flatten(String prefix, Map<String, String> values) {
            put(values, prefix + "ca_file", caFile);
            put(values, prefix + "client_cert_file", clientCertFile);
            put(values, prefix + "client_key_file", clientKeyFile);
            put(values, prefix + "client_key_password", clientKeyPassword);
        }
    }

    private interface Parser<T> {
        T parse(Object value, String field);
    }

    private static <T> T nested(
            Map<String, Object> map,
            String key,
            String parent,
            Parser<T> parser
    ) {
        Object value = map.get(key);
        return value == null ? null : parser.parse(value, qualify(parent, key));
    }

    private static Map<String, String> stringMap(
            Map<String, Object> map,
            String key,
            String parent
    ) {
        Object value = map.get(key);
        if (value == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> source = mapping(value, qualify(parent, key));
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() != null && !(entry.getValue() instanceof String)) {
                throw wrongType(qualify(qualify(parent, key), entry.getKey()), "a string");
            }
            if (entry.getValue() != null) {
                result.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String string(Map<String, Object> map, String key, String parent) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw wrongType(qualify(parent, key), "a string");
        }
        return (String) value;
    }

    private static Boolean bool(Map<String, Object> map, String key, String parent) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean)) {
            throw wrongType(qualify(parent, key), "true or false");
        }
        return (Boolean) value;
    }

    private static Integer integer(Map<String, Object> map, String key, String parent) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer)) {
            throw wrongType(qualify(parent, key), "an integer");
        }
        return (Integer) value;
    }

    private static Map<String, Object> mapping(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            throw wrongType(field, "a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(field + " contains a non-string field name");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void rejectUnknown(
            Map<String, Object> values,
            Set<String> allowed,
            String parent
    ) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                    "Unknown exporter YAML field: " + qualify(parent, key)
                );
            }
        }
    }

    private static IllegalArgumentException wrongType(String field, String expected) {
        return new IllegalArgumentException(
            "Exporter YAML field " + field + " must be " + expected
        );
    }

    private static String qualify(String parent, String child) {
        return parent == null || parent.isEmpty() ? child : parent + "." + child;
    }

    private static void put(Map<String, String> values, String key, Object value) {
        if (value != null) {
            values.put(key, String.valueOf(value));
        }
    }
}
