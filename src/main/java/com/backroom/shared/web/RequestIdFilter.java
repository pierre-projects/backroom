package com.backroom.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id before anything else runs. A well-formed incoming X-Request-Id (for example from
 * the hosting platform's router) is kept; anything else is replaced with a fresh UUID so log lines can't be forged or
 * bloated. The id is in the MDC for every log line of the request, echoed on the response, and added to 5xx problems.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** The current request's id, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static boolean valid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Error and async dispatches of the same request keep the id chosen on the first pass.
        var id = request.getAttribute(ATTRIBUTE) instanceof String existing ? existing : null;
        if (id == null) {
            var incoming = request.getHeader(HEADER);
            id = valid(incoming) ? incoming : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        var previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previous);
        }
    }
}
