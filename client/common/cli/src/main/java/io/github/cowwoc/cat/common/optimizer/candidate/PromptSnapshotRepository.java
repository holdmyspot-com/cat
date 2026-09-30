package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Persists immutable prompt snapshots beneath one campaign directory. */
public final class PromptSnapshotRepository
{
	private final Path campaignDirectory;
	private final JsonMapper json;

	/**
	 * Creates a campaign-local snapshot repository.
	 *
	 * @param campaignDirectory campaign evidence directory
	 */
	public PromptSnapshotRepository(Path campaignDirectory)
	{
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory").toAbsolutePath().
			normalize();
		json = SnapshotJson.mapper();
	}

	/**
	 * Stores blobs before publishing a new immutable manifest or validating the existing manifest for this candidate.
	 *
	 * @param snapshot immutable snapshot
	 * @return persisted candidate identity
	 * @throws IOException if immutable publication fails
	 */
	public String save(PromptSnapshot snapshot) throws IOException
	{
		Objects.requireNonNull(snapshot, "snapshot");
		for (SnapshotEntry entry: snapshot.manifest().entries())
		{
			byte[] content = snapshot.blob(entry.contentSha256()).orElseThrow();
			writeOnce(contentPath(entry.contentSha256()), content);
		}

		Path manifestFile = manifestPath(snapshot.id());
		if (Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS))
		{
			try
			{
				load(snapshot.id());
			}
			catch (IOException failure)
			{
				throw new IOException("Immutable snapshot collision at " + manifestFile, failure);
			}
			return snapshot.id();
		}

		byte[] manifest = json.writeValueAsBytes(snapshot.manifest());
		writeOnce(manifestPath(snapshot.id()), manifest);
		return snapshot.id();
	}

	/**
	 * Restores and independently validates one persisted snapshot.
	 *
	 * @param candidateId requested identity
	 * @return restored immutable snapshot
	 * @throws IOException if an artifact is invalid
	 */
	public PromptSnapshot load(String candidateId) throws IOException
	{
		if (!PromptSnapshot.isSha256(candidateId))
			throw new IOException("Invalid snapshot candidate identity: " + candidateId);
		Path manifestFile = manifestPath(candidateId);
		if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Snapshot manifest is missing: " + candidateId);
		SnapshotManifest manifest;
		try
		{
			manifest = json.readValue(Files.readAllBytes(manifestFile), SnapshotManifest.class);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Snapshot manifest is malformed: " + candidateId, e);
		}
		if (!candidateId.equals(manifest.candidateId()))
			throw new IOException("Snapshot manifest filename and candidate identity differ: " + candidateId);
		Map<String, byte[]> blobs = new LinkedHashMap<>();
		for (SnapshotEntry entry: manifest.entries())
		{
			String hash = entry.contentSha256();
			if (blobs.containsKey(hash))
				continue;
			Path contentFile = contentPath(hash);
			if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Snapshot blob is missing: " + hash);
			byte[] content = Files.readAllBytes(contentFile);
			if (!PromptSnapshot.sha256(content).equals(hash))
				throw new IOException("Snapshot blob is corrupt: " + hash);
			blobs.put(hash, content);
		}
		return PromptSnapshot.restore(manifest, blobs);
	}

	/**
	 * Indicates whether the named manifest has been published.
	 *
	 * @param candidateId requested identity
	 * @return whether a manifest file exists
	 */
	public boolean contains(String candidateId)
	{
		return PromptSnapshot.isSha256(candidateId) &&
			Files.isRegularFile(manifestPath(candidateId), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Resolves one content blob path.
	 *
	 * @param hash content identity
	 * @return blob path
	 */
	private Path contentPath(String hash)
	{
		return campaignDirectory.resolve("snapshot-content").resolve(hash + ".blob");
	}

	/**
	 * Resolves one manifest path.
	 *
	 * @param candidateId candidate identity
	 * @return manifest path
	 */
	private Path manifestPath(String candidateId)
	{
		return campaignDirectory.resolve("snapshots").resolve(candidateId + ".json");
	}

	/**
	 * Atomically creates immutable content or verifies byte-equal prior publication.
	 *
	 * @param target immutable destination
	 * @param content exact content
	 * @throws IOException if publication is unsafe or collides
	 */
	static void writeOnce(Path target, byte[] content) throws IOException
	{
		Files.createDirectories(target.getParent());
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Immutable snapshot target is not a regular file: " + target);
			if (!Arrays.equals(Files.readAllBytes(target), content))
				throw new IOException("Immutable snapshot collision at " + target);
			return;
		}
		Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
			{
				ByteBuffer bytes = ByteBuffer.wrap(content);
				while (bytes.hasRemaining())
					channel.write(bytes);
				channel.force(true);
			}
			try
			{
				Files.createLink(target, temporary);
				try (FileChannel parent = FileChannel.open(target.getParent(), StandardOpenOption.READ))
				{
					parent.force(true);
				}
			}
			catch (FileAlreadyExistsException e)
			{
				if (!Arrays.equals(Files.readAllBytes(target), content))
					throw new IOException("Immutable snapshot collision at " + target, e);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}
}
