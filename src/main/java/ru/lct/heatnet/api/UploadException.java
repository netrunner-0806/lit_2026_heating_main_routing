package ru.lct.heatnet.api;

/** Expected protocol/storage failure, safe to expose without server filesystem details. */
public final class UploadException extends RuntimeException {
    private final int status;
    public UploadException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
