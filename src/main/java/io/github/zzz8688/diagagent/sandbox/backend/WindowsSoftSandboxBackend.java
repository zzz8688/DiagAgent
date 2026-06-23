package io.github.zzz8688.diagagent.sandbox.backend;

import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionRequest;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionResult;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import io.github.zzz8688.diagagent.sandbox.isolation.SandboxProfile;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WindowsSoftSandboxBackend implements SandboxBackend {

    private static final Pattern URL_HOST_PATTERN = Pattern.compile("https?://([a-zA-Z0-9.-]+)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s]+)");
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "set-content", "add-content", "out-file", "copy-item", "move-item", "remove-item",
            "rename-item", "new-item", "mkdir", "ni", "set-item", "clear-content"
    );
    private static final Set<String> READ_COMMANDS = Set.of(
            "get-childitem", "dir", "ls", "get-content", "type", "gc", "test-path", "get-location",
            "pwd", "select-string", "get-item", "resolve-path"
    );
    private static final Set<String> NETWORK_COMMANDS = Set.of(
            "invoke-webrequest", "invoke-restmethod", "curl", "wget", "test-netconnection",
            "ping", "resolve-dnsname", "nslookup"
    );
    private static final Set<String> PROCESS_SPAWN_COMMANDS = Set.of(
            "start-process", "powershell", "pwsh", "cmd", "cscript", "wscript", "python", "py",
            "node", "npm", "bash", "sh"
    );

    @Override
    public SandboxBackendType type() {
        return SandboxBackendType.WINDOWS_SOFT;
    }

    @Override
    public boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public SandboxExecutionResult execute(SandboxExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!available()) {
            return SandboxExecutionResult.failed(request.executionId(), "Windows soft sandbox is only available on Windows.");
        }
        if (request.targetType() != SandboxTargetType.COMMAND && request.targetType() != SandboxTargetType.SCRIPT) {
            return SandboxExecutionResult.failed(
                    request.executionId(),
                    "Windows soft sandbox currently supports COMMAND and SCRIPT targets only."
            );
        }

        SandboxProfile profile = request.profile();
        String command = extractCommand(request.arguments());
        if (command.isBlank()) {
            return SandboxExecutionResult.failed(request.executionId(), "Missing command argument for sandbox execution.");
        }

        try {
            Path workingDirectory = resolveWorkingDirectory(request.workingDirectory());
            List<CommandSegment> segments = parseCommandSegments(command);
            validateFilesystemConstraints(workingDirectory, profile, command, segments);
            validateNetworkConstraints(profile, command, segments);
            return runProcess(request.executionId(), command, workingDirectory, resolveTimeout(request, profile), profile);
        } catch (Exception e) {
            return SandboxExecutionResult.failed(request.executionId(), e.getMessage());
        }
    }

    private String extractCommand(Map<String, Object> arguments) {
        Object command = arguments.get("command");
        return command == null ? "" : String.valueOf(command).trim();
    }

    private Path resolveWorkingDirectory(String workingDirectory) {
        String fallback = System.getProperty("user.dir", ".");
        String raw = (workingDirectory == null || workingDirectory.isBlank()) ? fallback : workingDirectory;
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private Duration resolveTimeout(SandboxExecutionRequest request, SandboxProfile profile) {
        Duration timeout = request.timeout() == null ? profile.maxWallClockTime() : request.timeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private void validateFilesystemConstraints(Path workingDirectory,
                                               SandboxProfile profile,
                                               String command,
                                               List<CommandSegment> segments) {
        ensureNotUnderDeniedPath(workingDirectory, profile.denyReadPaths(), "Working directory is denied for reads");
        ensureNotUnderDeniedPath(workingDirectory, profile.denyWritePaths(), "Working directory is denied for writes");

        List<String> allowedRoots = new ArrayList<>();
        allowedRoots.addAll(profile.allowReadPaths());
        allowedRoots.addAll(profile.allowWritePaths());
        if (!allowedRoots.isEmpty() && allowedRoots.stream().noneMatch(allowed -> isUnderPath(workingDirectory, allowed))) {
            throw new IllegalArgumentException("Working directory is outside sandbox allowlist.");
        }

        if (segments.stream().anyMatch(segment -> segment.category == CommandCategory.PROCESS_SPAWN)) {
            throw new IllegalArgumentException("Sandbox blocks process-spawning commands such as Start-Process, python or nested shells.");
        }

        if (profile.readOnlyFilesystem() && segments.stream().anyMatch(segment -> segment.category == CommandCategory.WRITE)) {
            throw new IllegalArgumentException("Read-only sandbox profile blocks write-like PowerShell commands.");
        }

        validateReferencedPaths(workingDirectory, profile, segments);
    }

    private void validateNetworkConstraints(SandboxProfile profile,
                                            String command,
                                            List<CommandSegment> segments) {
        if (!profile.networkEnabled() && segments.stream().anyMatch(segment -> segment.category == CommandCategory.NETWORK)) {
            throw new IllegalArgumentException("Sandbox profile disables network access for this command.");
        }

        List<String> hosts = extractHosts(command);
        for (String deniedDomain : profile.deniedDomains()) {
            if (hosts.stream().anyMatch(host -> matchesDomain(host, deniedDomain))) {
                throw new IllegalArgumentException("Sandbox profile blocks denied domain: " + deniedDomain);
            }
        }
        if (!profile.allowedDomains().isEmpty() && !hosts.isEmpty()) {
            boolean allowed = hosts.stream()
                    .allMatch(host -> profile.allowedDomains().stream().anyMatch(domain -> matchesDomain(host, domain)));
            if (!allowed) {
                throw new IllegalArgumentException("Sandbox profile blocks network targets outside allowed domains.");
            }
        }
    }

    private void validateReferencedPaths(Path workingDirectory,
                                         SandboxProfile profile,
                                         List<CommandSegment> segments) {
        for (CommandSegment segment : segments) {
            for (String token : segment.pathCandidates()) {
                Path candidate = resolveReferencedPath(workingDirectory, token);
                if (candidate == null) {
                    continue;
                }
                ensureNotUnderDeniedPath(candidate, profile.denyReadPaths(), "Referenced path is denied for reads");
                ensureNotUnderDeniedPath(candidate, profile.denyWritePaths(), "Referenced path is denied for writes");
                if (segment.category == CommandCategory.WRITE) {
                    ensureAllowed(candidate, profile.allowWritePaths(), "Referenced write path is outside sandbox allowlist.");
                } else {
                    ensureAllowed(candidate, union(profile.allowReadPaths(), profile.allowWritePaths()), "Referenced path is outside sandbox allowlist.");
                }
            }
        }
    }

    private SandboxExecutionResult runProcess(String executionId,
                                              String command,
                                              Path workingDirectory,
                                              Duration timeout,
                                              SandboxProfile profile) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                resolvePowerShellExecutable(),
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command
        );
        processBuilder.directory(workingDirectory.toFile());
        Map<String, String> environment = processBuilder.environment();
        mergeEnvironmentAllowlist(environment, profile.environmentAllowlist());

        Process process = processBuilder.start();
        long outputLimit = profile.maxOutputBytes();

        CountDownLatch latch = new CountDownLatch(2);
        StreamCapture stdoutCapture = new StreamCapture(process.getInputStream(), outputLimit, latch);
        StreamCapture stderrCapture = new StreamCapture(process.getErrorStream(), outputLimit, latch);
        Thread stdoutThread = new Thread(stdoutCapture, "sandbox-stdout-capture");
        Thread stderrThread = new Thread(stderrCapture, "sandbox-stderr-capture");
        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            latch.await(2, TimeUnit.SECONDS);
            String stdout = stdoutCapture.content();
            String stderr = joinMessages(stderrCapture.content(), "Process timed out after " + timeout.toMillis() + " ms.");
            return new SandboxExecutionResult(
                    executionId,
                    false,
                    true,
                    false,
                    -1,
                    stdout,
                    stderr,
                    digest(stdout + "\n" + stderr)
            );
        }

        latch.await(2, TimeUnit.SECONDS);
        int exitCode = process.exitValue();
        String stdout = stdoutCapture.content();
        String stderr = stderrCapture.content();
        return new SandboxExecutionResult(
                executionId,
                exitCode == 0,
                false,
                false,
                exitCode,
                stdout,
                stderr,
                digest(stdout + "\n" + stderr)
        );
    }

    private void mergeEnvironmentAllowlist(Map<String, String> targetEnvironment, Map<String, String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : allowlist.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            targetEnvironment.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
    }

    private String resolvePowerShellExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            return "powershell.exe";
        }
        return Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
    }

    private List<CommandSegment> parseCommandSegments(String command) {
        List<CommandSegment> segments = new ArrayList<>();
        for (String rawSegment : command.split("[;|]+")) {
            String segment = rawSegment == null ? "" : rawSegment.trim();
            if (segment.isBlank()) {
                continue;
            }
            List<String> tokens = tokenize(segment);
            if (tokens.isEmpty()) {
                continue;
            }
            String commandName = normalizeCommandName(tokens.get(0));
            CommandCategory category = classify(commandName, segment);
            segments.add(new CommandSegment(commandName, tokens, category));
        }
        if (segments.isEmpty()) {
            segments.add(new CommandSegment(normalizeCommandName(command), List.of(command), classify(normalizeCommandName(command), command)));
        }
        return segments;
    }

    private List<String> tokenize(String segment) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(segment);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                token = matcher.group(2);
            }
            if (token == null) {
                token = matcher.group(3);
            }
            if (token != null && !token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    private String normalizeCommandName(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }

    private CommandCategory classify(String commandName, String rawSegment) {
        String normalizedSegment = rawSegment == null ? "" : rawSegment.toLowerCase(Locale.ROOT);
        if (PROCESS_SPAWN_COMMANDS.contains(commandName)) {
            return CommandCategory.PROCESS_SPAWN;
        }
        if (WRITE_COMMANDS.contains(commandName) || normalizedSegment.contains(">>") || normalizedSegment.contains(">|") || normalizedSegment.contains(" > ")) {
            return CommandCategory.WRITE;
        }
        if (NETWORK_COMMANDS.contains(commandName)) {
            return CommandCategory.NETWORK;
        }
        if (READ_COMMANDS.contains(commandName)) {
            return CommandCategory.READ;
        }
        if (normalizedSegment.contains("http://") || normalizedSegment.contains("https://")) {
            return CommandCategory.NETWORK;
        }
        return CommandCategory.UNKNOWN;
    }

    private List<String> extractHosts(String command) {
        List<String> hosts = new ArrayList<>();
        Matcher matcher = URL_HOST_PATTERN.matcher(command);
        while (matcher.find()) {
            String host = matcher.group(1);
            if (host != null && !host.isBlank()) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    private boolean matchesDomain(String host, String configuredDomain) {
        String expected = configuredDomain == null ? "" : configuredDomain.trim().toLowerCase(Locale.ROOT);
        if (expected.isBlank()) {
            return false;
        }
        return host.equals(expected) || host.endsWith("." + expected);
    }

    private void ensureNotUnderDeniedPath(Path candidate, List<String> deniedPaths, String message) {
        for (String deniedPath : deniedPaths) {
            if (isUnderPath(candidate, deniedPath)) {
                throw new IllegalArgumentException(message + ": " + deniedPath);
            }
        }
    }

    private boolean isUnderPath(Path candidate, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path normalizedConfigured = Path.of(configuredPath).toAbsolutePath().normalize();
        return normalizedCandidate.startsWith(normalizedConfigured);
    }

    private void ensureAllowed(Path candidate, List<String> allowedPaths, String message) {
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return;
        }
        boolean allowed = allowedPaths.stream().anyMatch(allowedPath -> isUnderPath(candidate, allowedPath));
        if (!allowed) {
            throw new IllegalArgumentException(message + ": " + candidate);
        }
    }

    private List<String> union(List<String> left, List<String> right) {
        Set<String> values = new HashSet<>();
        if (left != null) {
            values.addAll(left);
        }
        if (right != null) {
            values.addAll(right);
        }
        return List.copyOf(values);
    }

    private Path resolveReferencedPath(Path workingDirectory, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim();
        if (normalized.startsWith("-") || normalized.contains("://")) {
            return null;
        }
        if (!(normalized.contains(":\\") || normalized.startsWith(".\\") || normalized.startsWith("..\\") || normalized.startsWith("./") || normalized.startsWith("../") || normalized.startsWith("\\"))) {
            return null;
        }
        Path raw = Path.of(normalized);
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize();
        }
        return workingDirectory.resolve(raw).normalize();
    }

    private String joinMessages(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + System.lineSeparator() + second;
    }

    private String digest(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static final class StreamCapture implements Runnable {

        private final InputStream inputStream;
        private final long limitBytes;
        private final CountDownLatch latch;
        private volatile String content = "";

        private StreamCapture(InputStream inputStream, long limitBytes, CountDownLatch latch) {
            this.inputStream = inputStream;
            this.limitBytes = limitBytes;
            this.latch = latch;
        }

        @Override
        public void run() {
            try (InputStream stream = inputStream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[1024];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    int remaining = (int) Math.max(0, limitBytes - buffer.size());
                    if (remaining <= 0) {
                        break;
                    }
                    buffer.write(chunk, 0, Math.min(read, remaining));
                }
                content = buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                content = "Failed to capture process output: " + e.getMessage();
            } finally {
                latch.countDown();
            }
        }

        private String content() {
            return content;
        }
    }

    private enum CommandCategory {
        READ,
        WRITE,
        NETWORK,
        PROCESS_SPAWN,
        UNKNOWN
    }

    private record CommandSegment(String commandName, List<String> tokens, CommandCategory category) {
        private List<String> pathCandidates() {
            if (tokens.size() <= 1) {
                return List.of();
            }
            return tokens.subList(1, tokens.size());
        }
    }
}
