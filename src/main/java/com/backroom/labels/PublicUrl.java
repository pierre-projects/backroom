package com.backroom.labels;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The address printed into QR codes (BACKROOM_PUBLIC_URL). Choose a stable hostname before printing real labels:
 * a label keeps its URL for as long as it is stuck to a shelf.
 */
@Component
class PublicUrl {
    private final String base;

    PublicUrl(@Value("${backroom.public-base-url:http://localhost:8080}") String base) {
        this.base = base.strip().replaceAll("/+$", "");
    }

    String base() {
        return base;
    }

    String link(String token) {
        return base + "/s/" + token;
    }

    /** The token part of one of our own QR links, or null for any other URL. Unfamiliar URLs are never followed. */
    String tokenFrom(String scanned) {
        var prefix = base.toLowerCase(Locale.ROOT) + "/s/";
        var lower = scanned.strip().toLowerCase(Locale.ROOT);
        if (!lower.startsWith(prefix)) return null;
        var rest = scanned.strip().substring(prefix.length());
        var end =
                rest.indexOf('?') >= 0 ? rest.indexOf('?') : rest.indexOf('#') >= 0 ? rest.indexOf('#') : rest.length();
        return rest.substring(0, end).replaceAll("/+$", "");
    }

    boolean local() {
        try {
            var host = URI.create(base).getHost();
            return host == null
                    || host.equals("localhost")
                    || host.startsWith("127.")
                    || host.equals("[::1]")
                    || host.endsWith(".localhost");
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }
}
