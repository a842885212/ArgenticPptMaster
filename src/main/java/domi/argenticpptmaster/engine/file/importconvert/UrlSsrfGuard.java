package domi.argenticpptmaster.engine.file.importconvert;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Basic SSRF checks for URL import: http(s) only; block loopback / private / link-local
 * after DNS resolution.
 */
public final class UrlSsrfGuard {

    public static final int MAX_REDIRECTS = 5;
    public static final long MAX_BODY_BYTES = 5L * 1024 * 1024; // 5 MiB page body
    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8 MiB per image

    private UrlSsrfGuard() {}

    public static URI requireHttpUrl(String raw) throws ImportConversionException {
        if (raw == null || raw.isBlank()) {
            throw new ImportConversionException("URL is blank");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ImportConversionException("Invalid URL: " + raw, ex);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ImportConversionException("Only http/https URLs are allowed: " + raw);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ImportConversionException("URL host is required: " + raw);
        }
        return uri;
    }

    public static void validateResolvedTarget(URI uri) throws ImportConversionException {
        requireHttpUrl(uri.toString());
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ImportConversionException("URL host is required: " + uri);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")) {
            throw new ImportConversionException("Refusing private/loopback URL host: " + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new ImportConversionException("Cannot resolve URL host: " + host, ex);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new ImportConversionException(
                        "Refusing private/loopback/link-local target for host: " + host);
            }
        }
    }

    public static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
