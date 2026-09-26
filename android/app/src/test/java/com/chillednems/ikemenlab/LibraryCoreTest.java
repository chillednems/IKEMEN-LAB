package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public final class LibraryCoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rowActivationRequiresSelectionBeforeToggleAndIgnoresBusyPresses() {
        String first = "characters|KFM/KFM.def";
        String second = "stages|dojo.def";
        assertEquals(RowActivation.Action.SELECT, RowActivation.decide(null, first, false));
        assertEquals(RowActivation.Action.TOGGLE, RowActivation.decide(first, first, false));
        assertEquals(RowActivation.Action.SELECT, RowActivation.decide(first, second, false));
        assertEquals(RowActivation.Action.IGNORE, RowActivation.decide(first, first, true));
    }

    @Test public void defMetadataKeepsQuotedSemicolonAndSections() {
        Map<String, Map<String, String>> parsed = DefParser.parse("[Info]\r\nname = \"Hero; Alpha\" ; note\r\nauthor = Creator\r\n");
        assertEquals("Hero; Alpha", DefParser.value(parsed, "info", "name", "missing"));
        assertEquals("Creator", DefParser.value(parsed, "info", "author", "missing"));
    }

    @Test public void rosterTogglePreservesUnknownDataAndCrLf() {
        String original = "; preface\r\n[Characters]\r\n  KFM/KFM.Def, order=2 ; special\r\n; Other\r\n[ExtraStages]\r\nstages/foo.def\r\n[Options]\r\nkeep = unknown\r\n";
        SelectDefEditor editor = new SelectDefEditor(original);
        assertEquals(Boolean.TRUE, editor.isEnabled("characters", "KFM/KFM.Def"));
        editor.setEnabled("characters", "KFM/KFM.Def", false);
        assertEquals(Boolean.FALSE, editor.isEnabled("characters", "KFM/KFM.Def"));
        assertEquals(original.replace("  KFM/KFM.Def", "  ;KFM/KFM.Def"), editor.content());
        editor.setEnabled("characters", "KFM/KFM.Def", true);
        assertEquals(original, editor.content());
        editor.setEnabled("extrastages", "stages/new.def", true);
        assertTrue(editor.content().contains("stages/new.def\r\n[Options]"));
        assertTrue(editor.content().contains("keep = unknown\r\n"));
    }

    @Test public void unsafeExistingReferenceDoesNotControlRealItem() {
        SelectDefEditor editor = new SelectDefEditor("[Characters]\nKFM/../../escape.def\n/absolute/KFM.def\n");
        assertNull(editor.isEnabled("characters", "KFM/KFM.def"));
        editor.setEnabled("characters", "KFM/KFM.def", true);
        assertTrue(editor.content().contains("KFM/../../escape.def\n"));
        assertTrue(editor.content().contains("KFM/KFM.def\n"));
    }

    @Test public void sameFolderAlternativeDefIsUntouched() {
        String original = "[Characters]\nKFM/KFM.def\nKFM/alternate.def\n";
        SelectDefEditor editor = new SelectDefEditor(original);
        editor.setEnabled("characters", "KFM/KFM.def", false);
        assertEquals("[Characters]\n;KFM/KFM.def\nKFM/alternate.def\n", editor.content());
    }

    @Test public void loneCrRosterIsEditedWithoutDuplicateSection() {
        String original = "[Characters]\rKFM/KFM.def\r[Options]\rkeep=1\r";
        SelectDefEditor editor = new SelectDefEditor(original);
        assertEquals(Boolean.TRUE, editor.isEnabled("characters", "KFM/KFM.def"));
        editor.setEnabled("characters", "KFM/KFM.def", false);
        assertEquals("[Characters]\r;KFM/KFM.def\r[Options]\rkeep=1\r", editor.content());
    }

    @Test public void scannerHandlesMixedCaseFoldersNestedStagesAndBackup() throws Exception {
        File root = temporary.newFolder("game");
        File character = new File(root, "ChArS/KFM");
        File stage = new File(root, "stages/deep");
        File data = new File(root, "DATA");
        assertTrue(character.mkdirs()); assertTrue(stage.mkdirs()); assertTrue(data.mkdirs());
        Files.write(new File(character, "KFM.Def").toPath(), "[Info]\nname = Kung Fu Man\nauthor = Elecbyte\n[Files]\nsprite = KFM.sff\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(character, "KFM.sff").toPath(), new byte[] {1});
        Files.write(new File(stage, "dojo.def").toPath(), "[Info]\nname = Dojo\n[BGdef]\nspr = dojo.sff\n[BG 0]\nspriteno = 20,3\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stage, "dojo.sff").toPath(), new byte[] {1});
        String initial = "[Characters]\r\nKFM/KFM.Def\r\n[ExtraStages]\r\nstages/deep/dojo.def\r\n[Options]\r\nopaque=1\r\n";
        File select = new File(data, "SELECT.DEF");
        Files.write(select.toPath(), initial.getBytes(StandardCharsets.UTF_8));

        LibraryScanner.Catalog catalog = LibraryScanner.scan(root);
        assertEquals(1, catalog.characters.size()); assertEquals(1, catalog.stages.size());
        assertEquals("Kung Fu Man", catalog.characters.get(0).name);
        assertEquals(Boolean.TRUE, catalog.characters.get(0).enabled);
        assertEquals(Boolean.TRUE, catalog.stages.get(0).enabled);
        assertTrue(catalog.characters.get(0).previewFile.endsWith("KFM.sff"));
        assertTrue(catalog.stages.get(0).previewFile.endsWith("dojo.sff"));
        assertEquals(20, catalog.stages.get(0).previewGroup);
        assertEquals(3, catalog.stages.get(0).previewImage);
        RosterStore.setEnabled(root, catalog.characters.get(0), false);
        assertTrue(new String(Files.readAllBytes(select.toPath()), StandardCharsets.UTF_8).contains(";KFM/KFM.Def"));
        assertTrue(new String(Files.readAllBytes(select.toPath()), StandardCharsets.UTF_8).contains("opaque=1\r\n"));
        SelectStorage storage = new SelectStorage(root);
        assertEquals(1, storage.listVersions().size());
        assertEquals(initial, new String(storage.readVersion(storage.listVersions().get(0).id), StandardCharsets.UTF_8));
    }

    @Test public void importLimitsRejectTraversalAndExcessBytes() throws IOException {
        ImportLimits limits = new ImportLimits();
        for (String name : new String[] {"..", "../escape", "/tmp", "a\\b", ""}) {
            try { limits.enter(name, 1); fail("accepted " + name); } catch (IOException expected) { }
        }
        limits.enter("safe.def", 1);
        limits.addBytes(42);
        try { limits.enter("deep", ImportLimits.MAX_DEPTH + 1); fail("depth accepted"); } catch (IOException expected) { }
    }

    @Test public void rosterEditRetainsLegacyEncoding() throws Exception {
        File root = temporary.newFolder("legacy");
        File chars = new File(root, "chars"); File stages = new File(root, "stages"); File data = new File(root, "data");
        assertTrue(chars.mkdirs()); assertTrue(stages.mkdirs()); assertTrue(data.mkdirs());
        File select = new File(data, "select.def");
        String original = "; café\n[Characters]\nKFM/KFM.def\n";
        Files.write(select.toPath(), original.getBytes(java.nio.charset.Charset.forName("windows-1252")));
        LibraryScanner.Item item = new LibraryScanner.Item("characters", "KFM/KFM.def", "KFM", "Author", "unused", true);
        RosterStore.setEnabled(root, item, false);
        assertEquals("; café\n[Characters]\n;KFM/KFM.def\n",
                new String(Files.readAllBytes(select.toPath()), java.nio.charset.Charset.forName("windows-1252")));
    }

    @Test public void shiftJisRosterEditPreservesUnrelatedBytes() throws Exception {
        File root = temporary.newFolder("shiftjis");
        File data = new File(root, "data"); assertTrue(data.mkdirs());
        File select = new File(data, "select.def");
        java.nio.charset.Charset shiftJis = java.nio.charset.Charset.forName("Shift_JIS");
        String original = "; 日本語の説明\r\n[Characters]\r\nKFM/KFM.def\r\n[Options]\r\nopaque = 保存\r\n";
        byte[] before = original.getBytes(shiftJis);
        Files.write(select.toPath(), before);
        LibraryScanner.Item item = new LibraryScanner.Item("characters", "KFM/KFM.def", "KFM", "Author", "unused", true);
        RosterStore.setEnabled(root, item, false);
        byte[] after = Files.readAllBytes(select.toPath());
        assertArrayEquals(original.replace("KFM/KFM.def", ";KFM/KFM.def").getBytes(shiftJis), after);
        SelectStorage storage = new SelectStorage(root);
        assertEquals(1, storage.listVersions().size());
        assertArrayEquals(before, storage.readVersion(storage.listVersions().get(0).id));
    }

    @Test public void scannerReadsUtf8BomSelect() throws Exception {
        File root = temporary.newFolder("bom");
        File character = new File(root, "chars/KFM"); File stages = new File(root, "stages"); File data = new File(root, "data");
        assertTrue(character.mkdirs()); assertTrue(stages.mkdirs()); assertTrue(data.mkdirs());
        Files.write(new File(character, "KFM.def").toPath(), "[Info]\nname=KFM\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(data, "select.def").toPath(), "\ufeff[Characters]\nKFM/KFM.def\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, LibraryScanner.scan(root).characters.get(0).enabled);
    }

    @Test public void scannerReadsJapaneseShiftJisMetadata() throws Exception {
        File file = temporary.newFile("japanese.def");
        String metadata = "[Info]\nname=日本語\nauthor=作成者\n";
        Files.write(file.toPath(), metadata.getBytes(java.nio.charset.Charset.forName("Shift_JIS")));
        assertEquals(metadata, LibraryScanner.readText(file));
    }

    @Test public void controllerDeadZoneAndDominantAxis() {
        assertEquals(ControllerPolicy.NONE, ControllerPolicy.stickDirection(.4f, .2f));
        assertEquals(ControllerPolicy.RIGHT, ControllerPolicy.stickDirection(.9f, .7f));
        assertEquals(ControllerPolicy.LEFT, ControllerPolicy.stickDirection(-.9f, .1f));
        assertEquals(ControllerPolicy.UP, ControllerPolicy.stickDirection(.1f, -.8f));
        assertEquals(ControllerPolicy.DOWN, ControllerPolicy.stickDirection(.1f, .8f));
    }
}
