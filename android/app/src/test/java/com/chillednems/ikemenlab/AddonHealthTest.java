package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class AddonHealthTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nestedStageReferencesResolveBesideSelectedDef() throws Exception {
        File root = temporary.newFolder("library");
        File stage = new File(root, "stages/sub");
        assertTrue(stage.mkdirs());
        Files.write(new File(stage, "arena.def").toPath(),
                "[Info]\nname = Arena\n[BGDef]\nspr = arena.sff\n[Music]\nbgmusic = missing.ogg\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stage, "arena.sff").toPath(), new byte[]{1});
        LibraryFiles.Node source = LibraryFiles.local(root);
        LibraryFiles.Node def = LibraryFiles.resolve(source, "stages/sub/arena.def");
        LibraryScanner.Item item = new LibraryScanner.Item("stages", "stages/sub/arena.def", "Arena", "QA",
                null, def, null, 0, 0, null);
        AddonHealth.Report report = AddonHealth.inspectLibrary(item, source);
        assertFalse(report.blocked);
        assertEquals(2, report.checked);
        assertFalse(report.summary().contains("missing arena.sff"));
        assertTrue(report.summary().contains("optional music"));
    }

    @Test public void importRequiresDeclaredMandatoryFilesAndReportsExistingDependencies() throws Exception {
        File source = temporary.newFolder("source");
        File sound = new File(source, "sound");
        assertTrue(sound.mkdir());
        Files.write(new File(sound, "shared.snd").toPath(), new byte[]{2});
        File addon = temporary.newFolder("Hero");
        Files.write(new File(addon, "Hero.def").toPath(),
                "[Info]\nname=Hero\n[Files]\nspr=hero.sff\ncmd=missing.cmd\nsnd=sound/shared.snd\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(addon, "hero.sff").toPath(), new byte[]{1});
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("stage"), "chars");
        AddonHealth.Report report = AddonHealth.inspectStage(staged, LibraryFiles.local(source));
        assertTrue(report.blocked);
        assertEquals(1, report.external);
        assertTrue(report.summary().contains("missing missing.cmd"));
        Files.write(new File(addon, "missing.cmd").toPath(), new byte[]{3});
        AddonPackage complete = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("stage-again"), "chars");
        assertFalse(AddonHealth.inspectStage(complete, LibraryFiles.local(source)).blocked);
        Files.delete(new File(sound, "shared.snd").toPath());
        assertTrue(AddonHealth.inspectStage(complete, LibraryFiles.local(source)).blocked);
    }

    @Test public void unsupportedStageDefDoesNotLookHealthy() throws Exception {
        File source = temporary.newFolder("unsupported-source");
        File addon = temporary.newFolder("UnsupportedStage");
        Files.write(new File(addon, "UnsupportedStage.def").toPath(),
                "[Unknown]\nvalue=1\n".getBytes(StandardCharsets.UTF_8));
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("unsupported-stage"), "stages");
        AddonHealth.Report report = AddonHealth.inspectStage(staged, LibraryFiles.local(source));
        assertTrue(report.blocked);
        assertTrue(report.summary().contains("Stage DEF needs"));
    }

    @Test public void oversizedRootDefIsRejectedBeforeDecode() throws Exception {
        File addon = temporary.newFolder("LargeHero");
        File def = new File(addon, "LargeHero.def");
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(def, "rw")) {
            output.setLength(1024 * 1024 + 1);
        }
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(addon),
                temporary.newFolder("large-stage"), "chars");
        try {
            AddonHealth.inspectStage(staged, LibraryFiles.local(temporary.newFolder("large-source")));
            fail("Oversized DEF accepted");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("1 MB")); }
    }

    @Test public void fullSignatureIncludesFindingsOmittedFromUiSummary() {
        AddonHealth.Report before = new AddonHealth.Report();
        AddonHealth.Report after = new AddonHealth.Report();
        for (int index = 0; index < 13; index++) {
            before.findings.add("reference " + index);
            after.findings.add("reference " + index);
        }
        after.findings.set(12, "changed reference");
        assertEquals(before.summary(), after.summary());
        assertNotEquals(before.signature(), after.signature());
    }
}
