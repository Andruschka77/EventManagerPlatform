package dev.sorokin.eventmanager.config;

import dev.sorokin.eventmanager.jwt.JwtTokenManager;
import dev.sorokin.eventmanager.model.entity.UserEntity;
import dev.sorokin.eventmanager.model.enums.UserRole;
import dev.sorokin.eventmanager.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;

@Component
public class UserTestConfiguration {

    private static final String DEFAULT_ADMIN_LOGIN = "admin";
    private static final String DEFAULT_USER_LOGIN = "user";
    private static volatile boolean isUserInitialized = false;
    private final SecretKey secretKey;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenManager jwtTokenManager;

    public UserTestConfiguration(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenManager jwtTokenManager,
            @Value("${jwt.secret-key}") String keyString
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenManager = jwtTokenManager;
        this.secretKey = Keys.hmacShaKeyFor(keyString.getBytes());
    }

    public String getJwtWithRole(UserRole role) {
        if (!isUserInitialized) {
            initializeTestUsers();
            isUserInitialized = true;
        }

        return switch (role) {
            case ADMIN -> jwtTokenManager.generateToken(DEFAULT_ADMIN_LOGIN, UserRole.ADMIN, 1L);
            case USER -> jwtTokenManager.generateToken(DEFAULT_USER_LOGIN, UserRole.USER, 2L);
        };
    }

    public Long getIdFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("userId", Long.class);
    }

    private void initializeTestUsers() {
        createUser(DEFAULT_ADMIN_LOGIN, "admin", UserRole.ADMIN);
        createUser(DEFAULT_USER_LOGIN, "user", UserRole.USER);
    }

    private void createUser(
            String login,
            String password,
            UserRole role
    ) {
        if (userRepository.existsByLogin(login)) {
            return;
        }

        String hashedPass = passwordEncoder.encode(password);
        var userToSave = new UserEntity(
                login,
                10,
                hashedPass,
                role
        );

        userRepository.save(userToSave);
    }

}