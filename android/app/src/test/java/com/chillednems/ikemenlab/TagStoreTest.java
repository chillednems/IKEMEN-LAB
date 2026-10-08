package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.*;

public final class TagStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void manualTagsPersistAndStayIsolatedBySourceAndItem() throws Exception {
        TagStore first = new TagStore(temporary.getRoot(), "content://source/one");
        first.toggle("characters|Hero/Hero.def", "Favorite");
        first.toggle("characters|Hero/Hero.def", "Needs testing");
        assertEquals(2, new TagStore(temporary.getRoot(), "content://source/one")
                .get("characters|Hero/Hero.def").size());
        assertTrue(new TagStore(temporary.getRoot(), "content://source/two")
                .get("characters|Hero/Hero.def").isEmpty());
        assertTrue(first.get("characters|Other/Other.def").isEmpty());
        first.toggle("characters|Hero/Hero.def", "favorite");
        assertEquals(1, new TagStore(temporary.getRoot(), "content://source/one")
                .get("characters|Hero/Hero.def").size());
    }

    @Test public void invalidAndOverBudgetTagsAreRejectedWithoutChangingSavedState() throws Exception {
        TagStore tags = new TagStore(temporary.getRoot(), "source");
        try { tags.toggle("characters|Hero", "line\nbreak"); fail("Newline accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("1–24")); }
        for (int i = 0; i < 8; i++) tags.toggle("characters|Hero", "Tag " + i);
        try { tags.toggle("characters|Hero", "Ninth"); fail("Ninth tag accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("eight")); }
        assertEquals(8, new TagStore(temporary.getRoot(), "source").get("characters|Hero").size());
    }

    @Test public void inferenceUsesExplicitCuesAndAvoidsSubstringMatches() {
        assertTrue(TagInference.from("kof_hero", "KOF Hero", "POTS").contains("KOF"));
        assertTrue(TagInference.from("kof_hero", "KOF Hero", "POTS").contains("POTS Style"));
        assertFalse(TagInference.from("sfx_hero", "SFX Hero", "Someone").contains("Street Fighter"));
        assertFalse(TagInference.from("coffee", "Coffee", "Postman").contains("KOF"));
    }
}
