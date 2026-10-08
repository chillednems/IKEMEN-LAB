package com.chillednems.ikemenlab;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** User-confirmed HTTPS ZIP fetches; every response is revalidated before staging. */
final class SharedZipSource {
    interface Cancellation { boolean cancelled(); }
    interface Clock { long nowNanos(); }
    interface Transport { Response open(URI uri) throws IOException; }
    static final class Response implements AutoCloseable {
        final int status;
        final String location;
        final long length;
        final InputStream body;
        private final Runnable close;
        Response(int status, String location, long length, InputStream body, Runnable close) {
            this.status = status; this.location = location; this.length = length;
            this.body = body; this.close = close;
        }
        @Override public void close() throws IOException {
            try { if (body != null) body.close(); }
            finally { if (close != null) close.run(); }
        }
    }
    static final class Staged {
        final AddonPackage addon;
        final String host;
        final int redirects;
        Staged(AddonPackage addon, String host, int redirects) {
            this.addon = addon; this.host = host; this.redirects = redirects;
        }
    }
    private SharedZipSource() { }

    static URI requireHttps(String text) throws IOException {
        if (text == null || text.length() > 2048 || !text.equals(text.trim()))
            throw new IOException("Share one HTTPS ZIP link up to 2048 characters");
        URI uri;
        try { uri = new URI(text); }
        catch (URISyntaxException invalid) { throw new IOException("Shared link is invalid", invalid); }
        String host = uri.getHost();
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getFragment() != null || host == null || host.isEmpty()
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || lower.equals("localhost") || lower.equals("localhost.")
                || lower.endsWith(".local") || lower.endsWith(".local.")
                || host.startsWith("[") || host.matches("[0-9.]+"))
            throw new IOException("Only public HTTPS links without credentials are supported");
        return uri;
    }

    static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 0 || first == 100 && second >= 64 && second <= 127
                || first == 169 && second == 254 || first >= 224;
    }

    static boolean trustedDownloadHost(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("github.com") || lower.equals("release-assets.githubusercontent.com")
                || lower.equals("objects.githubusercontent.com");
    }

    static Staged stage(String link, java.io.File privateRoot, String kind,
                        Cancellation cancellation, Transport transport, long maximum) throws IOException {
        return stage(link, privateRoot, kind, cancellation, transport, maximum,
                System::nanoTime, java.util.concurrent.TimeUnit.MINUTES.toNanos(2));
    }

    static Staged stage(String link, java.io.File privateRoot, String kind,
                        Cancellation cancellation, Transport transport, long maximum,
                        Clock clock, long durationNanos) throws IOException {
        URI current = requireHttps(link);
        long started = clock.nowNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!trustedDownloadHost(current))
                throw new IOException("This HTTPS host requires browser download and ZIP sharing");
            checkDeadline(cancellation, clock, started, durationNanos);
            try (Response response = transport.open(current)) {
                checkDeadline(cancellation, clock, started, durationNanos);
                if (response.status == HttpURLConnection.HTTP_MOVED_PERM
                        || response.status == HttpURLConnection.HTTP_MOVED_TEMP
                        || response.status == 307 || response.status == 308) {
                    if (redirects == 3) throw new IOException("HTTPS link redirected too many times");
                    if (response.location == null) throw new IOException("HTTPS redirect has no destination");
                    try { current = requireHttps(current.resolve(response.location).toString()); }
                    catch (IllegalArgumentException invalid) {
                        throw new IOException("HTTPS redirect is invalid", invalid);
                    }
                    continue;
                }
                if (response.status != HttpURLConnection.HTTP_OK || response.body == null)
                    throw new IOException("HTTPS ZIP download failed with status " + response.status);
                if (response.length > maximum) throw new IOException("Shared ZIP exceeds download limit");
                try (InputStream limited = new Limited(response.body, maximum, cancellation,
                        clock, started, durationNanos)) {
                    AddonPackage addon = AddonPackage.fromZip(limited, privateRoot, kind);
                    return new Staged(addon, current.getHost(), redirects);
                }
            }
        }
        throw new IOException("HTTPS link redirected too many times");
    }

    private static void checkDeadline(Cancellation cancellation, Clock clock,
                                      long started, long durationNanos) throws IOException {
        if (cancellation.cancelled()) throw new IOException("Download canceled");
        if (clock.nowNanos() - started >= durationNanos)
            throw new IOException("Shared ZIP transfer exceeded time limit");
    }

    static Transport network() {
        return uri -> {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (privateAddress(address)) throw new IOException("HTTPS host resolves to a private address");
            java.net.URLConnection opened = uri.toURL().openConnection();
            if (!(opened instanceof HttpsURLConnection))
                throw new IOException("HTTPS connection was not available");
            HttpsURLConnection connection = (HttpsURLConnection) opened;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/zip, application/octet-stream");
            try {
                int status = connection.getResponseCode();
                InputStream body = status == HttpURLConnection.HTTP_OK ? connection.getInputStream() : null;
                return new Response(status, connection.getHeaderField("Location"),
                        connection.getContentLengthLong(), body, connection::disconnect);
            } catch (IOException failure) { connection.disconnect(); throw failure; }
        };
    }

    static final class Limited extends FilterInputStream {
        private final long maximum;
        private final Cancellation cancellation;
        private final Clock clock;
        private final long started, duration;
        private long count;
        Limited(InputStream input, long maximum, Cancellation cancellation) {
            this(input, maximum, cancellation, System::nanoTime, System.nanoTime(),
                    java.util.concurrent.TimeUnit.MINUTES.toNanos(2));
        }
        Limited(InputStream input, long maximum, Cancellation cancellation, Clock clock,
                long started, long duration) {
            super(input); this.maximum = maximum; this.cancellation = cancellation;
            this.clock = clock; this.started = started; this.duration = duration;
        }
        @Override public int read() throws IOException {
            checkDeadline(cancellation, clock, started, duration);
            int value = in.read();
            checkDeadline(cancellation, clock, started, duration);
            if (value >= 0 && ++count > maximum) throw new IOException("Shared ZIP exceeds download limit");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline(cancellation, clock, started, duration);
            int read = in.read(buffer, offset, length);
            checkDeadline(cancellation, clock, started, duration);
            if (read > 0 && (count += read) > maximum)
                throw new IOException("Shared ZIP exceeds download limit");
            return read;
        }
    }
}
