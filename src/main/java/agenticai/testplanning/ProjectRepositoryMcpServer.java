package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Read-only local/Git project inspection tools for the Test Planning Agent. */
public final class ProjectRepositoryMcpServer {
    private static final int MAX_FILES = 900;
    private static final int MAX_PROJECT_CHARACTERS = 350_000;
    private static final int MAX_FILE_CHARACTERS = 24_000;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "target", "build", "dist", "release",
            "coverage", ".gradle", ".mvn", "vendor", "__pycache__");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs",
            "cs", "php", "scala", "sql", "graphql", "gql", "xml", "json", "yaml", "yml",
            "properties", "toml", "gradle", "md", "txt", "feature", "html", "css", "scss",
            "vue", "svelte", "sh", "ps1", "bat", "cmd");

    public JSONObject inspect(String source, Path cacheRoot, Consumer<String> log) throws Exception {
        Path root;
        String repositoryUrl = "";
        if (isGitSource(source)) {
            repositoryUrl = source.trim();
            root = cloneOrRefresh(repositoryUrl, cacheRoot, log);
        } else {
            root = Path.of(source.trim()).toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project directory does not exist: " + root);
        }
        if (log != null) log.accept("Inspecting project: " + root);
        JSONArray files = new JSONArray();
        StringBuilder content = new StringBuilder();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(root, path))
                    .filter(this::isTextFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_FILES)
                    .toList();
            for (Path file : candidates) {
                Path relative = root.relativize(file);
                long size = Files.size(file);
                files.put(new JSONObject().put("path", relative.toString()).put("sizeBytes", size));
                if (content.length() >= MAX_PROJECT_CHARACTERS || size > 1_500_000) continue;
                String value;
                try {
                    value = Files.readString(file, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    continue;
                }
                if (value.length() > MAX_FILE_CHARACTERS) value = value.substring(0, MAX_FILE_CHARACTERS);
                content.append("\n\n[PROJECT FILE: ").append(relative).append("]\n").append(value);
            }
        }
        String commit = gitValue(root, "rev-parse", "HEAD");
        return new JSONObject()
                .put("sourceType", repositoryUrl.isBlank() ? "localProject" : "gitRepository")
                .put("name", root.getFileName() == null ? root.toString() : root.getFileName().toString())
                .put("path", root.toString())
                .put("repositoryUrl", repositoryUrl)
                .put("commit", commit)
                .put("files", files)
                .put("fileCount", files.length())
                .put("truncated", content.length() >= MAX_PROJECT_CHARACTERS)
                .put("content", content.toString());
    }

    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-project-repository-mcp")
                        .put("version", "1.0").put("mode", "in-process").put("readOnly", true))
                .put("tools", new JSONArray().put("project.inspect_local").put("project.clone_and_inspect")
                        .put("project.list_files").put("project.search_code"));
    }

    public boolean isGitSource(String value) {
        String source = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return source.startsWith("git@")
                || source.startsWith("ssh://")
                || source.endsWith(".git")
                || source.matches("https?://(www\\.)?(github|gitlab|bitbucket)\\.[^/]+/.+");
    }

    private Path cloneOrRefresh(String repositoryUrl, Path cacheRoot, Consumer<String> log) throws Exception {
        Files.createDirectories(cacheRoot);
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(repositoryUrl.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        Path destination = cacheRoot.resolve("repo-" + key).toAbsolutePath().normalize();
        if (!destination.startsWith(cacheRoot.toAbsolutePath().normalize())) {
            throw new IllegalStateException("Unsafe repository cache path.");
        }
        if (!Files.exists(destination.resolve(".git"))) {
            if (log != null) log.accept("Cloning Git repository (read-only analysis cache)...");
            run(List.of("git", "clone", "--depth", "1", repositoryUrl, destination.toString()), cacheRoot);
        } else if (log != null) {
            log.accept("Using cached Git repository: " + destination);
        }
        return destination;
    }

    private void run(List<String> command, Path directory) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Git command failed: " + output.toString().trim());
    }

    private String gitValue(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isSkipped(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (SKIPPED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("dockerfile", "makefile", "pom.xml", "package.json").contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
