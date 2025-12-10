package com.liquid.library.nifi.service;

public class ExecutionResult {
    private String action;
    private String description;
    private String screenshotBase64;
    private boolean success;
    private String errorMessage;

    public ExecutionResult(String action, String description, String screenshotBase64, boolean success, String errorMessage) {
        this.action = action;
        this.description = description;
        this.screenshotBase64 = screenshotBase64;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // Getters
    public String getAction() { return action; }
    public String getDescription() { return description; }
    public String getScreenshotBase64() { return screenshotBase64; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }

    // Setters
    public void setAction(String action) { this.action = action; }
    public void setDescription(String description) { this.description = description; }
    public void setScreenshotBase64(String screenshotBase64) { this.screenshotBase64 = screenshotBase64; }
    public void setSuccess(boolean success) { this.success = success; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
