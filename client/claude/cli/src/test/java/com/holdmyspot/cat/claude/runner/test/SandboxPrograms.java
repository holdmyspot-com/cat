package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.PortableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes the programs that a launch's pre-launch checks look for, for a test whose subject is not those checks: a
 * folder whose {@code bwrap} and {@code socat} the test owns, so that the launch passes them whatever the host has
 * installed. The test-owned {@code bwrap} exits 0, as one that can start a sandbox does.
 */
public final class SandboxPrograms
{
	/**
	 * Prevents construction.
	 */
	private SandboxPrograms()
	{
	}

	/**
	 * Creates a folder that holds an executable {@code bwrap} and {@code socat}.
	 *
	 * @param parent the folder that receives it
	 * @return the folder, as a {@code PATH} that holds only it
	 * @throws IOException if the folder or a program cannot be written
	 */
	public static String path(Path parent) throws IOException
	{
		Path folder = Files.createDirectory(parent.resolve("sandbox-programs"));
		for (String name : new String[]{"bwrap", "socat"})
		{
			Path program = Files.writeString(folder.resolve(name), "#!/bin/sh\nexit 0\n");
			if (PortableFiles.hasModes(folder))
				Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		return folder.toString();
	}
}
