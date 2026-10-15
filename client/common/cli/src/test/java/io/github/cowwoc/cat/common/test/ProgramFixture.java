package io.github.cowwoc.cat.common.test;

import io.github.cowwoc.cat.common.PortableFiles;

import java.nio.file.Path;

/**
 * Names and contents of a fixture program in the form that the platform runs. A file system with POSIX modes runs a
 * shell script whose mode allows it, and Windows runs a batch file by its extension.
 */
public final class ProgramFixture
{
	/**
	 * Whether the default file system has POSIX modes.
	 */
	private static final boolean HAS_MODES = PortableFiles.hasModes(Path.of(""));
	/**
	 * The extension of a program: {@code .sh} on a file system with POSIX modes, {@code .cmd} otherwise.
	 */
	private static final String EXTENSION;
	/**
	 * The text of a program that does nothing.
	 */
	public static final String TEXT;

	static
	{
		if (HAS_MODES)
		{
			EXTENSION = ".sh";
			TEXT = "#!/bin/sh\n";
		}
		else
		{
			EXTENSION = ".cmd";
			TEXT = "@echo off\n";
		}
	}

	/**
	 * Returns the name of a program.
	 *
	 * @param stem the name without its extension
	 * @return the name with the platform's program extension
	 */
	public static String name(String stem)
	{
		return stem + EXTENSION;
	}

	/**
	 * Prevents instantiation.
	 */
	private ProgramFixture()
	{
	}
}
