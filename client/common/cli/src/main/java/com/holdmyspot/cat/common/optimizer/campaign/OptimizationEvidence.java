package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Durable descriptive optimization evidence, separate from pass and failure counts and allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param promptAuthors the retained prompt author diagnoses
 * @param removed the hunks removed from promoted attempts
 * @param promptSizes measured target prompt file sizes by snapshot
 * @param trialInputs completed trial input observations by measurement identity, snapshot and cell
 * @param attempts retained original promotion results in launch order
 * @param tokenUsage reported usage of every finished nested session, by role
 * @param approaches the editing approach and order origin of each reserved prompt author attempt
 */
public record OptimizationEvidence(List<PromptAuthorDiagnosis> promptAuthors, List<String> removed,
	Map<Sha256, PromptSize> promptSizes,
	Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> trialInputs,
	List<AttemptResult> attempts, Map<SessionRole, List<TokenUsage>> tokenUsage,
	Map<Integer, ApproachAssignment> approaches)
{
	/**
	 * No descriptive evidence has been recorded.
	 */
	public static final OptimizationEvidence EMPTY = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of());

	/**
	 * Creates evidence.
	 *
	 * @param promptAuthors the retained prompt author diagnoses
	 * @param removed the removed hunks
	 * @param promptSizes measured target prompt file sizes by snapshot
	 * @param trialInputs completed trial input observations by measurement identity, snapshot and cell
	 * @param attempts retained original promotion results in launch order
	 * @param tokenUsage reported usage of every finished nested session, by role
	 * @param approaches durable approach assignments by reserved attempt number
	 * @throws NullPointerException if an argument or element is null
	 */
	public OptimizationEvidence
	{
		approaches = Map.copyOf(approaches);
		promptAuthors = List.copyOf(promptAuthors);
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
	 * Creates fresh descriptive evidence with no prompt author promotion results.
	 *
	 * @param promptAuthors the prompt author diagnoses
	 * @param removed the removed hunks
	 * @param promptSizes the target prompt file measurements
	 * @param trialInputs the trial observations
	 */
	public OptimizationEvidence(List<PromptAuthorDiagnosis> promptAuthors, List<String> removed,
		Map<Sha256, PromptSize> promptSizes,
		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> trialInputs)
	{
		this(promptAuthors, removed, promptSizes, trialInputs, List.of(), Map.of(), Map.of());
	}

	/**
	 * Creates evidence without recorded approach assignments.
	 *
	 * @param promptAuthors retained diagnoses
	 * @param removed       removed hunks
	 * @param promptSizes   target file measurements
	 * @param trialInputs   trial observations
	 * @param attempts      promotion results
	 * @param tokenUsage    usage by session role
	 */
	public OptimizationEvidence(List<PromptAuthorDiagnosis> promptAuthors, List<String> removed,
		Map<Sha256, PromptSize> promptSizes,
		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> trialInputs,
		List<AttemptResult> attempts, Map<SessionRole, List<TokenUsage>> tokenUsage)
	{
		this(promptAuthors, removed, promptSizes, trialInputs, attempts, tokenUsage, Map.of());
	}

	/**
	 * Returns a recorded assignment, or derives a saved declaration-order attempt from the original nine approaches.
	 *
	 * @param number the attempt number
	 * @param cell   the addressed cell
	 * @return the assignment
	 */
	public ApproachAssignment approach(int number, String cell)
	{
		ApproachAssignment assignment = approaches.get(number);
		if (assignment != null)
			return assignment;
		return new ApproachAssignment(PromptAuthorApproach.values()[promptAuthorAttempts(cell, number) %
			PromptAuthorApproach.LEGACY_COUNT],
			ApproachAssignment.Source.DECLARATION);
	}

	/**
	 * Records a reservation's approach before its prompt author starts.
	 *
	 * @param number     the reserved attempt
	 * @param assignment its assigned approach and order origin
	 * @return the updated evidence
	 */
	public OptimizationEvidence withApproach(int number, ApproachAssignment assignment)
	{
		Map<Integer, ApproachAssignment> updated = new HashMap<>(approaches);
		updated.put(number, assignment);
		return new OptimizationEvidence(promptAuthors, removed, promptSizes, trialInputs, attempts, tokenUsage, updated);
	}

	/**
	 * Returns the number of prompt author attempts that addressed a cell before an attempt number.
	 *
	 * @param cell the addressed cell
	 * @param before the first attempt number not counted
	 * @return the number of distinct earlier attempts for which CAT retained a manifest or a promotion result
	 */
	public int promptAuthorAttempts(String cell, int before)
	{
		Set<Integer> numbers = new HashSet<>();
		for (PromptAuthorDiagnosis promptAuthor : promptAuthors)
		{
			if (promptAuthor.cell().equals(cell) && promptAuthor.number() < before)
				numbers.add(promptAuthor.number());
		}
		for (AttemptResult attempt : attempts)
		{
			if (attempt.cell().equals(cell) && attempt.number() < before)
				numbers.add(attempt.number());
		}
		return numbers.size();
	}

	/**
	 * Records one completed trial's input usage without changing pass or failure evidence.
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
		return new OptimizationEvidence(promptAuthors, removed, promptSizes, updated, attempts, tokenUsage, approaches);
	}

	/**
	 * Adds a finished session's reported usage independently of its pass or fail outcome.
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
		return new OptimizationEvidence(promptAuthors, removed, promptSizes, trialInputs, attempts, updated, approaches);
	}
}
