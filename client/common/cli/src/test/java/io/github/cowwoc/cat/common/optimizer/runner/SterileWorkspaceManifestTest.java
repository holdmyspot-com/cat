package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests exact immutable sterile workspace manifests.
 */
public final class SterileWorkspaceManifestTest
{
    /**
     * Creates a test instance.
     */
    public SterileWorkspaceManifestTest()
    {
    }

    /**
     * Verifies canonical schema-one encoding preserves every manifest field.
     *
     * @throws IOException if the encoded manifest cannot be read
     */
    @Test
    public void roundTripsCanonicalManifest() throws IOException
    {
        SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
            SterileWorkspaceKind.CONTRACT_DERIVATION, ref("requests", "a"), List.of("b".repeat(64)),
            List.of(entry("guidance.md", "c")));
        SterileWorkspaceManifest decoded = SterileWorkspaceManifest.fromBytes(manifest.bytes());
        requireThat(decoded, "manifest").isEqualTo(manifest);
    }

    /**
     * Verifies changes to caller-owned lists cannot change a retained manifest.
     */
    @Test
    public void copiesCallerLists()
    {
        List<String> turns = new ArrayList<>();
        turns.add("b".repeat(64));
        List<SterileWorkspaceEntry> entries = new ArrayList<>();
        entries.add(entry("guidance.md", "c"));
        SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
            SterileWorkspaceKind.CONTRACT_DERIVATION, ref("requests", "a"), turns, entries);
        turns.clear();
        entries.clear();
        requireThat(manifest.turnSha256().size(), "turnCount").isEqualTo(1);
        requireThat(manifest.entries().size(), "entryCount").isEqualTo(1);
    }

    /**
     * Verifies sterile entries follow unsigned UTF-8 path ordering.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsUnsortedEntries()
    {
        new SterileWorkspaceManifest(1, SterileWorkspaceKind.CONTRACT_DERIVATION, ref("requests", "a"),
            List.of("b".repeat(64)), List.of(entry("z.md", "c"), entry("a.md", "d")));
    }

    /**
     * Verifies a manifest cannot claim file bytes different from its content address.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsContentHashMismatch()
    {
        SterileWorkspaceEntry wrong = new SterileWorkspaceEntry(Path.of("guidance.md"),
            SterileWorkspaceInputRole.AUTHORITY, ref("inputs", "c"), "d".repeat(64));
        new SterileWorkspaceManifest(1, SterileWorkspaceKind.CONTRACT_DERIVATION, ref("requests", "a"),
            List.of("b".repeat(64)), List.of(wrong));
    }

    /**
     * Verifies a declared input cannot escape the workspace through a parent path segment.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsParentTraversal()
    {
        entry("../outside.md", "c");
    }

    /**
     * Verifies a declared file cannot name the workspace directory itself.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsWorkspaceRootAsFile()
    {
        entry(".", "c");
    }

    /**
     * Verifies a normal nested file remains an accepted workspace input.
     */
    @Test
    public void acceptsNestedInput()
    {
        requireThat(entry("authority/rubric.md", "c").relativePath(), "relativePath").
            isEqualTo(Path.of("authority/rubric.md"));
    }

    /**
     * Creates a sterile input entry.
     *
     * @param path the relative path
     * @param digit the repeated hash digit
     * @return the sterile entry
     */
    private static SterileWorkspaceEntry entry(String path, String digit)
    {
        return new SterileWorkspaceEntry(Path.of(path), SterileWorkspaceInputRole.AUTHORITY,
            ref("inputs", digit), digit.repeat(64));
    }

    /**
     * Creates an immutable evidence reference.
     *
     * @param collection the collection name
     * @param digit the repeated hash digit
     * @return the evidence reference
     */
    private static EvidenceRef ref(String collection, String digit)
    {
        return new EvidenceRef(collection, digit.repeat(64));
    }
}
