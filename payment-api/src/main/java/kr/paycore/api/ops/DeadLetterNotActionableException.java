package kr.paycore.api.ops;

/** DLT 항목이 없거나 이미 처리됐다. */
public class DeadLetterNotActionableException extends RuntimeException {

    private final String deadLetterId;

    public DeadLetterNotActionableException(String deadLetterId, String message) {
        super(message);
        this.deadLetterId = deadLetterId;
    }

    public String deadLetterId() {
        return deadLetterId;
    }
}
