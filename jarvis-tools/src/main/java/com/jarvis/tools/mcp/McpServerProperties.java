package com.jarvis.tools.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configuration for one MCP server.
 */
public class McpServerProperties {

    private boolean enabled;
    private boolean autoConnect;
    private McpExecutionHost executionHost = McpExecutionHost.CORE;
    private McpTransport transport = McpTransport.STDIO;
    private String command = "";
    private List<String> args = new ArrayList<>();
    private McpAccessLevel accessLevel = McpAccessLevel.READ;
    private Duration startupTimeout = Duration.ofSeconds(10);
    private Duration initializeTimeout = Duration.ofSeconds(10);
    private Duration listToolsTimeout = Duration.ofSeconds(5);
    private Duration callTimeout = Duration.ofSeconds(30);
    private Set<String> activeWorkspaces = Set.of();
    /** Extra environment variables for a STDIO server process (e.g. GITHUB_PERSONAL_ACCESS_TOKEN). */
    private java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
    /** Endpoint of an HTTP (streamable HTTP) MCP server, e.g. https://api.githubcopilot.com/mcp/. */
    private String url = "";
    /** HTTP headers for an HTTP MCP server, e.g. Authorization: Bearer ${GITHUB_TOKEN}. */
    private java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
    /**
     * Glob patterns of MCP tool names to expose (empty = all). Large servers (GitHub has ~90 tools)
     * flood a local model's tool catalog; expose only what is useful, e.g. ["get_*", "list_*", "create_issue"].
     */
    private List<String> includeTools = new ArrayList<>();
    /** Glob patterns of MCP tool names to hide, applied after includeTools. */
    private List<String> excludeTools = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public McpExecutionHost getExecutionHost() {
        return executionHost;
    }

    public void setExecutionHost(McpExecutionHost executionHost) {
        this.executionHost = executionHost == null ? McpExecutionHost.CORE : executionHost;
    }

    public McpTransport getTransport() {
        return transport;
    }

    public void setTransport(McpTransport transport) {
        this.transport = transport == null ? McpTransport.STDIO : transport;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
    }

    public McpAccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(McpAccessLevel accessLevel) {
        this.accessLevel = accessLevel == null ? McpAccessLevel.READ : accessLevel;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(Duration startupTimeout) {
        this.startupTimeout = startupTimeout == null ? Duration.ofSeconds(10) : startupTimeout;
    }

    public Duration getInitializeTimeout() {
        return initializeTimeout;
    }

    public void setInitializeTimeout(Duration initializeTimeout) {
        this.initializeTimeout = initializeTimeout == null ? Duration.ofSeconds(10) : initializeTimeout;
    }

    public Duration getListToolsTimeout() {
        return listToolsTimeout;
    }

    public void setListToolsTimeout(Duration listToolsTimeout) {
        this.listToolsTimeout = listToolsTimeout == null ? Duration.ofSeconds(5) : listToolsTimeout;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout == null ? Duration.ofSeconds(30) : callTimeout;
    }

    public java.util.Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(java.util.Map<String, String> env) {
        this.env = env == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(env);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.strip();
    }

    public java.util.Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(java.util.Map<String, String> headers) {
        this.headers = headers == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(headers);
    }

    public List<String> getIncludeTools() {
        return includeTools;
    }

    public void setIncludeTools(List<String> includeTools) {
        this.includeTools = includeTools == null ? new ArrayList<>() : new ArrayList<>(includeTools);
    }

    public List<String> getExcludeTools() {
        return excludeTools;
    }

    public void setExcludeTools(List<String> excludeTools) {
        this.excludeTools = excludeTools == null ? new ArrayList<>() : new ArrayList<>(excludeTools);
    }

    /**
     * Applies {@link #getIncludeTools()} / {@link #getExcludeTools()} to an MCP tool name.
     *
     * @param toolName MCP-native tool name
     * @return true when the tool should be exposed
     */
    public boolean exposesTool(String toolName) {
        String name = toolName == null ? "" : toolName;
        boolean included = includeTools.isEmpty() || includeTools.stream().anyMatch(pattern -> globMatches(pattern, name));
        return included && excludeTools.stream().noneMatch(pattern -> globMatches(pattern, name));
    }

    private static boolean globMatches(String pattern, String name) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        StringBuilder regex = new StringBuilder("(?i)");
        for (char ch : pattern.strip().toCharArray()) {
            regex.append(ch == '*' ? ".*" : ch == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return name.matches(regex.toString());
    }

    public Set<String> getActiveWorkspaces() {
        return activeWorkspaces;
    }

    public void setActiveWorkspaces(Set<String> activeWorkspaces) {
        this.activeWorkspaces = activeWorkspaces == null ? Set.of() : Set.copyOf(activeWorkspaces);
    }
}
