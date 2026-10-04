package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Locale;
import java.util.Optional;

/**
 * The cells that certification tests and that a claim covers, which the user chooses at confirmation.
 * <p>
 * A category with one cell is certified in that cell under every choice.
 */
public enum CertificationScope
{
	/**
	 * Each category's weakest cell only: the lowest effort of the least capable model among the latest versions.
	 */
	WEAKEST_CELL("each category's weakest cell only"),
	/**
	 * Every cell of each model family's latest version.
	 */
	LATEST_VERSIONS("every cell of each model family's latest version"),
	/**
	 * The cells that the user described in revision feedback.
	 */
	DESCRIBED("the cells that you described in revision feedback");

	private final String summary;

	/**
	 * Creates a choice.
	 *
	 * @param summary the cells that the choice covers, as a phrase
	 */
	CertificationScope(String summary)
	{
		this.summary = summary;
	}

	/**
	 * Returns the identifier that the {@code confirm} command accepts.
	 *
	 * @return the identifier, such as {@code weakest-cell}
	 */
	public String id()
	{
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/**
	 * Returns the cells that the choice covers.
	 *
	 * @return a phrase, such as {@code each category's weakest cell only}
	 */
	public String summary()
	{
		return summary;
	}

	/**
	 * Returns the choice that an identifier names.
	 *
	 * @param id an identifier that the {@code confirm} command received
	 * @return the choice, or empty if no choice has this identifier
	 * @throws NullPointerException if {@code id} is null
	 */
	public static Optional<CertificationScope> fromId(String id)
	{
		requireThat(id, "id").isNotNull();
		for (CertificationScope scope : values())
		{
			if (scope.id().equals(id))
				return Optional.of(scope);
		}
		return Optional.empty();
	}

	/**
	 * Describes every choice for a message that asks the user to choose one.
	 *
	 * @return text such as {@code weakest-cell (each category's weakest cell only), ...}
	 */
	public static String choices()
	{
		CertificationScope[] scopes = values();
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < scopes.length; ++i)
		{
			if (i == scopes.length - 1)
				text.append(" or ");
			else if (i > 0)
				text.append(", ");
			text.append(scopes[i].id()).append(" (").append(scopes[i].summary).append(')');
		}
		return text.toString();
	}
}
