package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

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
 * @param trialInputs completed trial input observations by snapshot and cell
 * @param attempts retained original promotion results in launch order
 */
public record OptimizationEvidence(List<AuthorDiagnosis> authors, List<String> removed,
	Map<Sha256, PromptSize> promptSizes, Map<Sha256, Map<String, TrialInputs>> trialInputs,
	List<AttemptResult> attempts)
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
	 * @param trialInputs completed trial input observations by snapshot and cell
	 * @param attempts retained original promotion results in launch order
	 * @throws NullPointerException if an argument or element is null
	 */
	public OptimizationEvidence
	{
		authors = List.copyOf(authors);
		removed = List.copyOf(removed);
		promptSizes = Map.copyOf(promptSizes);
		Map<Sha256, Map<String, TrialInputs>> copied = new HashMap<>();
		for (Map.Entry<Sha256, Map<String, TrialInputs>> entry : trialInputs.entrySet())
			copied.put(entry.getKey(), Map.copyOf(entry.getValue()));
		trialInputs = Map.copyOf(copied);
		attempts = List.copyOf(attempts);
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
		Map<Sha256, PromptSize> promptSizes, Map<Sha256, Map<String, TrialInputs>> trialInputs)
	{
		this(authors, removed, promptSizes, trialInputs, List.of());
	}

	/**
	 * Records one completed trial's input usage without changing compliance evidence.
	 *
	 * @param snapshot the trial snapshot
	 * @param cell the trial cell
	 * @param input the input tokens, or {@code null} when unavailable
	 * @return the updated descriptive evidence
	 * @throws NullPointerException if snapshot or cell is null
	 * @throws IllegalArgumentException if input is negative or cell is blank
	 */
	public OptimizationEvidence addTrial(Sha256 snapshot, String cell, Long input)
	{
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(cell, "cell").isNotBlank();
		Map<Sha256, Map<String, TrialInputs>> updated = new HashMap<>(trialInputs);
		Map<String, TrialInputs> cells = new HashMap<>(trialInputs.getOrDefault(snapshot, Map.of()));
		cells.put(cell, cells.getOrDefault(cell, TrialInputs.EMPTY).add(input));
		updated.put(snapshot, cells);
		return new OptimizationEvidence(authors, removed, promptSizes, updated, attempts);
	}
}
