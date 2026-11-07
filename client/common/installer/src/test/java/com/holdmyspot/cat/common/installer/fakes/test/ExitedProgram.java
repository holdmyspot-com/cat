package com.holdmyspot.cat.common.installer.fakes.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stands in for the process of a harness program that has already exited: its combined output is the answer that a
 * test scripts from the harness program's recorded or documented behavior, and its standard input discards what it
 * receives.
 */
public final class ExitedProgram extends Process
{
	/**
	 * The exit status and combined output.
	 */
	private final CommandResult answer;
	/**
	 * The combined output.
	 */
	private final InputStream output;

	/**
	 * Creates the process.
	 *
	 * @param answer the exit status and combined output
	 * @throws NullPointerException if {@code answer} is null
	 */
	public ExitedProgram(CommandResult answer)
	{
		this.answer = answer;
		this.output = new ByteArrayInputStream(answer.output().getBytes(UTF_8));
	}

	@Override
	public OutputStream getOutputStream()
	{
		return OutputStream.nullOutputStream();
	}

	@Override
	public InputStream getInputStream()
	{
		return output;
	}

	@Override
	public InputStream getErrorStream()
	{
		return InputStream.nullInputStream();
	}

	@Override
	public int waitFor()
	{
		return answer.exitCode();
	}

	@Override
	public int exitValue()
	{
		return answer.exitCode();
	}

	@Override
	public void destroy()
	{
	}
}
