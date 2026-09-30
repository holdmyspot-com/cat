package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opens the indexed preparation cycle that owns contract inspection attempts.
 */
public final class PreparationCycleService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds cycle publication to a registered campaign.
	 *
	 * @param locator the campaign's durable locator
	 * @param store the pinned evidence store
	 */
	public PreparationCycleService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or retrieves the initial preparation cycle and its source capture.
	 *
	 * @param binding the registered campaign
	 * @return the indexed cycle reference
	 * @throws IOException if source capture or current preparation authority is invalid
	 */
	public EvidenceRef prepare(CampaignBinding binding) throws IOException
	{
		requireRegisteredStore(binding);
		PreparationAuthority authority = new CampaignTransitions(store).readPreparationAuthority(binding);
		if (authority.kind() != PreparationAuthority.Kind.INITIAL)
			throw new IOException("Initial preparation cycle requires an unconfirmed campaign");
		String key = PreparationIndexKey.derive(authority);
		Optional<EvidenceRef> existing = store.readPreconfirmationIndex(binding,
			PreconfirmationIndexKind.CONTRACT_PREPARATION, key);
		if (existing.isPresent())
		{
			ContractPreparationCycle retained = load(binding, existing.orElseThrow());
			if (!retained.authority().equals(authority))
				throw new IOException("Indexed preparation cycle conflicts with current authority");
			return existing.orElseThrow();
		}

		InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
		EvidenceRef sourceRef = sources.capture(binding);
		InitialTargetCapture source = sources.load(binding, sourceRef);
		if (!source.preparation().equals(authority))
			throw new IOException("Preparation source capture belongs to another authority");
		ContractPreparationCycle cycle = new ContractPreparationCycle(1, binding.campaignId(), authority, sourceRef);
		EvidenceRef reference = store.appendImmutableJson("contract-preparation-cycles",
			JSON.writeValueAsBytes(cycle));
		store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_PREPARATION, key, reference);
		return reference;
	}

	/**
	 * Loads a retained cycle through its campaign index and source capture.
	 *
	 * @param binding the registered campaign
	 * @param reference the retained cycle
	 * @return the validated preparation cycle
	 * @throws IOException if the cycle or its source capture is invalid
	 */
	public ContractPreparationCycle load(CampaignBinding binding, EvidenceRef reference) throws IOException
	{
		requireRegisteredStore(binding);
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals("contract-preparation-cycles"))
			throw new IOException("Preparation cycle has the wrong evidence collection");
		try
		{
			byte[] bytes = store.readImmutableJson(reference);
			ContractPreparationCycle cycle = JSON.readValue(bytes, ContractPreparationCycle.class);
			if (cycle == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(cycle)) ||
				!cycle.campaignId().equals(binding.campaignId()))
				throw new IOException("Preparation cycle is foreign or noncanonical");
			Optional<EvidenceRef> indexed = store.readPreconfirmationIndex(binding,
				PreconfirmationIndexKind.CONTRACT_PREPARATION, PreparationIndexKey.derive(cycle.authority()));
			if (!indexed.equals(Optional.of(reference)))
				throw new IOException("Preparation cycle lacks its exact campaign index");
			InitialTargetCapture source = new InitialTargetCaptureService(locator, store).load(binding,
				cycle.sourceCaptureRef());
			if (!source.preparation().equals(cycle.authority()))
				throw new IOException("Preparation cycle source belongs to another authority");
			return cycle;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Preparation cycle is malformed", exception);
		}
	}

	/**
	 * Requires the locator and repository to identify the same campaign.
	 *
	 * @param binding the requested campaign
	 * @throws IOException if the campaign registration is missing or foreign
	 */
	private void requireRegisteredStore(CampaignBinding binding) throws IOException
	{
		CampaignStore registered = locator.requireRegisteredBinding(binding);
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Preparation cycle store is not the registered campaign");
		store.lockedSnapshot(binding);
	}
}
