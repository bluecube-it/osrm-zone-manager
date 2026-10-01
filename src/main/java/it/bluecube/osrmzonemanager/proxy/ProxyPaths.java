package it.bluecube.osrmzonemanager.proxy;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Helpers shared by the zone and global proxy controllers.
 */
final class ProxyPaths {

    private ProxyPaths() {
    }

    /**
     * Strips a prefix from the request path (context path removed first) and returns the remainder —
     * the part that must be forwarded verbatim to the OSRM instance.
     *
     * @param request the incoming request
     * @param prefix  the prefix to remove, including the trailing slash
     * @return the remaining path, or an empty string when the prefix does not match
     */
    static String stripPrefix(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith(prefix)) {
            return uri.substring(prefix.length());
        }
        return "";
    }
}
