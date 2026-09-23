package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;

public class PiSetup implements ToolSetup {

    static final String DEFAULT_PROVIDER = "anthropic";
    private static final String[] NPM_INSTALL = {
            "npm", "install", "-g", "--ignore-scripts", "--loglevel=error",
            "--fetch-retries=4", "--fetch-retry-factor=2",
            "--fetch-retry-mintimeout=1000", "--fetch-retry-maxtimeout=15000",
            "@earendil-works/pi-coding-agent"
    };
    long[] retryDelaysMs = {1_000, 3_000, 5_000, 10_000};
    static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    @Override
    public String name() {
        return "pi";
    }

    @Override
    public java.util.Map<String, ToolDef.ParameterDef> parameters() {
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();

        var provider = new ToolDef.ParameterDef();
        provider.setType("string");
        provider.setDescription("Pi provider (e.g. anthropic, vertex, google)");
        provider.setPattern("^[a-z][a-z0-9_-]*$");
        provider.setOptional(true);
        provider.setReconfigurable(true);
        provider.setDefault(DEFAULT_PROVIDER);
        params.put("provider", provider);

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. claude-sonnet-4-6, gemini-3.7-flash)");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        model.setDefault(DEFAULT_MODEL);
        params.put("model", model);

        return params;
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("Pi Coding Agent");
        a.setType("shell");
        a.setCommand("pi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        // fd-find (provides 'fd') and ripgrep (provides 'rg') are pre-installed so
        // pi's tools-manager finds them in PATH and skips downloading them on first run.
        return List.of("nodejs", "fd-find", "ripgrep");
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        var entries = new ArrayList<EnvEntry>();
        var provider = resolvedParams.getOrDefault("provider", DEFAULT_PROVIDER);

        if ("vertex".equals(provider) || "google".equals(provider)) {
            var claude = SpawnConfig.load().getClaude();
            if (claude.isUseVertex()) {
                entries.add(EnvEntry.set("GOOGLE_CLOUD_PROJECT", claude.getVertexProjectId()));
                entries.add(EnvEntry.set("GOOGLE_CLOUD_LOCATION", claude.getCloudMlRegion()));
            }
        } else {
            var claude = SpawnConfig.load().getClaude();
            if (claude.isOauthMode()) {
                entries.add(EnvEntry.set("ANTHROPIC_OAUTH_TOKEN", SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN));
            } else {
                entries.add(EnvEntry.set("ANTHROPIC_API_KEY", "sk-ant-placeholder"));
            }
        }
        entries.add(EnvEntry.set("PI_SKIP_VERSION_CHECK", "1"));
        return entries;
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        installBinary(c);
        configureSettings(c, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, java.util.Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    private void installBinary(Container c) {
        BuildOutput.stepStart("Installing Pi coding agent...");
        var result = c.exec(NPM_INSTALL);
        for (int attempt = 0; !result.success() && isTransientNetworkFailure(result)
                && attempt < retryDelaysMs.length; attempt++) {
            BuildOutput.note("npm network failure; retrying Pi install ("
                    + (attempt + 2) + "/" + (retryDelaysMs.length + 1) + ")");
            try {
                Thread.sleep(retryDelaysMs[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while retrying Pi installation", e);
            }
            result = c.exec(NPM_INSTALL);
        }
        result.assertSuccess("Failed to install Pi coding agent");
        BuildOutput.stepDone();
    }

    static boolean isTransientNetworkFailure(dev.incusspawn.incus.IncusClient.ExecResult result) {
        var output = (result.stdout() + "\n" + result.stderr()).toLowerCase();
        return output.contains("econnreset")
                || output.contains("etimedout")
                || output.contains("eai_again")
                || output.contains("enetwork")
                || output.contains("socket hang up")
                || output.contains("network aborted")
                || output.contains("connection reset")
                || output.contains("connection was closed");
    }

    private void configureSettings(Container c, java.util.Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Pi...");
        var provider = resolvedParams.getOrDefault("provider", DEFAULT_PROVIDER);
        var model = resolvedParams.getOrDefault("model", DEFAULT_MODEL);
        var settingsJson = """
                {
                  "enableInstallTelemetry": false,
                  "quietStartup": true,
                  "defaultProvider": "%s",
                  "defaultModel": "%s",
                  "defaultThinkingLevel": "medium"
                }
                """.formatted(provider, model);
        c.sh("mkdir -p /home/agentuser/.pi/agent");
        c.writeFile("/home/agentuser/.pi/agent/settings.json", settingsJson);
        c.chown("/home/agentuser/.pi", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

}
