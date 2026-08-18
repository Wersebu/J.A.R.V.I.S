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

    public Set<String> getActiveWorkspaces() {
        return activeWorkspaces;
    }

    public void setActiveWorkspaces(Set<String> activeWorkspaces) {
        this.activeWorkspaces = activeWorkspaces == null ? Set.of() : Set.copyOf(activeWorkspaces);
    }
}
