package com.agora.identity.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user with a BCrypt-hashed password.
     * Fast-path duplicate check here; the DB unique constraint is the real guard —
     * a concurrent-insert race surfaces as DataIntegrityViolationException at commit,
     * mapped to 409 by the API exception handler.
     */
    @Transactional
    public User register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateUsernameException(username);
        }
        return userRepository.save(new User(username, passwordEncoder.encode(rawPassword)));
    }

    /** Empty unless username exists AND the password matches its BCrypt hash. */
    @Transactional(readOnly = true)
    public Optional<User> authenticate(String username, String rawPassword) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()));
    }
}
