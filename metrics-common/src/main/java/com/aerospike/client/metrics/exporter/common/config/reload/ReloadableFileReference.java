/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.nio.file.Path;

/**
 * Identifies why one fixed startup path is being watched.
 *
 * <p>The reference contains no credential value. Multiple logical settings
 * may legitimately refer to the same physical path.</p>
 */
public record ReloadableFileReference(Backend backend, String field, Path path) {
    public enum Backend {
        PROMETHEUS,
        OTEL
    }

    public ReloadableFileReference {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be empty");
        }
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        path = path.toAbsolutePath().normalize();
    }
}
