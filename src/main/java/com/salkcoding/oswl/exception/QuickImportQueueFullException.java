package com.salkcoding.oswl.exception;

import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import lombok.Getter;

import java.util.List;

/** User has reached the per-user Quick Import queue limit. */
@Getter
public class QuickImportQueueFullException extends RuntimeException {

    private final String messageKey;
    private final List<String> messageArgs;

    public QuickImportQueueFullException(int maxQueued) {
        super(QuickImportMessageKeys.QUEUE_FULL);
        this.messageKey = QuickImportMessageKeys.QUEUE_FULL;
        this.messageArgs = List.of(String.valueOf(maxQueued));
    }
}
