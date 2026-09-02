package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.optimization.cost.MoneyAmount;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Renders one concise optimization-round result from policy decisions and retained evidence.
 *
 * <p>
 * This projection does not calculate statistics, choose a candidate, estimate cost, or select the next action. Those
 * decisions remain with their domain owners. It prevents development evidence from being presented as a formal
 * lower-bound change and retains useful usage evidence when a provider price is unavailable.
 */
public final class OptimizationRoundReport
{
	/**
	 * Renders one complete round snapshot.
	 *
	 * @param snapshot the already-decided round result
	 * @return the user-facing report
	 */
	public static String render(Snapshot snapshot)
	{
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		StringBuilder result = new StringBuilder(512);
		if (snapshot.currentLowerBound().isPresent())
		{
			result.append("Current lower bound: ").append(percent(snapshot.currentLowerBound().getAsDouble(), 1)).
				append('\n');
		}
		else
			result.append("Current lower bound: unavailable\n");
		result.append("Target: above ").append(percent(snapshot.target(), 0)).append('\n').
			append("Best validated version: ").append(snapshot.bestValidatedVersion()).append('\n');
		appendUsage(result, snapshot.usage());
		if (snapshot.evidenceKind() == EvidenceKind.DEVELOPMENT)
			result.append("Round evidence: development only; Current lower bound unchanged\n");

		if (!snapshot.limitingFamilies().isEmpty())
		{
			result.append("\nLimiting families:\n");
			for (LimitingFamily family : snapshot.limitingFamilies())
			{
				result.append("- ").append(family.name()).append(": ").append(family.formalTrials()).
					append(" formal trials, ").append(family.failures()).append(" failures, ").
					append(family.status().description).append('\n');
			}
		}

		NextAction nextAction = snapshot.nextAction();
		result.append("\nRecommended next action: ").append(nextAction.action().description).append('\n').
			append("CAT will: ").append(nextAction.work()).append('\n').
			append("Why: ").append(nextAction.reason()).append('\n');
		return result.toString();
	}

	/**
	 * Appends provider cost when known, otherwise the observed provider-neutral usage.
	 *
	 * @param target the report under construction
	 * @param usage the retained operation usage
	 */
	private static void appendUsage(StringBuilder target, Usage usage)
	{
		if (usage.providerCost().isPresent())
		{
			MoneyAmount cost = usage.providerCost().orElseThrow();
			target.append("Amount spent: ").append(cost.currency().getCurrencyCode()).append(' ').
				append(cost.currency().getSymbol(Locale.US)).append(cost.amount().toPlainString()).append('\n');
			return;
		}
		target.append("Amount spent: unavailable\n").
			append("Observed usage: ").append(String.format(Locale.ROOT, "%,d", usage.inputTokens())).
			append(" input tokens, ").append(String.format(Locale.ROOT, "%,d", usage.outputTokens())).
			append(" output tokens, ").append(usage.operations()).append(" operations, ").
			append(duration(usage.elapsed())).append('\n');
	}

	/**
	 * Formats one probability as a percentage.
	 *
	 * @param value the probability
	 * @param decimals the displayed decimal places
	 * @return the percentage
	 */
	private static String percent(double value, int decimals)
	{
		return String.format(Locale.ROOT, "%." + decimals + "f%%", value * 100);
	}

	/**
	 * Formats elapsed time without subsecond detail.
	 *
	 * @param value the elapsed duration
	 * @return the compact duration
	 */
	private static String duration(Duration value)
	{
		long seconds = value.toSeconds();
		long hours = seconds / 3_600;
		long minutes = seconds % 3_600 / 60;
		long remainder = seconds % 60;
		if (hours > 0)
			return hours + "h " + minutes + "m " + remainder + "s";
		if (minutes > 0)
			return minutes + "m " + remainder + "s";
		return remainder + "s";
	}

	/**
	 * Names whether the completed round added formal or development-only evidence.
	 */
	public enum EvidenceKind
	{
		FORMAL,
		DEVELOPMENT
	}

	/**
	 * Names the proof-covered state of a limiting family.
	 */
	public enum FamilyStatus
	{
		BELOW_TARGET("not yet above target"),
		FUTILE("formal futility boundary reached"),
		INVALID_EVIDENCE("evidence invalid; formal testing ended");

		private final String description;

		/**
		 * Creates one user-facing family status.
		 *
		 * @param description the concise status
		 */
		FamilyStatus(String description)
		{
			this.description = description;
		}
	}

	/**
	 * Names the constrained next action selected by the workflow policy.
	 */
	public enum Action
	{
		CONTINUE_TARGET_ONLY("continue_target_only", "continue with this prompt file"),
		REPAIR_PROMPT_SYSTEM("repair_prompt_system", "repair the prompt-system conflict"),
		START_FORMAL_BATCH("start_formal_batch", "start formal certification"),
		FINISH_NOT_CERTIFIED("finish_not_certified", "finish as not certified");

		private final String externalValue;
		private final String description;

		/**
		 * Creates one stable action mapping.
		 *
		 * @param externalValue the persisted value
		 * @param description the user-facing action
		 */
		Action(String externalValue, String description)
		{
			this.externalValue = externalValue;
			this.description = description;
		}

		/**
		 * Parses one exact action at an untyped boundary.
		 *
		 * @param value the persisted value
		 * @return the closed action
		 * @throws IllegalArgumentException if the value is unsupported
		 */
		public static Action fromExternalValue(String value)
		{
			Objects.requireNonNull(value, "value must not be null");
			for (Action candidate : values())
			{
				if (candidate.externalValue.equals(value))
					return candidate;
			}
			throw new IllegalArgumentException("Unsupported optimization report action: " + value);
		}
	}

	/**
	 * Holds retained operation usage and optional provider cost.
	 *
	 * @param providerCost the exact accumulated provider cost, or empty when it cannot be determined
	 * @param inputTokens observed input tokens, or zero when unavailable
	 * @param outputTokens observed output tokens, or zero when unavailable
	 * @param elapsed observed elapsed time
	 * @param operations completed model operations
	 */
	public record Usage(Optional<MoneyAmount> providerCost, long inputTokens, long outputTokens, Duration elapsed,
		int operations)
	{
		/**
		 * Validates and snapshots retained usage.
		 *
		 * @param providerCost the exact accumulated provider cost, or empty when it cannot be determined
		 * @param inputTokens observed input tokens, or zero when unavailable
		 * @param outputTokens observed output tokens, or zero when unavailable
		 * @param elapsed observed elapsed time
		 * @param operations completed model operations
		 */
		public Usage
		{
			providerCost = Objects.requireNonNull(providerCost, "providerCost must not be null");
			Objects.requireNonNull(elapsed, "elapsed must not be null");
			if (inputTokens < 0 || outputTokens < 0 || elapsed.isNegative() || operations < 0)
				throw new IllegalArgumentException("Usage values must not be negative");
		}
	}

	/**
	 * Holds one limiting-family diagnostic without presenting it as a simultaneous family bound.
	 *
	 * @param name the user-facing family name
	 * @param formalTrials valid formal trial count
	 * @param failures valid formal failure count
	 * @param status the proof-covered family status
	 */
	public record LimitingFamily(String name, int formalTrials, int failures, FamilyStatus status)
	{
		/**
		 * Validates one limiting-family diagnostic.
		 *
		 * @param name the user-facing family name
		 * @param formalTrials valid formal trial count
		 * @param failures valid formal failure count
		 * @param status the proof-covered family status
		 */
		public LimitingFamily
		{
			name = requireText(name, "name");
			Objects.requireNonNull(status, "status must not be null");
			if (formalTrials < 0 || failures < 0 || failures > formalTrials)
				throw new IllegalArgumentException("Family trial counts are inconsistent");
		}
	}

	/**
	 * Holds one already-selected actionable recommendation.
	 *
	 * @param action the constrained next action
	 * @param work the concrete work CAT will perform
	 * @param reason why the action is recommended from current evidence
	 */
	public record NextAction(Action action, String work, String reason)
	{
		/**
		 * Validates one actionable recommendation.
		 *
		 * @param action the constrained next action
		 * @param work the concrete work CAT will perform
		 * @param reason why the action is recommended from current evidence
		 */
		public NextAction
		{
			Objects.requireNonNull(action, "action must not be null");
			work = requireText(work, "work");
			reason = requireText(reason, "reason");
		}
	}

	/**
	 * Holds all evidence and decisions needed to report one completed round.
	 *
	 * @param currentLowerBound the valid formal scalar bound, or empty when none exists under the active contract
	 * @param target the formal target probability
	 * @param bestValidatedVersion the retained candidate identifier
	 * @param usage retained operation usage and optional provider cost
	 * @param evidenceKind whether this round added formal or development-only evidence
	 * @param limitingFamilies proof-covered family diagnostics
	 * @param nextAction the already-selected recommendation
	 */
	public record Snapshot(OptionalDouble currentLowerBound, double target, String bestValidatedVersion, Usage usage,
		EvidenceKind evidenceKind, List<LimitingFamily> limitingFamilies, NextAction nextAction)
	{
		/**
		 * Validates and snapshots one round projection.
		 *
		 * @param currentLowerBound the valid formal scalar bound, or empty when none exists under the active contract
		 * @param target the formal target probability
		 * @param bestValidatedVersion the retained candidate identifier
		 * @param usage retained operation usage and optional provider cost
		 * @param evidenceKind whether this round added formal or development-only evidence
		 * @param limitingFamilies proof-covered family diagnostics
		 * @param nextAction the already-selected recommendation
		 */
		public Snapshot
		{
			currentLowerBound = Objects.requireNonNull(currentLowerBound, "currentLowerBound must not be null");
			if (currentLowerBound.isPresent())
				requireProbability(currentLowerBound.getAsDouble(), "currentLowerBound");
			requireProbability(target, "target");
			bestValidatedVersion = requireText(bestValidatedVersion, "bestValidatedVersion");
			Objects.requireNonNull(usage, "usage must not be null");
			Objects.requireNonNull(evidenceKind, "evidenceKind must not be null");
			limitingFamilies = List.copyOf(limitingFamilies);
			if (limitingFamilies.stream().anyMatch(Objects::isNull))
				throw new NullPointerException("limitingFamilies must not contain null elements");
			Objects.requireNonNull(nextAction, "nextAction must not be null");
		}
	}

	/**
	 * Requires a nonblank user-facing value.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the validated value
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Requires a finite probability.
	 *
	 * @param value the value
	 * @param name the parameter name
	 */
	private static void requireProbability(double value, String name)
	{
		if (!Double.isFinite(value) || value < 0 || value > 1)
			throw new IllegalArgumentException(name + " must be between zero and one");
	}

	/**
	 * Prevents construction of this static projection owner.
	 */
	private OptimizationRoundReport()
	{
	}
}
