package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class RosterDiagnosticsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void reportsMissingAlternateDefAndStageButIgnoresSpecialAndProse() throws Exception {
        File root = folder.newFolder();
        File chars = new File(root, "chars/KFM");
        File stages = new File(root, "stages");
        assertTrue(chars.mkdirs()); assertTrue(stages.mkdir());
        Files.write(new File(chars, "KFM.def").toPath(), new byte[0]);
        Files.write(new File(stages, "existing.def").toPath(), new byte[0]);
        String roster = "[Characters]\r\nKFM/KFM.def\r\nKFM/alternate.def\r\nrandomselect\r\n; guide text\r\n; missing/disabled.def\r\n"
                + "[ExtraStages]\nstages/existing.def\nstages/lost.def\n; add your stages here\n";
        List<RosterDiagnostics.Warning> warnings = RosterDiagnostics.scan(root, roster.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, warnings.size());
        assertEquals("KFM/alternate.def", warnings.get(0).rawReference);
        assertEquals("missing/disabled.def", warnings.get(1).rawReference);
        assertFalse(warnings.get(1).active);
        assertEquals("stages/lost.def", warnings.get(2).rawReference);
    }
    @Test public void missingRowsAreVisibleAndDisableEditsOnlyTheirExactReference() throws Exception {
        File root = folder.newFolder();
        File chars = new File(root, "chars/KFM");
        File stages = new File(root, "stages");
        File data = new File(root, "data");
        assertTrue(chars.mkdirs()); assertTrue(stages.mkdir()); assertTrue(data.mkdir());
        Files.write(new File(chars, "KFM.def").toPath(), "[Info]\nname=KFM\n".getBytes(StandardCharsets.UTF_8));
        File select = new File(data, "select.def");
        Files.write(select.toPath(), "[Characters]\nKFM/KFM.def\nKFM/alternate.def\n".getBytes(StandardCharsets.UTF_8));
        LibraryScanner.Catalog catalog = LibraryScanner.scan(root);
        assertEquals(2, catalog.characters.size());
        LibraryScanner.Item missing = catalog.characters.get(1);
        assertEquals("KFM/alternate.def", missing.reference);
        assertNotNull(missing.warning);
        try { RosterStore.setEnabled(root, missing, true); fail(); } catch (java.io.IOException expected) { }
        RosterStore.setEnabled(root, missing, false);
        String text = new String(Files.readAllBytes(select.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\nKFM/KFM.def\n;KFM/alternate.def\n"));
    }
    @Test public void instructionalExamplesAreNotRosterRowsButRealMissingNamesRemain() throws Exception {
        File root = folder.newFolder();
        assertTrue(new File(root, "chars").mkdir());
        assertTrue(new File(root, "stages").mkdir());
        String roster = "[Characters]\n"
                + ";Use the format:\n;    sample, stages/example.def\n;\n"
                + ";This example loads chars/sample/alternate.def:\n;    sample/alternate.def, stages/example.def\n;\n"
                + ";Place the ZIP file in the chars/ directory. The syntax is as\n;follows:\n"
                + ";    example.zip/inside.def, stages/example.def\n;\n"
                + ";Insert your characters below.\n"
                + "Missing (Hero)/Missing (Hero).def\n"
                + ";架空 (風)/架空 (風).def\n"
                + "[ExtraStages]\n;Examples:\n; stages/demo.def, order=3\n;\n"
                + ";Insert your stages below.\n;stages/Lost Stage.def\n";
        List<RosterDiagnostics.Entry> entries = RosterDiagnostics.entries(root, roster.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, entries.size());
        assertEquals("Missing (Hero)/Missing (Hero).def", entries.get(0).rawReference);
        assertTrue(entries.get(0).active);
        assertEquals("架空 (風)/架空 (風).def", entries.get(1).rawReference);
        assertFalse(entries.get(1).active);
        assertEquals("stages/Lost Stage.def", entries.get(2).rawReference);
        assertFalse(entries.get(2).active);
        assertEquals(3, RosterDiagnostics.scan(root, roster.getBytes(StandardCharsets.UTF_8)).size());
    }
    @Test public void referenceNamesAndOptionStagesContainingDocumentationWordsAreRealRows() throws Exception {
        File root = folder.newFolder();
        assertTrue(new File(root, "chars").mkdir());
        String roster = "[Characters]\n"
                + ";Example Hero/Example Hero.def\n"
                + ";Format Hero/Format Hero.def\n"
                + ";Hero/Hero.def, stages/Example Arena.def\n";
        List<RosterDiagnostics.Entry> entries = RosterDiagnostics.entries(root, roster.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, entries.size());
        assertEquals("Example Hero/Example Hero.def", entries.get(0).rawReference);
        assertEquals("Format Hero/Format Hero.def", entries.get(1).rawReference);
        assertEquals("Hero/Hero.def", entries.get(2).rawReference);
        assertEquals(3, RosterDiagnostics.scan(root, roster.getBytes(StandardCharsets.UTF_8)).size());
    }
}
