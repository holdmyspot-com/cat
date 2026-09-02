package io.github.cowwoc.cat.common.optimization.cost;

import io.github.cowwoc.cat.common.runner.HarnessModelProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Returns a cost range only when a request falls inside one recorded calibration envelope.
 */
public final class CostForecastCatalog
{
	private final List<Envelope> envelopes;

	/**
	 * Creates a catalog from disjoint, versioned calibration envelopes.
	 *
	 * @param envelopes the available calibration envelopes
	 */
	public CostForecastCatalog(List<Envelope> envelopes)
	{
		Objects.requireNonNull(envelopes, "envelopes must not be null");
		if (envelopes.isEmpty())
			throw new IllegalArgumentException("envelopes must not be empty");
		Set<String> identifiers = new HashSet<>();
		for (Envelope envelope : envelopes)
		{
			Objects.requireNonNull(envelope, "envelope must not be null");
			if (!identifiers.add(envelope.identifier()))
				throw new IllegalArgumentException("Duplicate calibration envelope: " + envelope.identifier());
		}
		this.envelopes = List.copyOf(envelopes);
	}

	/**
	 * Forecasts a request when all categorical values and count bands are calibrated.
	 *
	 * @param request the proposed certification dimensions
	 * @return the qualified range or an explicit unavailable result
	 */
	public Forecast forecast(Request request)
	{
		Objects.requireNonNull(request, "request must not be null");
		List<Envelope> profileMatches = envelopes.stream().filter(envelope -> envelope.matchesProfile(request)).toList();
		if (profileMatches.isEmpty())
		{
			return new Forecast(Status.COST_UNAVAILABLE, Optional.empty(), Optional.empty(),
				List.of(Dimension.PROFILE), "No reliable certification cost forecast is available for this " +
					"harness, model, effort, and evaluator profile.");
		}

		for (Envelope envelope : profileMatches)
		{
			if (envelope.includes(request))
			{
				return new Forecast(Status.AVAILABLE, Optional.of(envelope.range()),
					Optional.of(envelope.identifier()), List.of(), "Calibrated certification cost forecast available.");
			}
		}

		Envelope nearest = profileMatches.getFirst();
		List<Dimension> unavailable = nearest.outsideDimensions(request);
		return new Forecast(Status.COST_UNAVAILABLE, Optional.empty(), Optional.empty(), unavailable,
			unavailableMessage(request, nearest, unavailable));
	}

	/**
	 * Formats the narrowest actionable uncalibrated-dimension message.
	 *
	 * @param request the proposed certification request
	 * @param envelope the nearest matching envelope
	 * @param dimensions the out-of-range dimensions
	 * @return the user-facing message
	 */
	private static String unavailableMessage(Request request, Envelope envelope, List<Dimension> dimensions)
	{
		if (dimensions.size() == 1)
		{
			return switch (dimensions.getFirst())
			{
				case FAMILY_COUNT -> countMessage("family count", request.familyCount(), envelope.familyCount());
				case INTERACTION_COUNT -> countMessage("interaction count", request.interactionCount(),
					envelope.interactionCount());
				case CANDIDATE_COUNT -> countMessage("candidate count", request.candidateCount(),
					envelope.candidateCount());
				case PROFILE -> throw new AssertionError("Profile mismatches are handled before count bands");
			};
		}
		return "No reliable certification cost forecast is available because these values are outside calibration: " +
			dimensions.stream().map(Dimension::externalValue).toList() + ".";
	}

	/**
	 * Formats one count-band diagnostic.
	 *
	 * @param label the user-facing dimension label
	 * @param actual the requested count
	 * @param band the calibrated range
	 * @return the diagnostic
	 */
	private static String countMessage(String label, int actual, IntBand band)
	{
		return "No reliable certification cost forecast is available for " + label + " " + actual +
			"; the calibrated range is " + band.minimum() + "–" + band.maximum() + ".";
	}

	/**
	 * Names the supported local harnesses.
	 */
	public enum Harness
	{
		CODEX("codex"),
		CLAUDE("claude");

		private final String externalValue;

		/**
		 * Creates one harness value.
		 *
		 * @param externalValue the exact external spelling
		 */
		Harness(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Converts an untyped external value to the supported domain.
		 *
		 * @param value the exact external spelling
		 * @return the harness
		 */
		public static Harness fromExternalValue(String value)
		{
			for (Harness harness : values())
			{
				if (harness.externalValue.equals(value))
					return harness;
			}
			throw new IllegalArgumentException("Unsupported harness: " + value);
		}

		/**
		 * Returns the exact external spelling.
		 *
		 * @return the external spelling
		 */
		public String externalValue()
		{
			return externalValue;
		}
	}

	/**
	 * Names a forecast dimension that may be outside calibration.
	 */
	public enum Dimension
	{
		PROFILE("profile"),
		FAMILY_COUNT("family count"),
		INTERACTION_COUNT("interaction count"),
		CANDIDATE_COUNT("candidate count");

		private final String externalValue;

		/**
		 * Creates one dimension.
		 *
		 * @param externalValue the user-facing name
		 */
		Dimension(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the user-facing name.
		 *
		 * @return the name
		 */
		public String externalValue()
		{
			return externalValue;
		}
	}

	/**
	 * Names whether a reliable forecast is available.
	 */
	public enum Status
	{
		AVAILABLE,
		COST_UNAVAILABLE
	}

	/**
	 * Holds one proposed certification's forecast dimensions.
	 *
	 * @param harness the harness
	 * @param modelProfile the exact model and effort
	 * @param evaluatorProtocolIdentifier the frozen evaluator protocol
	 * @param familyCount the number of leaf families
	 * @param interactionCount the number of interaction families
	 * @param candidateCount the formal candidate-batch size
	 */
	public record Request(Harness harness, HarnessModelProfile modelProfile, String evaluatorProtocolIdentifier,
		int familyCount, int interactionCount, int candidateCount)
	{
		/**
		 * Validates one request.
		 *
		 * @param harness the harness
		 * @param modelProfile the exact model and effort
		 * @param evaluatorProtocolIdentifier the frozen evaluator protocol
		 * @param familyCount the number of leaf families
		 * @param interactionCount the number of interaction families
		 * @param candidateCount the formal candidate-batch size
		 */
		public Request
		{
			Objects.requireNonNull(harness, "harness must not be null");
			Objects.requireNonNull(modelProfile, "modelProfile must not be null");
			evaluatorProtocolIdentifier = requireText(evaluatorProtocolIdentifier, "evaluatorProtocolIdentifier");
			if (familyCount <= 0)
				throw new IllegalArgumentException("familyCount must be positive");
			if (interactionCount < 0)
				throw new IllegalArgumentException("interactionCount must not be negative");
			if (candidateCount <= 0)
				throw new IllegalArgumentException("candidateCount must be positive");
		}
	}

	/**
	 * Holds one inclusive nonnegative integer calibration band.
	 *
	 * @param minimum the inclusive minimum
	 * @param maximum the inclusive maximum
	 */
	public record IntBand(int minimum, int maximum)
	{
		/**
		 * Validates one band.
		 *
		 * @param minimum the inclusive minimum
		 * @param maximum the inclusive maximum
		 */
		public IntBand
		{
			if (minimum < 0 || maximum < minimum)
				throw new IllegalArgumentException("Integer calibration band is invalid");
		}

		/**
		 * Returns whether the band contains a value.
		 *
		 * @param value the value
		 * @return {@code true} if contained
		 */
		public boolean contains(int value)
		{
			return value >= minimum && value <= maximum;
		}
	}

	/**
	 * Holds a conservative exact-money forecast range.
	 *
	 * @param minimum the inclusive lower estimate
	 * @param maximum the inclusive conservative maximum
	 */
	public record Range(MoneyAmount minimum, MoneyAmount maximum)
	{
		/**
		 * Validates one range.
		 *
		 * @param minimum the inclusive lower estimate
		 * @param maximum the inclusive conservative maximum
		 */
		public Range
		{
			Objects.requireNonNull(minimum, "minimum must not be null");
			Objects.requireNonNull(maximum, "maximum must not be null");
			minimum.add(maximum);
			if (minimum.amount().compareTo(maximum.amount()) > 0)
				throw new IllegalArgumentException("minimum must not exceed maximum");
		}
	}

	/**
	 * Holds one immutable calibration envelope.
	 *
	 * @param identifier the envelope and source revision identifier
	 * @param harness the calibrated harness
	 * @param modelProfile the calibrated model and effort
	 * @param evaluatorProtocolIdentifier the calibrated evaluator protocol
	 * @param familyCount the calibrated family-count band
	 * @param interactionCount the calibrated interaction-count band
	 * @param candidateCount the calibrated candidate-count band
	 * @param range the observed conservative cost range
	 * @param observations the calibration observation count
	 */
	public record Envelope(String identifier, Harness harness, HarnessModelProfile modelProfile,
		String evaluatorProtocolIdentifier, IntBand familyCount, IntBand interactionCount, IntBand candidateCount,
		Range range, int observations)
	{
		/**
		 * Validates one envelope.
		 *
		 * @param identifier the envelope and source revision identifier
		 * @param harness the calibrated harness
		 * @param modelProfile the calibrated model and effort
		 * @param evaluatorProtocolIdentifier the calibrated evaluator protocol
		 * @param familyCount the calibrated family-count band
		 * @param interactionCount the calibrated interaction-count band
		 * @param candidateCount the calibrated candidate-count band
		 * @param range the observed conservative cost range
		 * @param observations the calibration observation count
		 */
		public Envelope
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(harness, "harness must not be null");
			Objects.requireNonNull(modelProfile, "modelProfile must not be null");
			evaluatorProtocolIdentifier = requireText(evaluatorProtocolIdentifier, "evaluatorProtocolIdentifier");
			Objects.requireNonNull(familyCount, "familyCount must not be null");
			Objects.requireNonNull(interactionCount, "interactionCount must not be null");
			Objects.requireNonNull(candidateCount, "candidateCount must not be null");
			Objects.requireNonNull(range, "range must not be null");
			if (observations <= 0)
				throw new IllegalArgumentException("observations must be positive");
		}

		/**
		 * Returns whether every categorical dimension matches.
		 *
		 * @param request the request
		 * @return {@code true} if the profile matches
		 */
		private boolean matchesProfile(Request request)
		{
			return harness == request.harness() && modelProfile.equals(request.modelProfile()) &&
				evaluatorProtocolIdentifier.equals(request.evaluatorProtocolIdentifier());
		}

		/**
		 * Returns whether every count dimension is in range.
		 *
		 * @param request the request
		 * @return {@code true} if every count is calibrated
		 */
		private boolean includes(Request request)
		{
			return familyCount.contains(request.familyCount()) && interactionCount.contains(request.interactionCount()) &&
				candidateCount.contains(request.candidateCount());
		}

		/**
		 * Returns count dimensions outside this envelope.
		 *
		 * @param request the request
		 * @return the dimensions in declaration order
		 */
		private List<Dimension> outsideDimensions(Request request)
		{
			List<Dimension> result = new ArrayList<>();
			if (!familyCount.contains(request.familyCount()))
				result.add(Dimension.FAMILY_COUNT);
			if (!interactionCount.contains(request.interactionCount()))
				result.add(Dimension.INTERACTION_COUNT);
			if (!candidateCount.contains(request.candidateCount()))
				result.add(Dimension.CANDIDATE_COUNT);
			return List.copyOf(result);
		}
	}

	/**
	 * Holds a complete forecast decision.
	 *
	 * @param status whether a reliable forecast is available
	 * @param range the qualified range when available
	 * @param envelopeIdentifier the source envelope when available
	 * @param unavailableDimensions the dimensions preventing a forecast
	 * @param userMessage the actionable user-facing result
	 */
	public record Forecast(Status status, Optional<Range> range, Optional<String> envelopeIdentifier,
		List<Dimension> unavailableDimensions, String userMessage)
	{
		/**
		 * Validates one forecast result.
		 *
		 * @param status whether a reliable forecast is available
		 * @param range the qualified range when available
		 * @param envelopeIdentifier the source envelope when available
		 * @param unavailableDimensions the dimensions preventing a forecast
		 * @param userMessage the actionable user-facing result
		 */
		public Forecast
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(range, "range must not be null");
			Objects.requireNonNull(envelopeIdentifier, "envelopeIdentifier must not be null");
			unavailableDimensions = List.copyOf(unavailableDimensions);
			userMessage = requireText(userMessage, "userMessage");
			boolean available = status == Status.AVAILABLE;
			if (available != range.isPresent() || available != envelopeIdentifier.isPresent() ||
				available != unavailableDimensions.isEmpty())
			{
				throw new IllegalArgumentException("Forecast fields do not match status " + status);
			}
		}
	}

	/**
	 * Requires nonblank text.
	 *
	 * @param value the value
	 * @param name the field name
	 * @return the validated value
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
