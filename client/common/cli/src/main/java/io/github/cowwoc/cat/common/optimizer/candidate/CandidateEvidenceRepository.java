package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** C3 immutable JSON evidence access bound to the C1-pinned campaign store. */
public final class CandidateEvidenceRepository
{
	private final CampaignStore store;
	private final EvidenceReader reader;
	private final JsonMapper json;

	/**
	 * Opens evidence only after the supplied binding names the same pinned campaign.
	 *
	 * @param campaignDirectory C1 campaign directory
	 * @param expectedBinding exact pinned campaign binding
	 * @throws IOException if this is a foreign or invalid campaign
	 */
	public CandidateEvidenceRepository(Path campaignDirectory, CampaignBinding expectedBinding) throws IOException
	{
		this(CampaignStore.open(Objects.requireNonNull(campaignDirectory, "campaignDirectory")), expectedBinding);
	}

	/**
	 * Uses an existing pinned campaign store for binding-checked evidence access.
	 *
	 * @param store pinned campaign store
	 * @param expectedBinding exact campaign binding verified before each evidence read
	 * @throws IOException if the binding differs from the store's campaign
	 */
	public CandidateEvidenceRepository(CampaignStore store, CampaignBinding expectedBinding) throws IOException
	{
		this.store = Objects.requireNonNull(store, "store");
		reader = store.evidenceReader(Objects.requireNonNull(expectedBinding, "expectedBinding"));
		json = SnapshotJson.mapper();
	}

	/**
	 * Publishes and rehashes a typed immutable C3 record.
	 *
	 * @param collection exact evidence collection
	 * @param value validated record
	 * @return content-addressed evidence reference
	 * @throws IOException if publication or verification fails
	 */
	public EvidenceRef publish(String collection, Object value) throws IOException
	{
		Objects.requireNonNull(value, "value");
		byte[] bytes = json.writeValueAsBytes(value);
		EvidenceRef reference = store.appendImmutableJson(collection, bytes);
		reader.read(reference);
		return reference;
	}

	/**
	 * Reads one exact collection through C1's binding-checked evidence reader.
	 *
	 * @param reference content-addressed record
	 * @param collection expected collection
	 * @param type expected record type
	 * @param <T> record type
	 * @return parsed validated record
	 * @throws IOException if the record is foreign, corrupt, or malformed
	 */
	public <T> T read(EvidenceRef reference, String collection, Class<T> type) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		Objects.requireNonNull(type, "type");
		if (!reference.collection().equals(collection))
			throw new IOException("Candidate evidence collection differs: " + reference.collection());
		byte[] bytes = reader.read(reference);
		try
		{
			return json.readValue(bytes, type);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Candidate evidence is malformed: " + reference, e);
		}
	}
}
