package cz.loplex.xembed.demo;

import java.nio.file.Path;

final class DemoPaths {

    private DemoPaths() {
    }

    static Path socketPath() {
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        Path dir = runtimeDir != null ? Path.of(runtimeDir) : Path.of(System.getProperty("java.io.tmpdir"));
        return dir.resolve("xembed-demo.sock");
    }
}
