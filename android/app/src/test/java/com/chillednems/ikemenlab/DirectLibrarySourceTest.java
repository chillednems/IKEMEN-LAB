package com.chillednems.ikemenlab;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class DirectLibrarySourceTest {
    private static final class Entry implements LibraryFiles.Node {
        final String name;
        final boolean directory;
        Entry parent;
        byte[] bytes;
        final List<LibraryFiles.Node> children = new ArrayList<>();
        Entry(String name, boolean directory, String content) {
            this.name = name; this.directory = directory;
            this.bytes = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        }
        Entry add(Entry child) { child.parent = this; children.add(child); return this; }
        @Override public String name() { return name; }
        @Override public String displayPath() { return parent == null ? name : parent.displayPath() + "/" + name; }
        @Override public boolean directory() { return directory; }
        @Override public long size() { return -1; } // A provider may omit size even for real files.
        @Override public List<LibraryFiles.Node> children() { return children; }
        @Override public InputStream open() throws IOException {
            if (bytes == null) throw new IOException("Missing file");
            return new ByteArrayInputStream(bytes);
        }
        @Override public SeekableByteChannel openSeekable() throws IOException { throw new IOException("not seekable"); }
        @Override public LibraryFiles.Node parent() { return parent; }
    }

    private static Entry source() {
        Entry root = new Entry("IKEMEN", true, null);
        Entry fighter = new Entry("Demo", true, null)
                .add(new Entry("Demo.def", false, "[Info]\nname = First Name\nauthor = Tester\n"));
        root.add(new Entry("chars", true, null).add(fighter));
        root.add(new Entry("stages", true, null)
                .add(new Entry("arena.def", false, "[Info]\nname = Demo Arena\n")));
        return root;
    }

    @Test public void scannerReadsSourceNodesAndFreshMetadataWithoutCopiedFiles() throws Exception {
        Entry root = source();
        byte[] working = "[Characters]\nDemo/Demo.def\n[ExtraStages]\nstages/arena.def\n"
                .getBytes(StandardCharsets.UTF_8);
        LibraryScanner.Catalog first = LibraryScanner.scan(root, working);
        assertEquals("First Name", first.characters.get(0).name);
        assertEquals(Boolean.TRUE, first.characters.get(0).enabled);
        assertEquals("Demo Arena", first.stages.get(0).name);
        Entry def = (Entry) LibraryFiles.resolve(LibraryFiles.resolve(root, "chars/Demo"), "Demo.def");
        def.bytes = "[Info]\nname = Changed At Source\n".getBytes(StandardCharsets.UTF_8);
        LibraryScanner.Catalog refreshed = LibraryScanner.scan(root, working);
        assertEquals("Changed At Source", refreshed.characters.get(0).name);
        assertEquals(Boolean.TRUE, refreshed.characters.get(0).enabled);
    }

    @Test public void unknownSizeMetadataStillStopsAtBound() throws Exception {
        Entry source = new Entry("huge.def", false, "0123456789");
        try { LibraryFiles.readLimited(source, 4); fail("Oversized unknown-size stream accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
    }

    @Test public void unsafeOrAmbiguousPathsCannotSelectProviderEntries() throws Exception {
        Entry root = source();
        try { LibraryFiles.resolve(root, "chars/../stages/arena.def"); fail("Traversal accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Invalid")); }
        root.add(new Entry("CHARS", true, null));
        try { LibraryFiles.child(root, "chars"); fail("Case-ambiguous entry accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
    }
}
