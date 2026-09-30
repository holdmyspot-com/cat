package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * Places one immutable sterile input set in a new workspace for a native call.
 */
public final class SterileWorkspaceMaterializer
{
	/**
	 * Prevents construction of a stateless materializer.
	 */
	private SterileWorkspaceMaterializer()
	{
	}

	/**
	 * Creates a new workspace containing the verified files declared by the manifest.
	 *
	 * @param context the kind and content address of the retained manifest
	 * @param evidenceReader the reader for immutable manifest and input bytes
	 * @param destination the new workspace path, which must not already exist
	 * @return the manifest used to create the workspace
	 * @throws IOException if evidence is missing or changed, the destination exists, or creation fails
	 */
	public static SterileWorkspaceManifest materialize(SterileWorkspaceContext context,
		EvidenceReader evidenceReader, Path destination) throws IOException
	{
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(evidenceReader, "evidenceReader");
		Objects.requireNonNull(destination, "destination");
		Path workspace = destination.toAbsolutePath().normalize();
		Path parent = workspace.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Sterile workspace parent is not a real directory: " + parent);
		if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Sterile workspace destination already exists: " + workspace);
		SterileWorkspaceManifest manifest = SterileWorkspaceManifest.fromBytes(
			VerifiedEvidence.read(evidenceReader, context.manifestRef()));
		if (manifest.kind() != context.kind())
			throw new IOException("Sterile workspace manifest kind differs from the request");

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pending = cleanup.add(Files.createTempDirectory(parent, ".sterile-workspace-"));
			for (SterileWorkspaceEntry entry : manifest.entries())
			{
				Path file = pending.resolve(entry.relativePath()).normalize();
				if (!file.startsWith(pending))
					throw new IOException("Sterile workspace entry escapes the workspace");
				Files.createDirectories(file.getParent());
				Files.write(file, VerifiedEvidence.read(evidenceReader, entry.contentEvidence()),
					StandardOpenOption.CREATE_NEW);
			}
			Files.move(pending, workspace);
		}
		return manifest;
	}

	/**
	 * Verifies that a copied native workspace still contains the exact declared input files.
	 *
	 * @param context the retained manifest identity for the call
	 * @param evidenceReader the reader for immutable manifest and input bytes
	 * @param workspace the native runner's copied project directory
	 * @throws IOException if a declared file is missing, changed, or cannot be verified
	 */
	public static void verify(SterileWorkspaceContext context, EvidenceReader evidenceReader, Path workspace)
		throws IOException
	{
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(evidenceReader, "evidenceReader");
		Objects.requireNonNull(workspace, "workspace");
		Path root = workspace.toAbsolutePath().normalize();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Sterile workspace is not a real directory: " + root);
		SterileWorkspaceManifest manifest = SterileWorkspaceManifest.fromBytes(
			VerifiedEvidence.read(evidenceReader, context.manifestRef()));
		if (manifest.kind() != context.kind())
			throw new IOException("Sterile workspace manifest kind differs from the request");
		for (SterileWorkspaceEntry entry : manifest.entries())
		{
			Path file = root.resolve(entry.relativePath()).normalize();
			if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Sterile workspace input is missing or is not a regular file: " +
					entry.relativePath());
			byte[] expected = VerifiedEvidence.read(evidenceReader, entry.contentEvidence());
			try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
			{
				if (!Arrays.equals(input.readAllBytes(), expected))
					throw new IOException("Sterile workspace input hash differs from retained evidence: " +
						entry.relativePath());
			}
		}
	}
}
