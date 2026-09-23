package dev.incusspawn.proxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.YamlErrors;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strict startup-only configuration for command-backed proxy credentials. */
public final class CommandCredentialConfig {

    public static final String FILE_NAME = "command-credentials.yaml";
    private static final long MAX_CONFIG_BYTES = 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern EXACT_HOST = Pattern.compile(
            "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern HEADER_NAME = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Pattern BEARER_PLACEHOLDER = Pattern.compile(
            "[A-Za-z0-9._~+/=-]{1,256}");
    private static final Set<String> FORBIDDEN_RAW_HEADERS = Set.of(
            "authorization", "connection", "content-length", "host",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");

    private static final ObjectMapper YAML = strictYamlMapper();

    private final List<Rule> rules;

    private static ObjectMapper strictYamlMapper() {
        var mapper = new ObjectMapper(new YAMLFactory())
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        var textual = mapper.coercionConfigFor(
                com.fasterxml.jackson.databind.type.LogicalType.Textual);
        textual.setCoercion(
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        textual.setCoercion(
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        textual.setCoercion(
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        return mapper;
    }

    CommandCredentialConfig(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static CommandCredentialConfig disabled() {
        return new CommandCredentialConfig(List.of());
    }

    public List<Rule> rules() {
        return rules;
    }

    public Set<String> hosts() {
        var hosts = new HashSet<String>();
        for (var rule : rules) hosts.add(rule.host());
        return Set.copyOf(hosts);
    }

    public Rule find(String host) {
        if (host == null) return null;
        for (var rule : rules) {
            if (rule.host().equalsIgnoreCase(host)) return rule;
        }
        return null;
    }

    public static Path configFile() {
        return SpawnConfig.configDir().resolve(FILE_NAME);
    }

    /** Load the protected machine-local file and reject all built-in and tool route collisions. */
    public static CommandCredentialConfig loadStrict() {
        var file = configFile();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return disabled();
        var toolRoutes = ToolProxyResolver.declaredRoutes();
        var exact = new HashSet<>(ProxyConfig.builtinInterceptedDomains());
        exact.addAll(toolRoutes.exactDomains());
        return loadStrict(file, exact, toolRoutes.wildcardSuffixes());
    }

    static CommandCredentialConfig loadStrict(Path file, Set<String> exactRoutes,
                                               List<String> wildcardSuffixes) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return disabled();
        validateProtection(file);
        try {
            var size = Files.size(file);
            if (size == 0) {
                throw invalid(file, "file is empty; use 'rules: []' to disable command credentials");
            }
            if (size > MAX_CONFIG_BYTES) {
                throw invalid(file, "file exceeds the 1 MiB limit");
            }

            var raw = YAML.readValue(file.toFile(), RawConfig.class);
            if (raw == null || raw.rules == null) {
                throw invalid(file, "top-level 'rules' list is required");
            }
            if (raw.rules.size() > 128) {
                throw invalid(file, "at most 128 rules are allowed");
            }

            var rules = new ArrayList<Rule>();
            var ids = new HashSet<String>();
            var hosts = new HashSet<String>();
            for (var candidate : raw.rules) {
                if (candidate == null) throw invalid(file, "rule definition is required");
                var rule = candidate.validate(file);
                if (!ids.add(rule.id())) {
                    throw invalid(file, "duplicate id '" + rule.id() + "'");
                }
                if (!hosts.add(rule.host())) {
                    throw invalid(file, "duplicate host '" + rule.host() + "'");
                }
                rejectCollision(file, rule.host(), exactRoutes, wildcardSuffixes);
                rules.add(rule);
            }
            return new CommandCredentialConfig(rules);
        } catch (IOException e) {
            throw new IllegalStateException(YamlErrors.friendly(file.getFileName().toString(), e), e);
        }
    }

    private static void validateProtection(Path file) {
        var parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw invalid(file, "parent must be a physical directory");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(file, "must be a regular file and must not be a symbolic link");
        }
        try {
            var expectedOwner = Files.getOwner(Environment.home(), LinkOption.NOFOLLOW_LINKS);
            if (!Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).equals(expectedOwner)
                    || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(expectedOwner)) {
                throw invalid(file, "file and parent must be owned by the current home owner");
            }
            var parentView = Files.getFileAttributeView(parent, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            var fileView = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (parentView == null || fileView == null) {
                throw invalid(file, "permissions cannot be verified on this filesystem");
            }
            var parentPermissions = parentView.readAttributes().permissions();
            if (!parentPermissions.contains(PosixFilePermission.OWNER_READ)
                    || !parentPermissions.contains(PosixFilePermission.OWNER_WRITE)
                    || !parentPermissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || parentPermissions.contains(PosixFilePermission.GROUP_WRITE)
                    || parentPermissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw invalid(file, "parent permissions must be owner-controlled");
            }
            var filePermissions = fileView.readAttributes().permissions();
            if (!filePermissions.equals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE))) {
                throw invalid(file, "permissions must be exactly 600");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot verify " + file + " protection: "
                    + e.getMessage(), e);
        }
    }

    private static void rejectCollision(Path file, String host, Set<String> exactRoutes,
                                        List<String> wildcardSuffixes) {
        if (exactRoutes.contains(host)) {
            throw invalid(file, "host '" + host + "' collides with a built-in or tool proxy route");
        }
        for (var suffix : wildcardSuffixes) {
            if (host.endsWith(suffix)) {
                throw invalid(file, "host '" + host + "' collides with a built-in or tool proxy route");
            }
        }
    }

    private static IllegalStateException invalid(Path file, String detail) {
        return new IllegalStateException("invalid " + file.getFileName() + ": " + detail);
    }

    public record Rule(
            String id,
            String host,
            List<String> argv,
            String validationRegex,
            Pattern validationPattern,
            Carriers carriers,
            int timeoutSeconds,
            int maxOutputBytes,
            int bodyLimitBytes,
            int cacheTtlSeconds,
            int failureTtlSeconds,
            String label,
            String remediation) {
        public Rule {
            argv = List.copyOf(argv);
        }
    }

    public record Carriers(BearerCarrier bearer, HeaderCarrier header) {}
    public record BearerCarrier(String placeholder) {}
    public record HeaderCarrier(String name, String placeholder) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class RawConfig {
        public List<RawRule> rules;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class RawRule {
        public String id;
        public String host;
        public List<String> argv;
        @JsonProperty("validation-regex")
        public String validationRegex;
        public RawCarriers carriers;
        @JsonProperty("timeout-seconds")
        public Integer timeoutSeconds;
        @JsonProperty("max-output-bytes")
        public Integer maxOutputBytes;
        @JsonProperty("body-limit-bytes")
        public Integer bodyLimitBytes;
        @JsonProperty("cache-ttl-seconds")
        public Integer cacheTtlSeconds;
        @JsonProperty("failure-ttl-seconds")
        public Integer failureTtlSeconds;
        public String label;
        public String remediation;

        Rule validate(Path file) {
            if (id == null || !SAFE_ID.matcher(id).matches()) {
                throw invalid(file, "rule id must be 1-63 lowercase letters, digits, or hyphens and start with a letter");
            }
            if (host == null || !EXACT_HOST.matcher(host).matches()) {
                throw invalid(file, "rule '" + id + "' host must be an exact lowercase DNS host");
            }
            validateArgv(file);
            var pattern = validateRegex(file);
            var validatedCarriers = validateCarriers(file, pattern);
            requireRange(file, "timeout-seconds", timeoutSeconds, 1, 60);
            requireRange(file, "max-output-bytes", maxOutputBytes, 1, 64 * 1024);
            requireRange(file, "body-limit-bytes", bodyLimitBytes, 1, 64 * 1024 * 1024);
            requireRange(file, "cache-ttl-seconds", cacheTtlSeconds, 1, 24 * 60 * 60);
            requireRange(file, "failure-ttl-seconds", failureTtlSeconds, 1, 5 * 60);
            validateDisplayText(file, "label", label, 128);
            validateDisplayText(file, "remediation", remediation, 512);
            return new Rule(id, host, argv, validationRegex, pattern, validatedCarriers,
                    timeoutSeconds, maxOutputBytes, bodyLimitBytes, cacheTtlSeconds,
                    failureTtlSeconds, label, remediation);
        }

        private void validateArgv(Path file) {
            if (argv == null || argv.isEmpty() || argv.size() > 32) {
                throw invalid(file, "rule '" + id + "' argv must contain 1-32 entries");
            }
            String executable = argv.getFirst();
            try {
                if (executable == null || !Path.of(executable).isAbsolute()) {
                    throw invalid(file, "rule '" + id + "' argv executable must be absolute");
                }
            } catch (java.nio.file.InvalidPathException e) {
                throw invalid(file, "rule '" + id + "' argv executable must be an absolute valid path");
            }
            for (var arg : argv) {
                if (arg == null || arg.isEmpty() || arg.length() > 4096
                        || containsControl(arg)) {
                    throw invalid(file, "rule '" + id + "' argv contains an invalid entry");
                }
            }
        }

        private Pattern validateRegex(Path file) {
            if (validationRegex == null || validationRegex.isEmpty()
                    || validationRegex.length() > 1024 || containsLineBreak(validationRegex)) {
                throw invalid(file, "rule '" + id + "' validation-regex must be a non-empty single line of at most 1024 characters");
            }
            try {
                return Pattern.compile(validationRegex);
            } catch (PatternSyntaxException e) {
                throw invalid(file, "rule '" + id + "' validation-regex is invalid");
            }
        }

        private Carriers validateCarriers(Path file, Pattern pattern) {
            if (carriers == null || (carriers.bearer == null && carriers.header == null)) {
                throw invalid(file, "rule '" + id + "' must configure at least one carrier");
            }
            BearerCarrier bearer = null;
            if (carriers.bearer != null) {
                var placeholder = carriers.bearer.placeholder;
                if (placeholder == null || !BEARER_PLACEHOLDER.matcher(placeholder).matches()) {
                    throw invalid(file, "rule '" + id + "' bearer placeholder is invalid");
                }
                rejectCredentialShapedPlaceholder(file, pattern, placeholder);
                bearer = new BearerCarrier(placeholder);
            }
            HeaderCarrier header = null;
            if (carriers.header != null) {
                var name = carriers.header.name;
                var placeholder = carriers.header.placeholder;
                if (name == null || !HEADER_NAME.matcher(name).matches()
                        || FORBIDDEN_RAW_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw invalid(file, "rule '" + id + "' raw header name is invalid or reserved");
                }
                if (placeholder == null || placeholder.isEmpty() || placeholder.length() > 512
                        || containsControl(placeholder)) {
                    throw invalid(file, "rule '" + id + "' raw header placeholder is invalid");
                }
                rejectCredentialShapedPlaceholder(file, pattern, placeholder);
                header = new HeaderCarrier(name, placeholder);
            }
            return new Carriers(bearer, header);
        }

        private void rejectCredentialShapedPlaceholder(Path file, Pattern pattern,
                                                        String placeholder) {
            if (pattern.matcher(placeholder).matches()) {
                throw invalid(file, "rule '" + id + "' placeholder must be inert and must not match validation-regex");
            }
        }

        private void requireRange(Path file, String field, Integer value, int min, int max) {
            if (value == null || value < min || value > max) {
                throw invalid(file, "rule '" + id + "' " + field + " must be between "
                        + min + " and " + max);
            }
        }

        private void validateDisplayText(Path file, String field, String value, int max) {
            if (value == null || value.isBlank() || value.length() > max
                    || containsControl(value)) {
                throw invalid(file, "rule '" + id + "' " + field
                        + " must be a non-empty single line of at most " + max + " characters");
            }
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class RawCarriers {
        public RawBearer bearer;
        public RawHeader header;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class RawBearer {
        public String placeholder;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class RawHeader {
        public String name;
        public String placeholder;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }
}
