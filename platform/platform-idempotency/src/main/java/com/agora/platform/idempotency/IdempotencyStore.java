package com.agora.platform.idempotency;

import java.util.Optional;

public interface IdempotencyStore {

    record StoredResponse(boolean inProgress, int status, String contentType, String body) {
    }

    /** Atomically claim (scope,key). False = someone else already began. */
    boolean tryBegin(String scope, String key);

    Optional<StoredResponse> find(String scope, String key);

    void complete(String scope, String key, int status, String contentType, String body);

    /** Remove an in-progress claim after a handler failure so retries can re-execute. */
    void abandon(String scope, String key);
}
