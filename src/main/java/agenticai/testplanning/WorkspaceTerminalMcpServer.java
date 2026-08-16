package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Supplies a workspace-rooted shell configuration for the embedded terminal surface. */
public final class WorkspaceTerminalMcpServer {
    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-workspace-terminal-mcp")
                        .put("version", "1.0").put("mode", "embedded-pty"))
                .put("tools", new JSONArray().put("terminal.start").put("terminal.send_input")
                        .put("terminal.cancel").put("terminal.status"));
    }

    public TerminalLaunch launch(Path requestedDirectory) throws Exception {
        Path directory = requestedDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Terminal directory is not available: " + directory);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win")
                ? List.of("powershell.exe", "-NoLogo", "-NoExit")
                : List.of(System.getenv().getOrDefault("SHELL", "/bin/sh"));
        return new TerminalLaunch(directory, command);
    }

    public record TerminalLaunch(Path directory, List<String> command) {
    }
}
