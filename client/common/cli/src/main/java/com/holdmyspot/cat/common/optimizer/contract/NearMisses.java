package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The replies that the contract author wrote for one judged assertion so that a candidate development judge can be
 * compared with the frozen judge: replies that only just satisfy the assertion and replies that only just fail it.
 * <p>
 * A reply may contain {@code {{variable}}} placeholders and {@code {{expected_outcome}}}, which a test case's values
 * replace.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param assertionId the identifier of the judged assertion
 * @param satisfying  the replies that only just satisfy the assertion
 * @param failing     the replies that only just fail the assertion
 */
public record NearMisses(String assertionId, List<String> satisfying, List<String> failing)
{
	/**
	 * The number of replies that only just satisfy an assertion, and the number that only just fail it.
	 */
	public static final int REPLIES_PER_SIDE = 2;

	/**
	 * One near-miss reply with a test case's values in place of its placeholders.
	 *
	 * @param key         identifies the reply within its test: the assertion, the side and the reply's position
	 * @param assertionId the identifier of the judged assertion
	 * @param satisfying  {@code true} if the reply only just satisfies the assertion, {@code false} if it only just
	 *                    fails it
	 * @param text        the reply
	 */
	public record Reply(String key, String assertionId, boolean satisfying, String text)
	{
		/**
		 * Creates a reply.
		 *
		 * @param key         identifies the reply within its test
		 * @param assertionId the identifier of the judged assertion
		 * @param satisfying  {@code true} if the reply only just satisfies the assertion
		 * @param text        the reply
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code text} is blank
		 */
		public Reply
		{
			requireThat(key, "key").isNotBlank();
			requireThat(assertionId, "assertionId").isNotBlank();
			requireThat(text, "text").isNotBlank();
		}
	}

	/**
	 * Creates the near misses of an assertion.
	 *
	 * @param assertionId the identifier of the judged assertion
	 * @param satisfying  the replies that only just satisfy it
	 * @param failing     the replies that only just fail it
	 * @throws NullPointerException     if any argument or element is null
	 * @throws IllegalArgumentException if {@code assertionId} is not snake case, a side does not hold
	 *                                  {@value #REPLIES_PER_SIDE} replies, a reply is blank, or the two sides hold
	 *                                  the same reply
	 */
	public NearMisses
	{
		requireThat(assertionId, "assertionId").matches(Identifier.SNAKE_CASE);
		requireThat(satisfying, "satisfying").isNotNull().size().isEqualTo(REPLIES_PER_SIDE);
		requireThat(failing, "failing").isNotNull().size().isEqualTo(REPLIES_PER_SIDE);
		satisfying = List.copyOf(satisfying);
		failing = List.copyOf(failing);
		for (String reply : satisfying)
			requireThat(reply, "reply").isNotBlank();
		for (String reply : failing)
		{
			requireThat(reply, "reply").isNotBlank();
			requireThat(satisfying, "satisfying").doesNotContain(reply);
		}
	}

	/**
	 * Returns the replies with a test case's values in place of their placeholders.
	 *
	 * @param textByName the text of each placeholder that a reply may name
	 * @return the satisfying replies, then the failing ones
	 * @throws NullPointerException if {@code textByName} is null
	 */
	public List<Reply> replies(Map<String, String> textByName)
	{
		requireThat(textByName, "textByName").isNotNull();
		List<Reply> replies = new ArrayList<>();
		for (int i = 0; i < satisfying.size(); ++i)
		{
			replies.add(new Reply(assertionId + "/satisfying/" + (i + 1), assertionId, true,
				Placeholders.fillScenario(satisfying.get(i), textByName)));
		}
		for (int i = 0; i < failing.size(); ++i)
		{
			replies.add(new Reply(assertionId + "/failing/" + (i + 1), assertionId, false,
				Placeholders.fillScenario(failing.get(i), textByName)));
		}
		return replies;
	}
}
