package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class AddonInstallTransactionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private AddonPackage stage(String name) throws Exception {
        File addon = temporary.newFolder(name);
        Files.write(new File(addon, name + ".def").toPath(),
                ("[Info]\nname=" + name + "\n[Files]\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(addon, "data.bin").toPath(), new byte[]{1, 2, 3});
        return AddonPackage.fromFolder(LibraryFiles.local(addon), temporary.newFolder("private-" + name), "chars");
    }

    @Test public void successfulAddOnlyInstallVerifiesAndKeepsExistingContent() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("success-source"));
        FakeProvider provider = new FakeProvider();
        provider.add(provider.chars, "Existing", true);
        AddonInstallTransaction.Review review = AddonInstallTransaction.review(addon, provider, source);
        AddonInstallTransaction.install(addon, provider, source, review);
        assertNotNull(provider.child(provider.chars, "Existing"));
        assertNotNull(provider.child(provider.chars, "Hero"));
        assertNull(provider.child(provider.chars, ".ikemen-pending-"));
        assertFalse(addon.stageRoot.exists());
    }

    @Test public void collisionAndUnsupportedCapabilitiesRejectBeforeSourceWrite() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("collision-source"));
        FakeProvider provider = new FakeProvider();
        provider.add(provider.chars, "hero", true);
        try { AddonInstallTransaction.review(addon, provider, source); fail("Casefold collision accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        assertEquals(1, provider.chars.children.size());
        provider.chars.children.clear();
        provider.add(provider.chars, "He\u0301ro", true);
        AddonPackage accented = stage("H\u00e9ro");
        try { AddonInstallTransaction.review(accented, provider, source); fail("NFC collision accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        provider.chars.children.clear();
        provider.capable = false;
        try { AddonInstallTransaction.review(addon, provider, source); fail("Unsupported provider accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("capabilities")); }
        assertTrue(provider.chars.children.isEmpty());
    }

    @Test public void coreReviewRejectsInvalidDefBeforeProviderMutation() throws Exception {
        File addonFolder = temporary.newFolder("InvalidHero");
        Files.write(new File(addonFolder, "InvalidHero.def").toPath(),
                "[Unknown]\nvalue=1\n".getBytes(StandardCharsets.UTF_8));
        AddonPackage addon = AddonPackage.fromFolder(LibraryFiles.local(addonFolder),
                temporary.newFolder("invalid-private"), "chars");
        FakeProvider provider = new FakeProvider();
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("invalid-source"));
        AddonInstallTransaction.Review review = AddonInstallTransaction.review(addon, provider, source);
        assertTrue(review.blocked);
        try { AddonInstallTransaction.install(addon, provider, source, review); fail("Invalid DEF installed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("health validation")); }
        assertTrue(provider.chars.children.isEmpty());
    }

    @Test public void interruptedWriteRemovesOnlyJournalOwnedPendingFolder() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("interrupted-source"));
        FakeProvider provider = new FakeProvider();
        provider.failWrite = true;
        AddonInstallTransaction.Review review = AddonInstallTransaction.review(addon, provider, source);
        try { AddonInstallTransaction.install(addon, provider, source, review); fail("Write failure accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        assertEquals(1, provider.chars.children.size());
        assertTrue(new File(addon.stageRoot, "install.properties").isFile());
        provider.failWrite = false;
        AddonInstallTransaction.recover(addon.stageRoot, provider);
        assertTrue(provider.chars.children.isEmpty());
        assertFalse(addon.stageRoot.exists());
    }

    @Test public void providerAutoRenameIsJournaledAndRecoveryLeavesUnexpectedName() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("rename-source"));
        FakeProvider provider = new FakeProvider();
        provider.autoRename = true;
        AddonInstallTransaction.Review review = AddonInstallTransaction.review(addon, provider, source);
        try { AddonInstallTransaction.install(addon, provider, source, review); fail("Auto rename accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        assertEquals(1, provider.chars.children.size());
        assertTrue(provider.chars.children.get(0).name.endsWith(" (1)"));
        try { AddonInstallTransaction.recover(addon.stageRoot, provider); fail("Unexpected folder name deleted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("name or type changed")); }
        assertEquals(1, provider.chars.children.size());
        assertTrue(new File(addon.stageRoot, "install.properties").isFile());
    }

    @Test public void uncertainPendingCreationRetainsJournalAndSource() throws Exception {
        AddonPackage addon = stage("UncertainHero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("uncertain-source"));
        FakeProvider provider = new FakeProvider();
        provider.throwAfterCreate = true;
        AddonInstallTransaction.Review review = AddonInstallTransaction.review(addon, provider, source);
        try { AddonInstallTransaction.install(addon, provider, source, review); fail("Creation failure accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        assertEquals(1, provider.chars.children.size());
        try { AddonInstallTransaction.recover(addon.stageRoot, provider); fail("Unknown creation deleted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("uncertain")); }
        assertEquals(1, provider.chars.children.size());
        assertTrue(new File(addon.stageRoot, "install.properties").isFile());
    }

    @Test public void sourceSuppliedJournalCannotDeleteExistingDestination() throws Exception {
        File folder = temporary.newFolder("JournalAttack");
        Files.write(new File(folder, "JournalAttack.def").toPath(),
                "[Info]\nname=JournalAttack\n[Files]\n".getBytes(StandardCharsets.UTF_8));
        FakeProvider provider = new FakeProvider();
        FakeProvider.Document existing = provider.add(provider.chars, ".ikemen-pending-00000000-0000-0000-0000-000000000000", true);
        String forged = "version=1\nsource=" + provider.identity() + "\nparent=chars\nkind=chars\n"
                + "pendingName=" + existing.name + "\npendingId=" + existing.id + "\nphase=writing\ncreated=0\n";
        Files.write(new File(folder, "install.properties").toPath(), forged.getBytes(StandardCharsets.UTF_8));
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(folder),
                temporary.newFolder("attack-private"), "chars");
        assertNull(AddonInstallTransaction.pendingDescription(staged.stageRoot));
        try { AddonInstallTransaction.recover(staged.stageRoot, provider); fail("Source journal accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("journal missing")); }
        assertSame(existing, provider.child(provider.chars, existing.name));
    }

    @Test public void foreignChildInPendingFolderStopsRecoveryWithoutDeletion() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("foreign-source"));
        FakeProvider provider = new FakeProvider();
        provider.failWrite = true;
        try { AddonInstallTransaction.install(addon, provider, source,
                AddonInstallTransaction.review(addon, provider, source)); fail("Write failure accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        FakeProvider.Document pending = provider.chars.children.get(0);
        provider.add(pending, "someone-else.txt", false);
        try { AddonInstallTransaction.recover(addon.stageRoot, provider); fail("Foreign content deleted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("unknown")); }
        assertEquals(1, provider.chars.children.size());
        assertTrue(new File(addon.stageRoot, "install.properties").isFile());
    }

    @Test public void movedJournalOwnedChildKeepsRecoveryJournal() throws Exception {
        AddonPackage addon = stage("Hero");
        LibraryFiles.Node source = LibraryFiles.local(temporary.newFolder("moved-source"));
        FakeProvider provider = new FakeProvider();
        provider.failWrite = true;
        try { AddonInstallTransaction.install(addon, provider, source,
                AddonInstallTransaction.review(addon, provider, source)); fail("Write failure accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        FakeProvider.Document pending = provider.chars.children.get(0);
        assertFalse(pending.children.isEmpty());
        FakeProvider.Document moved = pending.children.remove(0);
        provider.chars.children.add(moved);
        try { AddonInstallTransaction.recover(addon.stageRoot, provider); fail("Moved child ignored"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("no longer under")); }
        assertTrue(provider.chars.children.contains(pending));
        assertTrue(provider.chars.children.contains(moved));
        assertTrue(new File(addon.stageRoot, "install.properties").isFile());
    }

    private static final class FakeProvider implements AddonInstallTransaction.Destination {
        private static final class Document {
            final String id;
            String name;
            final boolean directory;
            final List<Document> children = new ArrayList<>();
            byte[] bytes = new byte[0];
            Document(String id, String name, boolean directory) {
                this.id = id; this.name = name; this.directory = directory;
            }
        }
        final Map<String, Document> nodes = new HashMap<>();
        final Document chars = new Document("chars", "chars", true);
        final Document stages = new Document("stages", "stages", true);
        int next;
        boolean capable = true, failWrite, autoRename, throwAfterCreate;
        FakeProvider() { nodes.put(chars.id, chars); nodes.put(stages.id, stages); }
        Document add(Document parent, String name, boolean directory) {
            Document document = new Document("id-" + (++next), name, directory);
            parent.children.add(document); nodes.put(document.id, document); return document;
        }
        Document child(Document parent, String name) {
            for (Document node : parent.children) if (node.name.equals(name)) return node;
            return null;
        }
        Node node(Document document) { return new Node(document.id, document.name, document.directory); }
        @Override public String identity() { return "content://synthetic-source"; }
        @Override public Node target(String kind) { return node(kind.equals("chars") ? chars : stages); }
        @Override public List<Node> children(Node folder) {
            List<Node> list = new ArrayList<>();
            for (Document child : nodes.get(folder.id).children) list.add(node(child));
            return list;
        }
        @Override public void requireCapabilities(Node folder) throws IOException {
            if (!capable) throw new IOException("Provider lacks add/rename/delete capabilities");
        }
        @Override public Node create(Node parent, String name, boolean directory) throws IOException {
            Document document = add(nodes.get(parent.id), autoRename ? name + " (1)" : name, directory);
            if (throwAfterCreate) throw new IOException("Injected response failure after create");
            return node(document);
        }
        @Override public void write(Node file, InputStream input, long bytes) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            nodes.get(file.id).bytes = output.toByteArray();
            if (failWrite) throw new IOException("Injected provider write failure");
        }
        @Override public InputStream read(Node file) { return new ByteArrayInputStream(nodes.get(file.id).bytes); }
        @Override public Node rename(Node folder, String name) {
            Document document = nodes.get(folder.id); document.name = name; return node(document);
        }
        @Override public void delete(Node node) {
            for (Document parent : nodes.values()) if (parent.children.remove(nodes.get(node.id))) break;
            nodes.remove(node.id);
        }
    }
}
