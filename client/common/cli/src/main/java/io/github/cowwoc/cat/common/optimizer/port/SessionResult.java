package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * How a nested session ended.
 * <p>
 * Only {@link Completed} carries evidence. Every other result is an infrastructure attempt that yields no outcome.
 */
public sealed interface SessionResult
{
	/**
	 * Returns the rate-limit windows that the harness reported during the session.
	 *
	 * @return the windows, empty if the harness reported none
	 */
	List<RateLimitWindow> windows();

	/**
	 * The session ran to the end of its turns, or to its time limit.
	 * <p>
	 * A session that the time limit cut off is completed with the replies it gave so far, because design &sect;3.2
	 * judges such a trial on its behaviour rather than retrying it. {@code responses} then holds fewer entries than
	 * the request had turns.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param transcript the retained native event archive
	 * @param responses the final response to each turn, in turn order
	 * @param effects the directory that holds the session's project effects
	 * @param model the model observed in native events, or {@code null} if the events did not name one
	 * @param reasoning the reasoning configuration observed in native events, or {@code null} if the events did not
	 *   name one
	 * @param usage the reported token usage, or {@code null} if the harness did not report it
	 * @param windows the rate-limit windows that the harness reported during the session
	 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading (design
	 *   &sect;5), normalized so that staged paths do not appear in it, or {@code none} if the events showed nothing
	 *   loading
	 */
	record Completed(Path transcript, List<String> responses, Path effects, String model, String reasoning,
		TokenUsage usage, List<RateLimitWindow> windows, String promptSources) implements SessionResult
	{
		/**
		 * Creates a completed result.
		 *
		 * @param transcript the retained native event archive
		 * @param responses the final response to each turn, in turn order
		 * @param effects the directory that holds the session's project effects
		 * @param model the model observed in native events, or {@code null} if the events did not name one
		 * @param reasoning the reasoning configuration observed in native events, or {@code null} if the events did
		 *   not name one
		 * @param usage the reported token usage, or {@code null} if the harness did not report it
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading
		 * @throws NullPointerException if {@code transcript}, {@code responses}, {@code effects}, {@code windows} or
		 *   {@code promptSources} are null
		 * @throws IllegalArgumentException if {@code promptSources} is blank
		 */
		public Completed
		{
			requireThat(transcript, "transcript").isNotNull();
			requireThat(responses, "responses").isNotNull();
			requireThat(effects, "effects").isNotNull();
			requireThat(windows, "windows").isNotNull();
			requireThat(promptSources, "promptSources").isNotBlank();
			responses = List.copyOf(responses);
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The session stopped because CAT interrupted it.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record Interrupted(List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates an interrupted result.
		 *
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if {@code windows} is null
		 */
		public Interrupted
		{
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The provider rejected the session because of a rate limit.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record RateLimited(Instant resetsAt, List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates a rate-limited result.
		 *
		 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if {@code windows} is null
		 */
		public RateLimited
		{
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}

	/**
	 * The session ended without replying to every turn for a reason other than an interrupt or rate limit.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param reason what went wrong, for the infrastructure-failure record
	 * @param windows the rate-limit windows that the harness reported during the session
	 */
	record Failed(String reason, List<RateLimitWindow> windows) implements SessionResult
	{
		/**
		 * Creates a failed result.
		 *
		 * @param reason what went wrong, for the infrastructure-failure record
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code reason} is blank
		 */
		public Failed
		{
			requireThat(reason, "reason").isNotBlank();
			requireThat(windows, "windows").isNotNull();
			windows = List.copyOf(windows);
		}
	}
}
