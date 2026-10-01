package it.bluecube.osrmzonemanager.vroom;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the permissive CORS headers {@code vroom-express} used to set on every response, so browser
 * clients keep working with the in-process VROOM endpoints — including error responses, which
 * bypass the controller and are produced by the exception handler.
 */
@Component
@Order(1)
public class VroomCorsFilter extends OncePerRequestFilter {

    private static final String HEADER_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String HEADER_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String ALLOWED_HEADERS = "Origin, X-Requested-With, Content-Type, Accept";
    private static final String VROOM_SEGMENT = "/vroom";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.contains(VROOM_SEGMENT)) {
            response.setHeader(HEADER_ALLOW_ORIGIN, "*");
            response.setHeader(HEADER_ALLOW_HEADERS, ALLOWED_HEADERS);
        }
        chain.doFilter(request, response);
    }
}
