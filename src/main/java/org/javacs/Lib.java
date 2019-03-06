package org.javacs;

import java.io.File;
import java.lang.System;
import java.util.Optional;
import java.util.Arrays;
import java.nio.file.*;

class Lib {
    static Optional<Path> srcZipPath() {
        return Optional.ofNullable(System.getenv("JAVA_HOME"))
            .map(home -> {
                return Arrays.asList(new Path[]{
                    Paths.get(home).resolve("lib/src.zip"),
                    Paths.get(home).resolve("src.zip"),
                });
            })
            .flatMap(paths -> {
                for (Path path : paths) {
                    if (path.toFile().exists()) {
                        return Optional.of(path);
                    }
                }
                return Optional.empty();
            });
    }

    static final Optional<Path> SRC_ZIP = srcZipPath();
}
