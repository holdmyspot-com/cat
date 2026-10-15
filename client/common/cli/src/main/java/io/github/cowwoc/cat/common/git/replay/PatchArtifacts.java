package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.PortablePaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves and identifies manifest-local immutable patch artifacts.
 */
public final class PatchArtifacts
{
	/**
	 * Resolves a reviewer-declared patch path without permitting the manifest to escape its own directory.
	 *
	 * @param manifestFile the manifest containing the patch declaration
	 * @param patchPath the manifest-relative patch path
	 * @return the resolved regular patch file
	 * @throws IOException if the patch declaration is unsafe or cannot be used
	 */
	public Path resolve(Path manifestFile, String patchPath) throws IOException
	{
		if (patchPath == null || patchPath.isBlank())
			throw new IOException("Topic-replay output batch patch must name a manifest-relative regular file");
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path patchFile = directory.resolve(patchPath).normalize();
		if (!patchFile.startsWith(directory) || !Files.isRegularFile(patchFile))
		{
			throw new IOException("Topic-replay output batch patch must be a regular file below the manifest directory: " +
				patchPath);
		}
		return patchFile;
	}

	/**
	 * Resolves one declared patch artifact without permitting the manifest to escape its own directory.
	 *
	 * @param manifestFile the manifest containing the patch declaration
	 * @param patch the declared patch artifact
	 * @return the resolved regular patch file
	 * @throws IOException if the patch declaration is unsafe or cannot be used
	 */
	public Path resolve(Path manifestFile, PatchArtifact patch) throws IOException
	{
		if (patch == null || patch.path() == null || patch.path().isBlank() || patch.sha256() == null ||
			!patch.sha256().matches("[0-9a-f]{64}"))
		{
			throw new IOException("Topic-replay batch requires a manifest-relative patch path and lowercase SHA-256");
		}
		return resolve(manifestFile, patch.path());
	}

	/**
	 * Creates a manifest-relative immutable patch declaration.
	 *
	 * @param manifestFile the manifest that owns the patch
	 * @param patchFile the existing patch file
	 * @return the patch declaration
	 * @throws IOException if the patch is outside the manifest directory or cannot be hashed
	 */
	public PatchArtifact declare(Path manifestFile, Path patchFile) throws IOException
	{
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path resolvedPatch = patchFile.toAbsolutePath().normalize();
		if (!resolvedPatch.startsWith(directory))
			throw new IOException("Topic-replay patch file must be below the manifest directory: " + patchFile);
		String relativePatch = PortablePaths.text(directory.relativize(resolvedPatch));
		return new PatchArtifact(relativePatch, sha256(resolvedPatch));
	}

	/**
	 * Computes a patch artifact's SHA-256 hash.
	 *
	 * @param patchFile the patch artifact
	 * @return its lowercase hexadecimal SHA-256 hash
	 * @throws IOException if the patch cannot be read or SHA-256 is unavailable
	 */
	String sha256(Path patchFile) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = new DigestInputStream(Files.newInputStream(patchFile), digest))
			{
				byte[] buffer = new byte[8192];
				int read = input.read(buffer);
				while (read != -1)
					read = input.read(buffer);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("The JDK does not provide SHA-256", e);
		}
	}
}
