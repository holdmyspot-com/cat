package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/**
 * Declares the complete immutable input set for a sterile call.
  *
 * @param schemaVersion the recorded schemaVersion
 * @param kind the recorded kind
 * @param semanticRequestRef the recorded semanticRequestRef
 * @param turnSha256 the recorded turnSha256
 * @param entries the recorded entries
 */
public record SterileWorkspaceManifest(int schemaVersion, SterileWorkspaceKind kind, EvidenceRef semanticRequestRef,
    List<String> turnSha256, List<SterileWorkspaceEntry> entries)
{
    /**
     * Validates manifest version, content identities and deterministic path order.
     */
    public SterileWorkspaceManifest
    {
        if (schemaVersion != 1)
            throw new IllegalArgumentException("schemaVersion must be 1");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
        turnSha256 = List.copyOf(turnSha256);
        entries = List.copyOf(entries);
        if (turnSha256.isEmpty() || turnSha256.stream().anyMatch(hash -> !hash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("turnSha256 must contain valid hashes");
        var seen = new HashSet<Path>();
        byte[] previous = null;
        for (SterileWorkspaceEntry entry : entries)
        {
            if (!entry.sha256().equals(entry.contentEvidence().evidenceId()))
                throw new IllegalArgumentException("sterile entry hash differs from its content address");
            byte[] current = entry.relativePath().toString().getBytes(StandardCharsets.UTF_8);
            if (!seen.add(entry.relativePath()) || previous != null &&
                Arrays.compareUnsigned(previous, current) >= 0)
                throw new IllegalArgumentException("entries must have unique unsigned-bytewise sorted paths");
            previous = current;
        }
    }

    /**
     * Encodes the exact schema-one manifest bytes for immutable evidence.
     *
     * @return the canonical bytes
     */
    public byte[] bytes()
    {
        try
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(schemaVersion);
            NativeDispatchCompletion.write(output, kind.name());
            writeRef(output, semanticRequestRef);
            output.writeInt(turnSha256.size());
            for (String hash : turnSha256)
                NativeDispatchCompletion.write(output, hash);
            output.writeInt(entries.size());
            for (SterileWorkspaceEntry entry : entries)
            {
                NativeDispatchCompletion.write(output, entry.relativePath().toString());
                NativeDispatchCompletion.write(output, entry.role().name());
                writeRef(output, entry.contentEvidence());
                NativeDispatchCompletion.write(output, entry.sha256());
            }
            output.flush();
            return buffer.toByteArray();
        }
        catch (IOException exception)
        {
            throw new AssertionError(exception);
        }
    }

    /**
     * Decodes only canonical schema-one bytes.
     *
     * @param bytes the retained immutable manifest bytes
     * @return the validated manifest
     * @throws IOException if the manifest bytes are incomplete, malformed or noncanonical
     */
    public static SterileWorkspaceManifest fromBytes(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        try
        {
            int version = input.readInt();
            SterileWorkspaceKind kind = SterileWorkspaceKind.valueOf(NativeDispatchCompletion.read(input));
            EvidenceRef semanticRequest = readRef(input);
            int turnCount = count(input);
            List<String> turns = new java.util.ArrayList<>(turnCount);
            for (int index = 0; index < turnCount; index = index + 1)
                turns.add(NativeDispatchCompletion.read(input));
            int entryCount = count(input);
            List<SterileWorkspaceEntry> inputs = new java.util.ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index = index + 1)
            {
                Path relativePath = Path.of(NativeDispatchCompletion.read(input));
                SterileWorkspaceInputRole role = SterileWorkspaceInputRole.valueOf(
                    NativeDispatchCompletion.read(input));
                EvidenceRef content = readRef(input);
                String sha256 = NativeDispatchCompletion.read(input);
                inputs.add(new SterileWorkspaceEntry(relativePath, role, content, sha256));
            }
            if (input.available() != 0)
                throw new IOException("trailing sterile manifest bytes");
            SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(version, kind, semanticRequest,
                turns, inputs);
            if (!Arrays.equals(bytes, manifest.bytes()))
                throw new IOException("noncanonical sterile manifest bytes");
            return manifest;
        }
        catch (RuntimeException exception)
        {
            throw new IOException("invalid sterile manifest", exception);
        }
    }

    /**
     * Encodes a content address.
     *
     * @param output the binary output
     * @param reference the content address
     * @throws IOException if output fails
     */
    private static void writeRef(DataOutputStream output, EvidenceRef reference) throws IOException
    {
        NativeDispatchCompletion.write(output, reference.collection());
        NativeDispatchCompletion.write(output, reference.evidenceId());
    }

    /**
     * Decodes a content address.
     *
     * @param input the binary input
     * @return the content address
     * @throws IOException if the address is incomplete
     */
    private static EvidenceRef readRef(DataInputStream input) throws IOException
    {
        return new EvidenceRef(NativeDispatchCompletion.read(input), NativeDispatchCompletion.read(input));
    }

    /**
     * Reads a bounded list length.
     *
     * @param input the binary input
     * @return the list length
     * @throws IOException if the count is malformed
     */
    private static int count(DataInputStream input) throws IOException
    {
        int result = input.readInt();
        if (result < 0 || result > 100_000)
            throw new IOException("invalid sterile manifest list length");
        return result;
    }
}
