package com.holdmyspot.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.PortableFiles;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stores one topic-replay invocation's version-5 manifest and its manifest-local temporary artifacts.
 *
 * @param <T> the immutable manifest model consumed by replay planning and finalization
 */
public final class ManifestStore<T extends ManifestStore.Manifest>
{
	public static final int BATCH_MANIFEST_VERSION = 5;
	/**
	 * The start of the name of every temporary file that a replay operation creates.
	 */
	private static final String TEMPORARY_FILE_PREFIX = "git-topic-replay-";
	private final Path temporaryRoot;
	private final JsonMapper json;
	private final Class<T> manifestType;

	/**
	 * Creates a store for one CAT-work temporary root and one immutable manifest model.
	 *
	 * @param temporaryRoot the CAT-work root that owns manifests and their temporary artifacts
	 * @param json the operation-scoped mapper used for manifest input and output
	 * @param manifestType the immutable manifest model to parse from stored JSON
	 */
	public ManifestStore(Path temporaryRoot, JsonMapper json, Class<T> manifestType)
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(manifestType, "manifestType").isNotNull();
		this.temporaryRoot = temporaryRoot.toAbsolutePath().normalize();
		this.json = json;
		this.manifestType = manifestType;
	}

	/**
	 * Reads one unfinished version-5 manifest below the CAT-work temporary root.
	 *
	 * @param manifestFile the stored manifest
	 * @return the parsed immutable manifest
	 * @throws IOException if the manifest lies outside CAT work or does not provide the required version-5 source facts
	 */
	public T readUnfinalized(Path manifestFile) throws IOException
	{
		requireTemporaryManifest(manifestFile);
		T manifest = json.readValue(manifestFile.toFile(), manifestType);
		if (manifest == null || manifest.version() != BATCH_MANIFEST_VERSION || !manifest.hasSources())
		{
			throw new IOException("Topic-replay batch manifest must be a version-5 manifest with source snapshots");
		}
		return manifest;
	}

	/**
	 * Creates one temporary file for a manifest-owned replay operation.
	 *
	 * @param manifestFile the manifest that owns the current operation
	 * @param suffix the filename suffix
	 * @return the new temporary file below the CAT-work temporary root
	 * @throws IOException if the manifest lies outside CAT work or the file cannot be created
	 */
	public Path createTemporaryFile(Path manifestFile, String suffix) throws IOException
	{
		requireTemporaryManifest(manifestFile);
		Files.createDirectories(temporaryRoot);
		return Files.createTempFile(temporaryRoot, TEMPORARY_FILE_PREFIX, suffix);
	}

	/**
	 * Creates one temporary directory for a manifest-owned replay operation.
	 *
	 * @param manifestFile the manifest that owns the current operation
	 * @param prefix the directory-name prefix
	 * @return the new temporary directory below the CAT-work temporary root
	 * @throws IOException if the manifest lies outside CAT work or the directory cannot be created
	 */
	Path createTemporaryDirectory(Path manifestFile, String prefix) throws IOException
	{
		requireTemporaryManifest(manifestFile);
		Files.createDirectories(temporaryRoot);
		return Files.createTempDirectory(temporaryRoot, prefix);
	}

	/**
	 * Atomically replaces one manifest with its current immutable model.
	 *
	 * @param manifestFile the target manifest
	 * @param manifest the current immutable manifest content
	 * @throws IOException if the target lies outside CAT work or cannot be replaced
	 */
	public void write(Path manifestFile, T manifest) throws IOException
	{
		requireTemporaryManifest(manifestFile);
		Path target = manifestFile.toAbsolutePath().normalize();
		Path parent = target.getParent();
		assert parent != null;
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		try
		{
			json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
			try
			{
				PortableFiles.replace(temporary, target);
			}
			catch (AtomicMoveNotSupportedException _)
			{
				Files.move(temporary, target, REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Verifies that a manifest and each artifact it owns stays below the invocation's CAT-work temporary root.
	 *
	 * @param manifestFile the manifest path
	 * @throws IOException if the manifest lies outside the CAT-work temporary root
	 */
	private void requireTemporaryManifest(Path manifestFile) throws IOException
	{
		Path manifest = manifestFile.toAbsolutePath().normalize();
		if (!manifest.startsWith(temporaryRoot))
		{
			throw new IOException("Topic-replay invocation artifacts must be below the CAT work temp directory " +
				temporaryRoot + ": " + manifestFile + ". Place the artifact beneath " + temporaryRoot + ".");
		}
	}

	/**
	 * Supplies the version and immutable-source presence facts needed to validate a stored replay manifest.
	 */
	public interface Manifest
	{
		/**
		 * Returns the stored format version.
		 *
		 * @return the stored format version
		 */
		int version();

		/**
		 * Indicates whether the manifest contains the immutable source snapshots required before review.
		 *
		 * @return {@code true} if immutable source snapshots are present
		 */
		boolean hasSources();
	}
}
