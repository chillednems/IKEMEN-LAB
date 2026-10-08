package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class AddonPackageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private byte[] zip(String... namesAndBodies) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBodies[i]));
                zip.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test public void folderStagesOnlyOneAddonAndLeavesSourceBytes() throws Exception {
        File addon = temporary.newFolder("Hero");
        byte[] def = "[Info]\nname=Hero\n[Files]\nspr=hero.sff\n".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(addon, "Hero.def").toPath(), def);
        Files.write(new File(addon, "hero.sff").toPath(), new byte[]{1, 2, 3});
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("private"), "chars");
        assertEquals("Hero", staged.name);
        assertEquals(2, staged.files.size());
        assertArrayEquals(def, Files.readAllBytes(new File(addon, "Hero.def").toPath()));
        assertTrue(new File(staged.directory, "Hero.def").isFile());
    }

    @Test public void addonFileNamedLikeJournalCannotMasqueradeAsRecoveryState() throws Exception {
        File addon = temporary.newFolder("JournalHero");
        Files.write(new File(addon, "JournalHero.def").toPath(),
                "[Info]\nname=JournalHero\n[Files]\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(addon, "install.properties").toPath(),
                "version=1\nsource=content://synthetic-source\nparent=chars\npendingId=existing-folder\ncreated=0\n"
                        .getBytes(StandardCharsets.UTF_8));
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("journal-private"), "chars");
        assertTrue(new File(staged.directory, "install.properties").isFile());
        assertNull(AddonInstallTransaction.pendingDescription(staged.stageRoot));
        try {
            AddonPackage.reopen(staged.stageRoot, ".", staged.name, staged.kind,
                    staged.files, staged.totalBytes);
            fail("Journal can reopen metadata root as add-on content");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("stage path")); }
    }

    @Test public void zipStagesSingleRootAndRejectsCaseFoldedDirectoryConflict() throws Exception {
        AddonPackage staged = AddonPackage.fromZip(new ByteArrayInputStream(zip(
                "Hero/Hero.def", "[Files]\n", "Hero/hero.sff", "pixels")),
                temporary.newFolder("zip-stage"), "chars");
        assertEquals("Hero", staged.name);
        assertEquals("Hero.def", staged.files.get(0).path);
        try {
            AddonPackage.fromZip(new ByteArrayInputStream(zip("Hero.def", "[Files]\n",
                    "A/x", "1", "a/y", "2")), temporary.newFolder("conflict"), "chars");
            fail("Case-folded folder conflict accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("conflicting add-on path")); }
    }

    @Test public void zipRejectsHighExpansionAndLinkModeRegardlessOfHost() throws Exception {
        String repeated = "A".repeat(200_000);
        try {
            AddonPackage.fromZip(new ByteArrayInputStream(zip("Hero.def", "[Files]\n",
                    "huge.txt", repeated)), temporary.newFolder("ratio"), "chars");
            fail("Compression bomb accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("unsupported")); }

        byte[] link = zip("Hero.def", "[Files]\n", "link", "target");
        int central = -1;
        for (int i = 0; i < link.length - 46; i++)
            if ((link[i] & 255) == 0x50 && (link[i + 1] & 255) == 0x4b
                    && (link[i + 2] & 255) == 1 && (link[i + 3] & 255) == 2) central = i;
        assertTrue(central >= 0);
        link[central + 5] = 7; // non-Unix host marker
        int mode = 0120000 << 16;
        for (int i = 0; i < 4; i++) link[central + 38 + i] = (byte) (mode >>> (8 * i));
        try {
            AddonPackage.fromZip(new ByteArrayInputStream(link), temporary.newFolder("link-stage"), "chars");
            fail("Link mode accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("links")); }
    }

    @Test public void localFolderLinkIsRejectedBeforeCopy() throws Exception {
        File addon = temporary.newFolder("LinkedHero");
        File def = new File(addon, "LinkedHero.def");
        Files.write(def.toPath(), "[Files]\n".getBytes(StandardCharsets.UTF_8));
        File outside = temporary.newFile("outside.sff");
        try { Files.createSymbolicLink(new File(addon, "linked.sff").toPath(), outside.toPath()); }
        catch (UnsupportedOperationException | IOException unsupported) {
            org.junit.Assume.assumeNoException(unsupported); return;
        }
        try {
            AddonPackage.fromFolder(LibraryFiles.local(addon), temporary.newFolder("link-private"), "chars");
            fail("Folder symlink accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("links")); }
    }

    @Test public void zipRejectsDirectoryEntryWithInflatedData() throws Exception {
        try {
            AddonPackage.fromZip(new ByteArrayInputStream(zip("Hero.def", "[Files]\n",
                    "folder/", "unexpected")), temporary.newFolder("directory-data"), "chars");
            fail("Nonempty directory accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("directory entry contains data")); }
    }
}
