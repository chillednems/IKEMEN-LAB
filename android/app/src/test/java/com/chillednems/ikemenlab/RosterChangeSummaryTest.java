package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RosterChangeSummaryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void countsChangesAgainstDestinationAndSeparatesStateFromMembership() throws Exception {
        byte[] before = ("[Characters]\nKFM/KFM.def\n;chars/old.def\nchars/remove.def\n"
                + "[ExtraStages]\nstages/dojo.def\n").getBytes(StandardCharsets.UTF_8);
        byte[] after = ("[Characters]\n;KFM/KFM.def\nchars/old.def\nchars/add.def\n"
                + "[ExtraStages]\nstages/dojo.def\n").getBytes(StandardCharsets.UTF_8);
        RosterChangeSummary summary = RosterChangeSummary.compare(temp.getRoot(), before, after);
        assertEquals(1, summary.enabled);
        assertEquals(1, summary.disabled);
        assertEquals(1, summary.added);
        assertEquals(1, summary.removed);
    }
    @Test public void duplicateReferenceIsEnabledWhenAnyEntryIsActive() throws Exception {
        byte[] before = "[Characters]\nHero/Hero.def\n;Hero/Hero.def\n".getBytes(StandardCharsets.UTF_8);
        byte[] after = "[Characters]\n;Hero/Hero.def\n;Hero/Hero.def\n".getBytes(StandardCharsets.UTF_8);
        RosterChangeSummary summary = RosterChangeSummary.compare(temp.getRoot(), before, after);
        assertEquals(0, summary.enabled);
        assertEquals(1, summary.disabled);
        assertEquals(0, summary.added);
        assertEquals(0, summary.removed);
        assertFalse(summary.otherContentChanged);
        assertEquals(0, summary.reorderedPositions);
    }

    @Test public void reportsOnlyOrderChangeAcrossRandomEmptyAndDuplicateOptions() throws Exception {
        byte[] before = ("[Characters]\r\nAlpha, order=1\r\nrandomselect\r\nBeta\r\nempty\r\nAlpha, order=2\r\n"
                + "[ExtraStages]\r\nstages/dojo.def\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] after = ("[Characters]\r\nrandomselect\r\nAlpha, order=1\r\nempty\r\nBeta\r\nAlpha, order=2\r\n"
                + "[ExtraStages]\r\nstages/dojo.def\r\n").getBytes(StandardCharsets.UTF_8);
        RosterChangeSummary summary = RosterChangeSummary.compare(temp.getRoot(), before, after);
        assertEquals(0, summary.enabled); assertEquals(0, summary.disabled);
        assertEquals(0, summary.added); assertEquals(0, summary.removed);
        assertEquals(4, summary.reorderedPositions);
        assertFalse(summary.otherContentChanged);
    }

    @Test public void unchangedAndOptionOnlyEditsAreDistinguished() throws Exception {
        byte[] original = "[Characters]\nAlpha, order=1\nrandomselect\nempty\n".getBytes(StandardCharsets.UTF_8);
        RosterChangeSummary unchanged = RosterChangeSummary.compare(temp.getRoot(), original, original);
        assertEquals(0, unchanged.reorderedPositions);
        assertFalse(unchanged.otherContentChanged);
        byte[] options = "[Characters]\nAlpha, order=2\nrandomselect\nempty\n".getBytes(StandardCharsets.UTF_8);
        RosterChangeSummary changed = RosterChangeSummary.compare(temp.getRoot(), original, options);
        assertEquals(0, changed.reorderedPositions);
        assertTrue(changed.otherContentChanged);
    }
}
