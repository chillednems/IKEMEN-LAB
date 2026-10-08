package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.*;

public final class CollectionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void collectionsPersistPerSourceAndSupportUpdateDelete() throws Exception {
        File privateRoot = temporary.newFolder("collections");
        CollectionStore first = new CollectionStore(privateRoot, "content://source/one");
        CollectionStore.Record record = first.create("Favorites", false,
                new RosterProfile.Snapshot(Arrays.asList("A/A.def, order=2", "randomselect", "empty"),
                        Arrays.asList("stages/arena.def, music=theme.ogg")));
        assertEquals(1, new CollectionStore(privateRoot, "content://source/one").list().size());
        assertTrue(new CollectionStore(privateRoot, "content://source/two").list().isEmpty());
        try { new CollectionStore(privateRoot, "content://source/two").save(record); fail("Cross-source save accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("another source")); }
        record.name = "Renamed";
        first.save(record);
        assertEquals("Renamed", first.get(record.id).name);
        assertEquals("stages/arena.def, music=theme.ogg", first.get(record.id).stages.get(0));
        first.delete(record.id);
        assertTrue(first.list().isEmpty());
    }

    @Test public void smartRulesReevaluateSupportedCurrentCatalogFields() throws Exception {
        CollectionStore store = new CollectionStore(temporary.newFolder("smart"), "source");
        CollectionStore.Record smart = new CollectionStore.Record(UUID.randomUUID().toString(), "POTS fighters", true);
        smart.rules.add(new CollectionStore.Rule("type", "Characters"));
        smart.rules.add(new CollectionStore.Rule("inferredTag", "POTS Style"));
        store.save(smart);
        LibraryScanner.Catalog catalog = new LibraryScanner.Catalog();
        LibraryFiles.Node def = LibraryFiles.local(temporary.newFile("present.def"));
        catalog.characters.add(new LibraryScanner.Item("characters", "Hero/Hero.def", "Hero", "POTS", true,
                def, null, 9000, 1, null));
        catalog.characters.add(new LibraryScanner.Item("characters", "Other/Other.def", "Other", "Else", false,
                def, null, 9000, 1, null));
        TagStore tags = new TagStore(temporary.newFolder("tags"), "source");
        assertEquals(Arrays.asList("Hero/Hero.def"), SmartCollectionRules.evaluate(store.get(smart.id), catalog, tags).characters);
        catalog.characters.add(new LibraryScanner.Item("characters", "New/New.def", "New", "POTS", true,
                def, null, 9000, 1, null));
        assertEquals(2, SmartCollectionRules.evaluate(store.get(smart.id), catalog, tags).characters.size());

        smart.allRules = false;
        smart.rules.clear();
        smart.rules.add(new CollectionStore.Rule("name", "Hero"));
        smart.rules.add(new CollectionStore.Rule("status", "Disabled"));
        store.save(smart);
        catalog.characters.add(new LibraryScanner.Item("characters", "Missing/Missing.def", "Missing Hero", "", "Missing.def", true));
        catalog.characters.add(new LibraryScanner.Item("characters", "Warning/Warning.def", "Warning Hero", "", true,
                def, null, 9000, 1, "DEF unavailable"));
        assertEquals(Arrays.asList("Hero/Hero.def", "Other/Other.def"),
                SmartCollectionRules.evaluate(store.get(smart.id), catalog, tags).characters);
    }

    @Test public void malformedCollectionAndUnsupportedRuleFailClosed() throws Exception {
        CollectionStore store = new CollectionStore(temporary.newFolder("bad"), "source");
        CollectionStore.Record smart = new CollectionStore.Record(UUID.randomUUID().toString(), "Bad rule", true);
        smart.rules.add(new CollectionStore.Rule("installedAt", "yesterday"));
        try { store.save(smart); fail("Unsupported smart field accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsupported")); }
        CollectionStore.Record saved = store.create("Good", false,
                new RosterProfile.Snapshot(Arrays.asList("A/A.def"), Arrays.asList()));
        File file;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(temporary.getRoot().toPath())) {
            file = paths.filter(path -> path.getFileName().toString().equals(saved.id + ".properties"))
                    .findFirst().get().toFile();
        }
        Files.write(file.toPath(), "version=1\nname=Bad\nsmart=false\nallRules=true\ncharacters=999999\nstages=0\nrules=0\n"
                .getBytes(StandardCharsets.US_ASCII));
        try { store.get(saved.id); fail("Malformed entry count accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("count")); }
    }

    @Test public void activationPlanWarnsMissingAndStaleWorkingHashCannotCommit() throws Exception {
        File root = temporary.newFolder("working");
        File select = RosterStore.selectFile(root);
        assertTrue(select.getParentFile().mkdirs());
        byte[] original = "[Characters]\nA/A.def\n[Options]\nkeep=1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(select.toPath(), original);
        CollectionStore.Record chosen = new CollectionStore.Record(UUID.randomUUID().toString(), "Missing", false);
        chosen.characters.add("Missing/Missing.def");
        CollectionPlan plan = CollectionPlan.prepare(original, chosen, new LibraryScanner.Catalog(),
                new TagStore(temporary.newFolder("tags-plan"), "source"));
        assertEquals(Arrays.asList("Missing/Missing.def"), plan.missing);
        assertTrue(new String(plan.replacement, StandardCharsets.UTF_8).contains("[Options]\nkeep=1\n"));
        SelectStorage storage = new SelectStorage(root);
        storage.commitWorking(storage.readWorking().sha256, "[Characters]\nLater/Later.def\n".getBytes(StandardCharsets.UTF_8), "concurrent", null);
        try { storage.commitWorking(plan.workingSha, plan.replacement, "collection:activate", null); fail("Stale review committed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
    }

    @Test public void successfulActivationChangesOnlyPrivateWorkingRoster() throws Exception {
        File workingRoot = temporary.newFolder("private-working");
        File workingFile = RosterStore.selectFile(workingRoot);
        assertTrue(workingFile.getParentFile().mkdirs());
        byte[] before = "[Characters]\nA/A.def\n[Options]\nkeep=1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(workingFile.toPath(), before);
        File sourceFile = temporary.newFile("linked-source-select.def");
        byte[] source = "[Characters]\nSource/Source.def\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceFile.toPath(), source);
        CollectionStore.Record chosen = new CollectionStore.Record(UUID.randomUUID().toString(), "Empty", false);
        CollectionPlan plan = CollectionPlan.prepare(before, chosen, new LibraryScanner.Catalog(),
                new TagStore(temporary.newFolder("tags-success"), "source"));
        assertTrue(plan.missing.isEmpty());
        new SelectStorage(workingRoot).commitWorking(plan.workingSha, plan.replacement, "collection:activate:test", null);
        assertArrayEquals(source, Files.readAllBytes(sourceFile.toPath()));
        assertEquals("[Characters]\n[Options]\nkeep=1\n",
                new String(Files.readAllBytes(workingFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void sourceMotifSwitchAfterPreviewBlocksActivation() throws Exception {
        File source = temporary.newFolder("motif-source");
        File config = new File(source, "save/config.ini");
        File global = new File(source, "data/select.def");
        File system = new File(source, "data/system.def");
        File alternate = new File(source, "data/pack/select.def");
        File packSystem = new File(source, "data/pack/system.def");
        assertTrue(config.getParentFile().mkdirs());
        assertTrue(alternate.getParentFile().mkdirs());
        Files.write(config.toPath(), "[Config]\nMotif = data/system.def\n".getBytes(StandardCharsets.UTF_8));
        Files.write(system.toPath(), "[Files]\nselect = select.def\n".getBytes(StandardCharsets.UTF_8));
        Files.write(global.toPath(), "[Characters]\nA/A.def\n".getBytes(StandardCharsets.UTF_8));
        Files.write(packSystem.toPath(), "[Files]\nselect = select.def\n".getBytes(StandardCharsets.UTF_8));
        Files.write(alternate.toPath(), "[Characters]\nB/B.def\n".getBytes(StandardCharsets.UTF_8));
        CollectionPlan.requireActiveWorkingRoster(LibraryFiles.local(source));
        Files.write(config.toPath(), "[Config]\nMotif = data/pack/system.def\n".getBytes(StandardCharsets.UTF_8));
        try { CollectionPlan.requireActiveWorkingRoster(LibraryFiles.local(source)); fail("Alternate roster allowed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Active screenpack")); }
        Files.write(config.toPath(), "[Config]\nMotif = ../../outside/system.def\n".getBytes(StandardCharsets.UTF_8));
        try { CollectionPlan.requireActiveWorkingRoster(LibraryFiles.local(source)); fail("Unsafe motif allowed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("unverified")); }
        Files.write(config.toPath(), "[Config]\nMotif = data/missing/system.def\n".getBytes(StandardCharsets.UTF_8));
        try { CollectionPlan.requireActiveWorkingRoster(LibraryFiles.local(source)); fail("Missing motif allowed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("unverified")); }
    }
}
