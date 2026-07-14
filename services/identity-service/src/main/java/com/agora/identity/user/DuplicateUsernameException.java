package com.agora.identity.user;

public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String username) {
        super("username already taken: " + username);
    }
}
