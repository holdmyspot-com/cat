package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * The channel to the runner of the nested session whose session runs a command. The runner lives as long as the nested
 * session, and removes the nested session's responses with it.
 *
 * @param requests the nested session's requests directory
 */
public record NestedSessionChannel(Path requests) implements WorktreeRequestClient.Channel
{
	/**
	 * Creates a channel.
	 *
	 * @param requests the nested session's requests directory
	 * @throws NullPointerException if {@code requests} is null
	 */
	public NestedSessionChannel
	{
		requireThat(requests, "requests").isNotNull();
	}

	@Override
	public boolean isServed()
	{
		return true;
	}

	@Override
	public boolean removesResponses()
	{
		return false;
	}

	@Override
	public void close()
	{
	}
}
