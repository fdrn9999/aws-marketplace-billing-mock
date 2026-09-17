package io.github.fdrn9999.marketplace.common;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 앱 요청(/api/**, /marketplace/**)을 처리하는 동안 {@link DemoStateLock}의 읽기 잠금을 잡는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoStateLockFilter extends OncePerRequestFilter {

    static final String RESET_PATH = "/api/admin/reset";

    private final DemoStateLock stateLock;

    public DemoStateLockFilter(DemoStateLock stateLock) {
        this.stateLock = stateLock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean appRequest = path.startsWith("/api/") || path.startsWith("/marketplace/");
        // 초기화 요청은 서비스에서 쓰기 잠금을 잡는다
        return !appRequest || path.equals(RESET_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            stateLock.read(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
