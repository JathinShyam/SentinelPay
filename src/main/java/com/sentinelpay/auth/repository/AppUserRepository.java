package com.sentinelpay.auth.repository;

import com.sentinelpay.auth.domain.AppUser;
import com.sentinelpay.auth.domain.Role;
import com.sentinelpay.auth.domain.UserStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AppUserRepository {

    private static final RowMapper<AppUser> ROW_MAPPER = (rs, rowNum) -> new AppUser(
            UUID.fromString(rs.getString("user_id")),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("""
                        SELECT user_id, username, display_name, password_hash, role,
                               status, created_at, updated_at
                        FROM app_users
                        WHERE LOWER(username) = LOWER(:username)
                        """)
                .param("username", username)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<AppUser> findById(UUID userId) {
        return jdbc.sql("""
                        SELECT user_id, username, display_name, password_hash, role,
                               status, created_at, updated_at
                        FROM app_users
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(ROW_MAPPER)
                .optional();
    }

    public void insert(AppUser user) {
        jdbc.sql("""
                        INSERT INTO app_users
                            (user_id, username, display_name, password_hash, role,
                             status, created_at, updated_at)
                        VALUES
                            (:userId, :username, :displayName, :passwordHash, :role,
                             :status, :createdAt, :updatedAt)
                        """)
                .param("userId", user.userId())
                .param("username", user.username())
                .param("displayName", user.displayName())
                .param("passwordHash", user.passwordHash())
                .param("role", user.role().name())
                .param("status", user.status().name())
                .param("createdAt", java.sql.Timestamp.from(user.createdAt()))
                .param("updatedAt", java.sql.Timestamp.from(user.updatedAt()))
                .update();
    }
}
