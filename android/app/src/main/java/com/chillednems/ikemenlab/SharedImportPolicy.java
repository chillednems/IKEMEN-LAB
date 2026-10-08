package com.chillednems.ikemenlab;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** A single explicit share, validated before any source or network access. */
final class SharedImportPolicy {
    static final class Request {
        final boolean link;
        final String value;
        Request(boolean link, String value) { this.link = link; this.value = value; }
    }
    private SharedImportPolicy() { }

    static Request accept(String action, String mime, String stream, String text,
                          boolean readGrant) throws IOException {
        if (!"android.intent.action.SEND".equals(action)) throw new IOException("Share one ZIP or HTTPS link");
        if (stream != null && text != null) throw new IOException("Share one ZIP or one HTTPS link at a time");
        if (stream != null) {
            if (!readGrant || stream.length() > 4096) throw new IOException("Shared ZIP needs one temporary read grant");
            try {
                URI uri = new URI(stream);
                if (!"content".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null
                        || uri.getAuthority().isEmpty() || uri.getFragment() != null)
                    throw new IOException("Shared ZIP must be a content document");
            } catch (URISyntaxException invalid) { throw new IOException("Shared ZIP URI is invalid", invalid); }
            return new Request(false, stream);
        }
        if (text != null && "text/plain".equalsIgnoreCase(mime)) {
            SharedZipSource.requireHttps(text);
            return new Request(true, text);
        }
        throw new IOException("Share one content ZIP or one HTTPS link");
    }
}
