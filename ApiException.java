package gym;

/** Thrown anywhere in the backend to send a clean JSON error to the browser. */
final class ApiException extends RuntimeException {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
