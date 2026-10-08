package com.chillednems.ikemenlab;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public final class RosterArrangementTest {
    @Test public void movesWholeActiveSlotsAndPreservesOtherBytes() throws Exception {
        String original = "[Characters]\r\n; guide\r\nKFM/KFM.def, order=2 ; custom\r\n"
                + ";disabled/disabled.def\r\nrandomselect\r\nempty\r\n[ExtraStages]\r\nstages/a.def\r\n";
        RosterArrangement arrangement = new RosterArrangement(original.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, arrangement.slots().size());
        assertEquals("Random select", arrangement.slots().get(1).title);
        String moved = new String(arrangement.moved(0, 1), StandardCharsets.UTF_8);
        assertEquals("[Characters]\r\n; guide\r\nrandomselect\r\n"
                + ";disabled/disabled.def\r\nKFM/KFM.def, order=2 ; custom\r\nempty\r\n"
                + "[ExtraStages]\r\nstages/a.def\r\n", moved);
    }

    @Test public void preservesBomLegacyBytesAndDuplicates() throws Exception {
        byte[] original = new byte[]{(byte)239,(byte)187,(byte)191,'[','C','h','a','r','a','c','t','e','r','s',']','\n',
                'a',',',' ',(byte)0xe9,'\n','a','\n','e','m','p','t','y'};
        RosterArrangement arrangement = new RosterArrangement(original);
        assertEquals(3, arrangement.slots().size());
        byte[] moved = arrangement.moved(1, -1);
        assertEquals((byte)239, moved[0]);
        assertTrue(new String(moved, StandardCharsets.ISO_8859_1).contains("a, é"));
    }

    @Test public void boundsPathologicalShortLineRosters() throws Exception {
        StringBuilder text = new StringBuilder("[Characters]\n");
        for (int i = 0; i < 30001; i++) text.append("a\n");
        try {
            new RosterArrangement(text.toString().getBytes(StandardCharsets.UTF_8));
            fail("Expected bounded preview");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("30,000"));
        }
    }

    @Test public void ignoresNonSlotNotesAndUnsupportedRandomTokens() throws Exception {
        String original = "[Characters]\nA\n# note\n---\nrandom\nrandomselect/extra\nB\nrandomselect\nempty\n";
        RosterArrangement arrangement = new RosterArrangement(original.getBytes(StandardCharsets.UTF_8));
        assertEquals(4, arrangement.slots().size());
        assertEquals("B", arrangement.slots().get(1).title);
        String moved = new String(arrangement.moved(0, 1), StandardCharsets.UTF_8);
        assertEquals("[Characters]\nB\n# note\n---\nrandom\nrandomselect/extra\nA\nrandomselect\nempty\n", moved);
    }
}
