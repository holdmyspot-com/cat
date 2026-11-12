package com.holdmyspot.cat.common.optimizer.campaign;

import java.util.Optional;

/**
 * The outcome that a delivery's entry in the certification history is labelled with.
 */
public enum DeliveryLabel
{
	/**
	 * A certified candidate was delivered.
	 */
	CERTIFIED("certified"),
	/**
	 * A candidate proven better than the incumbent, but not certified, was kept and delivered.
	 */
	IMPROVED_NOT_CERTIFIED("improved, not certified"),
	/**
	 * The user explicitly accepted an uncertified candidate.
	 */
	NOT_CERTIFIED("not certified"),
	/**
	 * The user kept the incumbent, and only the prompt tests were delivered.
	 */
	INCUMBENT_KEPT("incumbent kept");

	private final String label;

	/**
	 * Creates a label.
	 *
	 * @param label the text that the user sees
	 */
	DeliveryLabel(String label)
	{
		this.label = label;
	}

	/**
	 * Returns the text that the user sees.
	 *
	 * @return the label
	 */
	public String label()
	{
		return label;
	}

	/**
	 * Returns the label of a delivery.
	 *
	 * @param outcome the label of the delivery request, or {@code null} if the user kept the incumbent
	 * @return the label
	 * @throws IllegalArgumentException if {@code outcome} never accompanies a delivered candidate
	 */
	public static DeliveryLabel of(Outcome outcome)
	{
		if (outcome == null)
			return INCUMBENT_KEPT;
		return switch (outcome)
		{
			case CERTIFIED -> CERTIFIED;
			case IMPROVED_NOT_CERTIFIED -> IMPROVED_NOT_CERTIFIED;
			case NOT_CERTIFIED -> NOT_CERTIFIED;
			case BELOW_MINIMUM, BELOW_CONTROL, BELOW_INCUMBENT, INCONCLUSIVE, STOPPED_FOR_CONTRACT_REVISION,
				STOPPED_FOR_TEST_REVISION, TESTING_ENVIRONMENT_CHANGED, UNSUPPORTED_INSTRUCTION_SOURCE,
				INELIGIBLE_TRIAL_EFFECT, UNSUPPORTED_OUTPUT, UNSUPPORTED_TEST_INTERACTION,
				INVALID_STATISTICAL_ASSUMPTIONS -> throw new IllegalArgumentException("A delivery is never labelled \"" +
				outcome.label() + "\".");
		};
	}

	/**
	 * Returns the label that a text names.
	 *
	 * @param label the text that the user sees
	 * @return the label, or empty if no label has that text
	 * @throws NullPointerException if {@code label} is null
	 */
	public static Optional<DeliveryLabel> fromLabel(String label)
	{
		for (DeliveryLabel value : values())
		{
			if (value.label.equals(label))
				return Optional.of(value);
		}
		return Optional.empty();
	}
}
