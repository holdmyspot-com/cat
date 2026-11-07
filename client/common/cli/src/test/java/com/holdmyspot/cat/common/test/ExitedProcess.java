package com.holdmyspot.cat.common.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ExecutableFiles;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stands in for a program that an {@link ExecutableFiles.ProcessLauncher} starts and that has already exited, so that
 * a test of a class that starts a program does not start it.
 * <p>
 * The process delivers its standard output and standard error as the JDK's {@link Process} does for the
 * {@link ProcessBuilder} that started it: through {@link #getInputStream()} and {@link #getErrorStream()} apart, or
 * both through {@link #getInputStream()}, the standard output first, when the builder redirects the error stream
 * there.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe, as the streams of a JDK process are not.
 */
public final class ExitedProcess extends Process
{
	private final int status;
	private final InputStream output;
	private final InputStream error;

	/**
	 * Creates a process that has exited.
	 *
	 * @param builder the builder that started the process
	 * @param status  the exit status
	 * @param stdout  what the program printed to its standard output
	 * @param stderr  what the program printed to its standard error
	 */
	public ExitedProcess(ProcessBuilder builder, int status, String stdout, String stderr)
	{
		this.status = status;
		if (builder.redirectErrorStream())
		{
			output = new ByteArrayInputStream((stdout + stderr).getBytes(UTF_8));
			error = InputStream.nullInputStream();
		}
		else
		{
			output = new ByteArrayInputStream(stdout.getBytes(UTF_8));
			error = new ByteArrayInputStream(stderr.getBytes(UTF_8));
		}
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
		return error;
	}

	@Override
	public int waitFor()
	{
		return status;
	}

	@Override
	public int exitValue()
	{
		return status;
	}

	@Override
	public void destroy()
	{
		// The process has already exited
	}
}
