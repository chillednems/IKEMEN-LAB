package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class SharedZipSourceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void followsOnlyExplicitBoundedHttpsRedirectsThenStagesPrivately() throws Exception {
        byte[] zip = zip();
        List<URI> opened = new ArrayList<>();
        SharedZipSource.Transport transport = uri -> {
            opened.add(uri);
            if (opened.size() == 1)
                return new SharedZipSource.Response(302,
                        "https://release-assets.githubusercontent.com/Hero.zip", 0, null, null);
            return new SharedZipSource.Response(200, null, zip.length,
                    new ByteArrayInputStream(zip), null);
        };
        assertTrue(opened.isEmpty());
        SharedZipSource.Staged staged = SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip",
                temporary.newFolder("private"), "chars", () -> false, transport, AddonPackage.MAX_ARCHIVE);
        assertEquals(2, opened.size());
        assertEquals("release-assets.githubusercontent.com", staged.host);
        assertEquals(1, staged.redirects);
        assertEquals("Hero", staged.addon.name);
    }

    @Test public void rejectsDowngradeRedirectOverlongBodyAndCancellation() throws Exception {
        SharedZipSource.Transport downgrade = uri ->
                new SharedZipSource.Response(302, "http://example.org/Hero.zip", 0, null, null);
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip", temporary.newFolder("down"),
                "chars", () -> false, downgrade, 1000); fail("Downgrade accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }

        byte[] zip = zip();
        SharedZipSource.Transport oversize = uri -> new SharedZipSource.Response(200, null, -1,
                new ByteArrayInputStream(zip), null);
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip", temporary.newFolder("large"),
                "chars", () -> false, oversize, zip.length - 1); fail("Oversized stream accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }

        List<URI> opened = new ArrayList<>();
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip", temporary.newFolder("cancel"),
                "chars", () -> true, uri -> { opened.add(uri); return null; }, 1000);
            fail("Canceled transfer opened network"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("canceled")); }
        assertTrue(opened.isEmpty());
    }

    @Test public void rejectsUnlistedHostsBeforeNetworkAndAtEveryRedirect() throws Exception {
        List<URI> opened = new ArrayList<>();
        SharedZipSource.Transport transport = uri -> {
            opened.add(uri);
            return new SharedZipSource.Response(302, "https://other.example.org/Hero.zip", 0, null, null);
        };
        try { SharedZipSource.stage("https://other.example.org/Hero.zip", temporary.newFolder("initial"),
                "chars", () -> false, transport, 1000); fail("Unlisted host fetched"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("browser")); }
        assertTrue(opened.isEmpty());
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip",
                temporary.newFolder("redirect"), "chars", () -> false, transport, 1000);
            fail("Unlisted redirect fetched"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("browser")); }
        assertEquals(1, opened.size());
        assertTrue(SharedZipSource.trustedDownloadHost(URI.create("https://GITHUB.COM/x")));
        assertFalse(SharedZipSource.trustedDownloadHost(URI.create("https://github.com./x")));
        assertFalse(SharedZipSource.trustedDownloadHost(URI.create("https://github.com.evil.org/x")));
        try { SharedZipSource.requireHttps("https://user:password@github.com/x"); fail("Credentials accepted"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void preflightRecognizesPrivateAddresses() throws Exception {
        assertTrue(SharedZipSource.privateAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SharedZipSource.privateAddress(InetAddress.getByName("10.2.3.4")));
        assertTrue(SharedZipSource.privateAddress(InetAddress.getByName("100.64.1.1")));
        assertFalse(SharedZipSource.privateAddress(InetAddress.getByName("93.184.215.14")));
    }

    @Test public void fourthRedirectAndCanceledContentStreamNeverReachImportReview() throws Exception {
        List<URI> opened = new ArrayList<>();
        SharedZipSource.Transport repeat = uri -> {
            opened.add(uri);
            return new SharedZipSource.Response(302, "https://objects.githubusercontent.com/file.zip",
                    0, null, null);
        };
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip",
                temporary.newFolder("redirect-loop"), "chars", () -> false, repeat,
                AddonPackage.MAX_ARCHIVE); fail("Fourth redirect accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("too many")); }
        assertEquals(4, opened.size());

        try (SharedZipSource.Limited stream = new SharedZipSource.Limited(
                new ByteArrayInputStream(zip()), AddonPackage.MAX_ARCHIVE, () -> true)) {
            stream.read(); fail("Canceled shared content was read");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("canceled")); }
    }

    @Test public void wholeTransferDeadlineStopsSlowBodyAndRedirects() throws Exception {
        byte[] zip = zip();
        AtomicLong ticks = new AtomicLong();
        SharedZipSource.Transport slowBody = uri -> new SharedZipSource.Response(200, null, -1,
                new ByteArrayInputStream(zip) {
                    @Override public synchronized int read(byte[] target, int offset, int length) {
                        ticks.addAndGet(6);
                        return super.read(target, offset, length);
                    }
                }, null);
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip",
                temporary.newFolder("slow-body"), "chars", () -> false, slowBody,
                AddonPackage.MAX_ARCHIVE, ticks::get, 10);
            fail("Slow body exceeded whole-transfer deadline"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("time limit")); }

        ticks.set(0);
        SharedZipSource.Transport slowRedirect = uri -> {
            ticks.addAndGet(11);
            return new SharedZipSource.Response(302, "https://objects.githubusercontent.com/file.zip",
                    0, null, null);
        };
        try { SharedZipSource.stage("https://github.com/owner/repo/releases/download/Hero.zip",
                temporary.newFolder("slow-redirect"), "chars", () -> false, slowRedirect,
                AddonPackage.MAX_ARCHIVE, ticks::get, 10);
            fail("Slow redirect exceeded whole-transfer deadline"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("time limit")); }
    }

    private static byte[] zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("Hero.def"));
            output.write("[Info]\nname=Hero\n[Files]\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
