package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Persists and rehashes confirmed known-source baselines under one campaign directory. */
public final class ConfirmedSourceBaselineRepository
{
	private final Path campaignDirectory;
	private final JsonMapper json;

	/**
	 * Creates a campaign-local repository.
	 *
	 * @param campaignDirectory pinned campaign evidence directory
	 */
	public ConfirmedSourceBaselineRepository(Path campaignDirectory)
	{
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory").toAbsolutePath().
			normalize();
		json = SnapshotJson.mapper();
	}

	/**
	 * Publishes an immutable content-addressed baseline.
	 *
	 * @param baseline validated baseline
	 * @return baseline identity
	 * @throws IOException if publication fails
	 */
	public String save(ConfirmedSourceBaseline baseline) throws IOException
	{
		byte[] bytes = json.writeValueAsBytes(Objects.requireNonNull(baseline, "baseline"));
		String id = PromptSnapshot.sha256(bytes);
		PromptSnapshotRepository.writeOnce(path(id), bytes);
		return id;
	}

	/**
	 * Loads and rehashes an immutable baseline.
	 *
	 * @param id baseline identity
	 * @return validated baseline
	 * @throws IOException if the record is missing, corrupt, or malformed
	 */
	public ConfirmedSourceBaseline load(String id) throws IOException
	{
		if (!PromptSnapshot.isSha256(id))
			throw new IOException("Invalid confirmed source baseline identity: " + id);
		Path file = path(id);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Confirmed source baseline is missing: " + id);
		byte[] bytes = Files.readAllBytes(file);
		if (!PromptSnapshot.sha256(bytes).equals(id))
			throw new IOException("Confirmed source baseline hash differs: " + id);
		try
		{
			return json.readValue(bytes, ConfirmedSourceBaseline.class);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Confirmed source baseline is malformed: " + id, e);
		}
	}

	/**
	 * Resolves one content-addressed record.
	 *
	 * @param id baseline identity
	 * @return durable path
	 */
	private Path path(String id)
	{
		return campaignDirectory.resolve("confirmed-source-baselines").resolve(id + ".json");
	}
}
