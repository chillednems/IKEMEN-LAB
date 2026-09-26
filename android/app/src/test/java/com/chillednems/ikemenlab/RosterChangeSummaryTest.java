package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

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
    }
}
