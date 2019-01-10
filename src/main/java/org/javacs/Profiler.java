package org.javacs;

import com.sun.source.util.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

class Profiler implements TaskListener {
    Set<URI> files = new HashSet<>();
    Map<URI, Map<TaskEvent.Kind, Instant>> started = new HashMap<>();
    Map<TaskEvent.Kind, Duration> profile = new EnumMap<>(TaskEvent.Kind.class);

    @Override
    public void started(TaskEvent e) {
        var uri = e.getSourceFile().toUri();
        var kind = e.getKind();
        var fileStarted = started.computeIfAbsent(uri, __ -> new EnumMap<>(TaskEvent.Kind.class));
        fileStarted.put(kind, Instant.now());
        files.add(uri);
        // TODO show the user a warning when we're compiling a lot of files that aren't in the classpath
    }

    @Override
    public void finished(TaskEvent e) {
        var uri = e.getSourceFile().toUri();
        var kind = e.getKind();
        var fileStarted = started.computeIfAbsent(uri, __ -> new HashMap<>());
        var start = fileStarted.getOrDefault(kind, Instant.now());
        var elapsed = Duration.between(start, Instant.now());
        var soFar = profile.getOrDefault(kind, Duration.ZERO);
        var total = soFar.plus(elapsed);
        profile.put(kind, total);
    }

    void print() {
        var lines = new StringJoiner("; ");
        for (var kind : TaskEvent.Kind.values()) {
            if (!profile.containsKey(kind)) continue;
            var elapsed = profile.get(kind);
            var s = elapsed.getSeconds() + elapsed.getNano() / 1000.0 / 1000.0 / 1000.0;
            lines.add(String.format("%s: %.3fs", kind, s));
        }
        if (files.size() <= 3) {
            var join = new StringJoiner(", ");
            for (var f : files) join.add(Parser.fileName(f));
            LOG.info(String.format("Compiled %s: %s", join, lines));
        } else {
            LOG.info(String.format("Compiled %d files: %s", files.size(), lines));
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
