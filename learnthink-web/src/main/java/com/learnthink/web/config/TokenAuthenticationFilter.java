package com.learnthink.web.config;

import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.TokenStorageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);
    private final TokenStorageService tokenStorageService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");

        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String accessToken = authorization.substring(7);

            try {
                Map<String, Object> tokenInfo = tokenStorageService.getAccessTokenInfo(accessToken);
                if (tokenInfo != null) {
                    String userId = (String) tokenInfo.get("userId");
                    String role = (String) tokenInfo.get("role");
                    if (userId != null) {
                        var authorities = List.of(new SimpleGrantedAuthority(
                            "ROLE_" + (role != null ? role.toUpperCase() : "STUDENT")));
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, authorities));
                        UserContextUtil.setCurrentUser(new UserContextUtil.UserInfo(userId, null, role));
                    }
                }
            } catch (Exception e) {
                log.warn("Token validation error: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
