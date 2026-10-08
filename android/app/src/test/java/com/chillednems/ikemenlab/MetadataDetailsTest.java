package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class MetadataDetailsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Entry implements LibraryFiles.Node {
        final String name;
        final boolean folder;
        byte[] bytes;
        Entry parent;
        final List<LibraryFiles.Node> children = new ArrayList<>();
        Entry(String name, String text) { this.name = name; folder = text == null; bytes = text == null ? null : text.getBytes(StandardCharsets.UTF_8); }
        Entry add(Entry child) { child.parent = this; children.add(child); return child; }
        @Override public String name() { return name; }
        @Override public String displayPath() { return name; }
        @Override public boolean directory() { return folder; }
        @Override public long size() { return bytes == null ? -1 : bytes.length; }
        @Override public List<LibraryFiles.Node> children() { return children; }
        @Override public InputStream open() { return new ByteArrayInputStream(bytes); }
        @Override public SeekableByteChannel openSeekable() throws IOException { throw new IOException("read only"); }
        @Override public LibraryFiles.Node parent() { return parent; }
    }

    @Test public void readsDeclaredFactsAndStaticCommandsWithoutTreatingStatesAsInputs() throws Exception {
        Entry folder = new Entry("Hero", null);
        Entry def = folder.add(new Entry("Hero.def", "[Info]\nname = Hero\nauthor = Tester\nversiondate = 2026\n"
                + "mugenversion = 1.1\n[Files]\ncmd = Hero.cmd\npal1 = hero.act\n"));
        folder.add(new Entry("Hero.cmd", "[Command]\nname = Fireball\ncommand = ~D, DF, F, x\n"
                + "[Command]\nname = Incomplete\n[State -1]\nname = Not a command\n"));
        MetadataDetails details = MetadataDetails.read(new LibraryScanner.Item("characters", "Hero/Hero.def", "Hero",
                "Tester", true, def, null, 0, 0, null));
        assertTrue(details.fields.contains("Version date: 2026"));
        assertTrue(details.fields.contains("Palette 1: hero.act"));
        assertEquals(1, details.commands.size());
        assertTrue(details.commands.get(0).contains("Fireball · ~D, DF, F, x"));
        assertTrue(details.commandNotice.contains("unverified"));
    }

    @Test public void unsafeCmdPathDoesNotEscapeSelectedSource() throws Exception {
        Entry folder = new Entry("Hero", null);
        Entry def = folder.add(new Entry("Hero.def", "[Files]\ncmd = ../outside.cmd\n"));
        MetadataDetails details = MetadataDetails.read(new LibraryScanner.Item("characters", "Hero/Hero.def", "Hero",
                "Tester", true, def, null, 0, 0, null));
        assertTrue(details.commands.isEmpty());
        assertTrue(details.commandNotice.contains("unsafe"));
    }

    @Test public void metadataBoundAndLegacyEncodingAreHandled() throws Exception {
        Entry huge = new Entry("huge.def", "a".repeat(MetadataDetails.MAX_FILE_BYTES + 1));
        try { MetadataDetails.boundedText(huge); fail("Oversized DEF accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
        byte[] western = "[Info]\nauthor = Caf\u00e9\n".getBytes(Charset.forName("windows-1252"));
        assertTrue(LibraryScanner.readTextBytes(western, "legacy.def").contains("Caf\u00e9"));
        try { MetadataDetails.parseCommands("[Command]\nname=x\n".repeat(10_001)); fail("Excessive lines accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("lines")); }
    }

    @Test public void stageShowsBoundsAndMusicButNoCommandInputs() throws Exception {
        Entry folder = new Entry("stages", null);
        Entry def = folder.add(new Entry("arena.def", "[Info]\nname=Arena\n[Camera]\nboundleft=-100\n"
                + "boundright=100\n[Music]\nbgmusic=theme.ogg\n"));
        MetadataDetails details = MetadataDetails.read(new LibraryScanner.Item("extrastages", "stages/arena.def", "Arena",
                "Tester", true, def, null, 0, 0, null));
        assertTrue(details.fields.contains("Left bound: -100"));
        assertTrue(details.fields.contains("Music: theme.ogg"));
        assertTrue(details.commands.isEmpty());
    }
}
