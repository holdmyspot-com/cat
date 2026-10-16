package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A file that CAT's rules give an agent, and how the file reaches it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param file     the absolute, normalized file
 * @param kind     how the file reaches the agent
 * @param referrer the file that includes, refers to or needs this file, or empty for a rule that the loader injects
 *                 or routes to
 */
public record RuleSource(Path file, Kind kind, Optional<Path> referrer)
{
	/**
	 * Creates a rule source.
	 *
	 * @param file     the absolute, normalized file
	 * @param kind     how the file reaches the agent
	 * @param referrer the file that includes, refers to or needs this file, or empty for a rule that the loader
	 *                 injects or routes to
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} is not absolute, or {@code referrer} is empty exactly when
	 *                                  {@code kind} needs a referrer
	 */
	public RuleSource
	{
		requireThat(file, "file").isNotNull();
		requireThat(file.isAbsolute(), "file.isAbsolute()").isTrue();
		requireThat(kind, "kind").isNotNull();
		requireThat(referrer, "referrer").isNotNull();
		requireThat(referrer.isPresent(), "referrer.isPresent()").isEqualTo(kind.hasReferrer());
	}

	/**
	 * How a file reaches the agent.
	 */
	public enum Kind
	{
		/**
		 * The loader injects the rule into the session.
		 */
		INJECTED(false),
		/**
		 * The loader tells the agent to load the rule when it reads or edits a file that the rule's paths match.
		 */
		ROUTED(false),
		/**
		 * The referrer includes the file, so the loader injects the file's content with the referrer.
		 */
		INCLUDED(true),
		/**
		 * The referrer tells the agent to load the file.
		 */
		REFERENCED(true),
		/**
		 * The loader tells the agent to load the file before it loads the referrer.
		 */
		PREREQUISITE(true);

		private final boolean hasReferrer;

		/**
		 * Creates a kind.
		 *
		 * @param hasReferrer {@code true} if a file of this kind reaches the agent through another file
		 */
		Kind(boolean hasReferrer)
		{
			this.hasReferrer = hasReferrer;
		}

		/**
		 * Indicates whether a file of this kind reaches the agent through another file.
		 *
		 * @return {@code true} if a file of this kind has a referrer
		 */
		public boolean hasReferrer()
		{
			return hasReferrer;
		}
	}
}
