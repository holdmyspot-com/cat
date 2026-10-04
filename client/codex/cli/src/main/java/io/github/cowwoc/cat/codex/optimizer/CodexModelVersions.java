package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Codex models that the prompt optimizer may run a trial under: those of version 6 or later.
 * <p>
 * A model's slug carries its version as {@code gpt-<version>} or {@code gpt-<version>-<rest>}, where
 * {@code <version>} is digits optionally followed by {@code .digits}, such as {@code 6} or {@code 6.1}. A model is of
 * version 6 or later when the number before any dot is 6 or greater. A slug without that form, such as
 * {@code codex-auto-review}, is not of version 6 or later.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class CodexModelVersions
{
	private static final Pattern SLUG = Pattern.compile("gpt-(\\d+)(?:\\.\\d+)?(?:-.+)?");
	private static final BigInteger EARLIEST_MAJOR = BigInteger.valueOf(6);

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexModelVersions()
	{
	}

	/**
	 * Indicates whether a model slug has the form {@code gpt-<version>} or {@code gpt-<version>-<rest>} with a major
	 * version of 6 or later.
	 *
	 * @param slug the model's slug
	 * @return {@code true} if the model is of version 6 or later
	 * @throws NullPointerException if {@code slug} is null
	 */
	static boolean isSixOrLater(String slug)
	{
		requireThat(slug, "slug").isNotNull();
		Matcher matcher = SLUG.matcher(slug);
		return matcher.matches() && new BigInteger(matcher.group(1)).compareTo(EARLIEST_MAJOR) >= 0;
	}
}
