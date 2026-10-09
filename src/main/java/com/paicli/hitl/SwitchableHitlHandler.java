package com.paicli.hitl;

import java.util.Objects;

/**
 * Delegates HITL interaction to the currently active UI implementation.
 *
 * <p>The registry is created before the UI mode is selected, so this wrapper
 * lets CLI keep using {@link TerminalHitlHandler} while TUI can swap in a
 * Lanterna-backed handler without rebuilding the tool registry.
 * 中文：将 HITL 交互委托给当前界面实现。注册表早于界面模式选择创建，包装器让 CLI 使用终端处理器，TUI 可换成
 * Lanterna 处理器而不重建注册表。
 */
public final class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;
    private volatile boolean highRiskConfirmation;

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public HitlHandler getDelegate() {
        return delegate;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        return delegate.requestApproval(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public void onAutoApproved(String toolName, String reason) {
        delegate.onAutoApproved(toolName, reason);
    }

    @Override
    public void onAutoDenied(String toolName, String reason) {
        delegate.onAutoDenied(toolName, reason);
    }

    @Override
    public boolean isHighRiskConfirmationEnabled() {
        return highRiskConfirmation;
    }

    @Override
    public void setHighRiskConfirmationEnabled(boolean enabled) {
        this.highRiskConfirmation = enabled;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }
}
