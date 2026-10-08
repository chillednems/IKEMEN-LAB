package com.chillednems.ikemenlab;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class RosterProfileTest {
    @Test public void activationKeepsUnknownBytesSectionsAndStageOptions() throws Exception {
        byte[] before = ("; header\r\n[Characters]\r\nA/A.def, order=2 ; custom\r\n"
                + "; note stays\r\nrandomselect\r\n[ExtraStages]\r\n"
                + "stages/old.def, music=old.ogg\r\n[Options]\r\nkeep = \u00e9\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        RosterProfile.Snapshot saved = new RosterProfile(before).snapshot();
        assertEquals(Arrays.asList("A/A.def, order=2 ; custom", "randomselect"), saved.characters);
        assertEquals(Arrays.asList("stages/old.def, music=old.ogg"), saved.stages);
        byte[] after = new RosterProfile(before).activate(new RosterProfile.Snapshot(
                Arrays.asList("empty", "A/A.def, order=2 ; custom", "A/A.def, order=2 ; custom"),
                Arrays.asList("stages/new.def, music=new.ogg")));
        String text = new String(after, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("; header\r\n[Characters]\r\nempty\r\nA/A.def, order=2 ; custom\r\nA/A.def, order=2 ; custom\r\n; note stays\r\n"));
        assertTrue(text.contains("[ExtraStages]\r\nstages/new.def, music=new.ogg\r\n[Options]\r\nkeep = \u00e9\r\n"));
        assertTrue(new String(before, StandardCharsets.ISO_8859_1).contains("old.def"));
    }

    @Test public void activationPreservesUtf8BomAndTrailingUnknownSection() throws Exception {
        byte[] body = "[Characters]\nAlpha/Alpha.def\n[Unknown]\nkeep=1".getBytes(StandardCharsets.UTF_8);
        byte[] before = new byte[body.length + 3];
        before[0] = (byte) 239; before[1] = (byte) 187; before[2] = (byte) 191;
        System.arraycopy(body, 0, before, 3, body.length);
        byte[] after = new RosterProfile(before).activate(new RosterProfile.Snapshot(
                Arrays.asList("randomselect", "empty"), Arrays.asList("stages/arena.def")));
        assertEquals((byte) 239, after[0]);
        String text = new String(after, 3, after.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.contains("[Unknown]\nkeep=1\n[ExtraStages]\nstages/arena.def\n"));
    }

    @Test public void unchangedSnapshotKeepsMixedLineEndingsByteExact() throws Exception {
        byte[] original = "[Characters]\r\nA/A.def\n[ExtraStages]\rstages/a.def"
                .getBytes(StandardCharsets.UTF_8);
        RosterProfile parsed = new RosterProfile(original);
        assertArrayEquals(original, parsed.activate(parsed.snapshot()));
        byte[] stageOnly = parsed.activate(new RosterProfile.Snapshot(
                Arrays.asList("A/A.def"), Arrays.asList("stages/b.def")));
        assertTrue(new String(stageOnly, StandardCharsets.UTF_8).startsWith("[Characters]\r\nA/A.def\n[ExtraStages]\r"));
    }

    @Test public void malformedCollectionLineCannotInjectSectionOrEscape() throws Exception {
        RosterProfile profile = new RosterProfile("[Characters]\nA/A.def\n".getBytes(StandardCharsets.UTF_8));
        for (String unsafe : Arrays.asList("../outside.def", "A/A.def\n[Options]", ";A/A.def")) {
            try { profile.activate(new RosterProfile.Snapshot(Arrays.asList(unsafe), Arrays.asList())); fail(unsafe); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Collection entry")); }
        }
    }

    @Test public void editedDuplicateSectionsKeepBomMixedEndingsAndUnknownLines() throws Exception {
        byte[] body = ("[Characters]\r\nA/A.def\n; first comment\r[Characters]\r"
                + "B/B.def\r\n# separator\n[ExtraStages]\rstages/a.def\n[ExtraStages]\r\n"
                + "stages/b.def\r\n[Unknown]\rkeep = value").getBytes(StandardCharsets.UTF_8);
        byte[] before = new byte[body.length + 3];
        before[0] = (byte) 239; before[1] = (byte) 187; before[2] = (byte) 191;
        System.arraycopy(body, 0, before, 3, body.length);
        RosterProfile profile = new RosterProfile(before);
        assertEquals(Arrays.asList("A/A.def", "B/B.def"), profile.snapshot().characters);
        assertEquals(Arrays.asList("stages/a.def", "stages/b.def"), profile.snapshot().stages);
        byte[] after = profile.activate(new RosterProfile.Snapshot(
                Arrays.asList("B/B.def", "randomselect", "B/B.def"), Arrays.asList("stages/b.def")));
        assertEquals((byte) 239, after[0]);
        String text = new String(after, 3, after.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.contains("; first comment\r[Characters]\r"));
        assertTrue(text.contains("# separator\n[ExtraStages]\r"));
        assertTrue(text.contains("[ExtraStages]\r\n[Unknown]\rkeep = value"));
        assertTrue(text.contains("B/B.def\r\nrandomselect\r\nB/B.def"));
        assertFalse(text.contains("A/A.def"));
    }
}
