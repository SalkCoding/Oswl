package com.salkcoding.oswl.exception;

import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import lombok.Getter;

import java.util.List;

/** The same repository (and branch) is already queued or importing for this user. */
@Getter
public class QuickImportDuplicateException extends RuntimeException {

    private final String messageKey;
    private final List<String> messageArgs;

    public QuickImportDuplicateException(String repoLabel) {
        super(QuickImportMessageKeys.DUPLICATE_IMPORT);
        this.messageKey = QuickImportMessageKeys.DUPLICATE_IMPORT;
        this.messageArgs = List.of(repoLabel != null ? repoLabel : "");
    }
}
