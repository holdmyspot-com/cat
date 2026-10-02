package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Durable descriptive optimization evidence, separate from compliance counts and allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param authors the retained author diagnoses
 * @param removed the hunks removed from promoted attempts
 * @param promptSizes measured prompt sizes by snapshot
 * @param trialInputs completed trial input observations by measurement identity, snapshot and cell
 * @param attempts retained original promotion results in launch order
 * @param tokenUsage reported usage of every finished nested session, by role
 */
public record OptimizationEvidence(List<AuthorDiagnosis> authors, List<String> removed,
	Map<Sha256, PromptSize> promptSizes,
	Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> trialInputs,
	List<AttemptResult> attempts, Map<SessionRole, List<TokenUsage>> tokenUsage)
{
	/**
	 * No descriptive evidence has been recorded.
	 */
	public static final OptimizationEvidence EMPTY = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of());

	/**
	 * Creates evidence.
	 *
	 * @param authors the retained author diagnoses
	 * @param removed the removed hunks
	 * @param promptSizes measured prompt sizes by snapshot
	 * @param trialInputs completed trial input observations by measurement identity, snapshot and cell
	 * @param attempts retained original promotion results in launch order
	 * @param tokenUsage reported usage of every finished nested session, by role
	 * @throws NullPointerException if an argument or element is null
	 */
	public OptimizationEvidence
	{
		authors = List.copyOf(authors);
		removed = List.copyOf(removed);
		promptSizes = Map.copyOf(promptSizes);
		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> copied = new HashMap<>();
		for (Map.Entry<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> identity : trialInputs.entrySet())
		{
			Map<Sha256, Map<String, TrialInputs>> snapshots = new HashMap<>();
			for (Map.Entry<Sha256, Map<String, TrialInputs>> entry : identity.getValue().entrySet())
				snapshots.put(entry.getKey(), Map.copyOf(entry.getValue()));
			copied.put(identity.getKey(), Map.copyOf(snapshots));
		}
		trialInputs = Map.copyOf(copied);
		attempts = List.copyOf(attempts);
		Map<SessionRole, List<TokenUsage>> sessions = new EnumMap<>(SessionRole.class);
		for (Map.Entry<SessionRole, List<TokenUsage>> entry : tokenUsage.entrySet())
			sessions.put(entry.getKey(), List.copyOf(entry.getValue()));
		tokenUsage = Map.copyOf(sessions);
	}

	/**
	 * Creates fresh descriptive evidence with no author promotion results.
	 *
	 * @param authors the author diagnoses
	 * @param removed the removed hunks
	 * @param promptSizes the prompt measurements
	 * @param trialInputs the trial observations
	 */
	public OptimizationEvidence(List<AuthorDiagnosis> authors, List<String> removed,
		Map<Sha256, PromptSize> promptSizes,
		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> trialInputs)
	{
		this(authors, removed, promptSizes, trialInputs, List.of(), Map.of());
	}

	/**
	 * Records one completed trial's input usage without changing compliance evidence.
	 *
	 * @param identity the observed conditions under which the tokens were measured
	 * @param snapshot the trial prompt-system snapshot
	 * @param cell the non-blank trial cell identifier
	 * @param input the input tokens, or {@code null} when unavailable
	 * @return the updated descriptive evidence
	 * @throws NullPointerException if {@code identity}, {@code snapshot} or {@code cell} are null
	 * @throws IllegalArgumentException if {@code input} is negative or {@code cell} is blank
	 */
	public OptimizationEvidence addTrial(MeasurementIdentity identity, Sha256 snapshot, String cell, Long input)
	{
		requireThat(identity, "identity").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(cell, "cell").isNotBlank();

		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> updated = new HashMap<>(trialInputs);
		Map<Sha256, Map<String, TrialInputs>> snapshots = new HashMap<>(trialInputs.getOrDefault(identity, Map.of()));
		Map<String, TrialInputs> cells = new HashMap<>(snapshots.getOrDefault(snapshot, Map.of()));
		cells.put(cell, cells.getOrDefault(cell, TrialInputs.EMPTY).add(input));
		snapshots.put(snapshot, cells);
		updated.put(identity, snapshots);
		return new OptimizationEvidence(authors, removed, promptSizes, updated, attempts, tokenUsage);
	}

	/**
	 * Adds a finished session's reported usage independently of its compliance outcome.
	 *
	 * @param role the session's role
	 * @param usage the reported token counts
	 * @return the updated evidence
	 * @throws NullPointerException if {@code role} or {@code usage} are null
	 */
	public OptimizationEvidence addUsage(SessionRole role, TokenUsage usage)
	{
		requireThat(role, "role").isNotNull();
		requireThat(usage, "usage").isNotNull();
		Map<SessionRole, List<TokenUsage>> updated = new EnumMap<>(SessionRole.class);
		updated.putAll(tokenUsage);
		List<TokenUsage> sessions = new ArrayList<>(updated.getOrDefault(role, List.of()));
		sessions.add(usage);
		updated.put(role, sessions);
		return new OptimizationEvidence(authors, removed, promptSizes, trialInputs, attempts, updated);
	}
}
