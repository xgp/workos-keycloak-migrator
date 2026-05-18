package io.phasetwo.wkm.common.workos;

public class WorkOSException extends RuntimeException {

    private final int status;

    public WorkOSException(int status, String message) {
        super(message);
        this.status = status;
    }

    public WorkOSException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    public int status() {
        return status;
    }
}
