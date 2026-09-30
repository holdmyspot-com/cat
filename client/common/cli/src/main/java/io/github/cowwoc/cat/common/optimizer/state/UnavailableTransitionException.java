package io.github.cowwoc.cat.common.optimizer.state;

import java.io.IOException;

/**
 * The named producer schema is not yet integrated with campaign transitions.
 */
public final class UnavailableTransitionException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Identifies the unavailable transition kind and its producer.
	 *
	 * @param kind requested transition kind
	 * @param producer responsible producer component
	 */
	public UnavailableTransitionException(TransitionKind kind, String producer)
	{
		super("Transition " + kind + " is unavailable until " + producer + " producer integration");
	}
}
