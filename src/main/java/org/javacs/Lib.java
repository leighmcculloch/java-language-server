package org.javacs;

import java.io.File;
import java.lang.System;
import java.util.Optional;
import java.nio.file.*;

class Lib {
    static Optional<Path> srcZipPath() {
        return Optional.ofNullable(System.getenv("JAVA_HOME"))
            .flatMap(home -> Optional.of(Paths.get(home).resolve("lib/src.zip")))
            .flatMap(path -> {
                if (path.toFile().exists()) {
                    return Optional.of(path);
                } else {
                    return Optional.empty();
                }
            });
    }

    static final Optional<Path> SRC_ZIP = srcZipPath();
}
