package com.sentinelpay.auth.security;

import com.sentinelpay.auth.domain.AppUser;
import com.sentinelpay.auth.domain.UserStatus;
import com.sentinelpay.auth.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        AppUser user = users.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new AuthenticatedUser(user.userId(), user.username(), user.displayName(),
                user.passwordHash(), user.role(), user.status() == UserStatus.ACTIVE);
    }
}
