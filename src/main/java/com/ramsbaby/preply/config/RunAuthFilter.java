package com.ramsbaby.preply.config;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * /run 엔드포인트에 대해 단순 헤더 토큰 검증을 수행한다.
 * - app.run.token이 비어 있으면 필터를 우회한다(기존 동작 유지).
 * - 설정되어 있으면 X-Run-Token 헤더 값과 비교한다.
 */
@Component
@RequiredArgsConstructor
public class RunAuthFilter extends OncePerRequestFilter {

    private static final String PATH = "/run";
    private static final String HEADER = "X-Run-Token";

    private final AppProps props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = (props.run() != null && props.run().token() != null) ? props.run().token() : "";
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (!token.equals(provided)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("Unauthorized");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

