package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;

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
	 * A trial that the time limit cut off in a turn with model output or a running hook is completed with the replies
	 * it gave so far, because CAT judges the timeout evidence instead of retrying the trial. {@code responses} then
	 * holds fewer entries than the request had turns.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param transcript the retained harness event archive
	 * @param responses each turn's assistant messages in order, ending with its final response
	 * @param effects the directory that holds the session's project effects
	 * @param model the model observed in harness events, or {@code null} if the events did not name one
	 * @param reasoning the reasoning configuration observed in harness events, or {@code null} if the events did not
	 *   name one
	 * @param usage the reported token usage, or {@code null} if the harness did not report it
	 * @param windows the rate-limit windows that the harness reported during the session
	 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading,
	 *   normalized so that staged paths do not appear in it, or {@code none} if the events showed nothing
	 *   loading
	 * @param toolCalls the root agent's calls in invocation order, with absent commands and exit statuses left null
	 */
	record Completed(Path transcript, List<List<String>> responses, Path effects, String model, String reasoning,
		TokenUsage usage, List<RateLimitWindow> windows, String promptSources, List<ToolCall> toolCalls)
		implements SessionResult
	{
		/**
		 * Creates a completed result.
		 *
		 * @param transcript the retained harness event archive
		 * @param responses each turn's assistant messages in order, ending with its final response
		 * @param effects the directory that holds the session's project effects
		 * @param model the model observed in harness events, or {@code null} if the events did not name one
		 * @param reasoning the reasoning configuration observed in harness events, or {@code null} if the events did
		 *   not name one
		 * @param usage the reported token usage, or {@code null} if the harness did not report it
		 * @param windows the rate-limit windows that the harness reported during the session
		 * @param promptSources the {@code PROMPT_SOURCES} measurement value that the harness was observed loading
		 * @param toolCalls the root agent's calls in invocation order, with absent commands and exit statuses left null
		 * @throws NullPointerException if {@code transcript}, {@code responses}, {@code effects}, {@code windows},
		 *   {@code promptSources} or {@code toolCalls} are null, or a response list, message or {@code toolCalls}
		 *   element is null
		 * @throws IllegalArgumentException if {@code promptSources} is blank
		 */
		public Completed
		{
			requireThat(transcript, "transcript").isNotNull();
			requireThat(responses, "responses").isNotNull();
			requireThat(effects, "effects").isNotNull();
			requireThat(windows, "windows").isNotNull();
			requireThat(promptSources, "promptSources").isNotBlank();
			responses = responses.stream().map(List::copyOf).toList();
			windows = List.copyOf(windows);
			toolCalls = List.copyOf(toolCalls);
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
