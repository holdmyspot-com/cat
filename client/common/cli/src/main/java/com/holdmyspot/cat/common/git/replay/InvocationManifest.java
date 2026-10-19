package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates and reports the manifest path owned by one topic-replay command invocation.
 */
final class InvocationManifest
{
	private final Path temporaryRoot;

	/**
	 * Creates an invocation-manifest path owner.
	 *
	 * @param temporaryRoot the CLI-owned root for invocation-local replay state
	 */
	InvocationManifest(Path temporaryRoot)
	{
		this.temporaryRoot = temporaryRoot;
	}

	/**
	 * Creates the manifest path for one invocation-local replay directory.
	 *
	 * @return the new manifest path
	 * @throws IOException if the invocation directory cannot be created
	 */
	Path create() throws IOException
	{
		Files.createDirectories(temporaryRoot);
		return Files.createTempDirectory(temporaryRoot, "topic-replay-").resolve("manifest.json");
	}

	/**
	 * Prints the retained invocation paths required by later replay commands.
	 *
	 * @param manifest the invocation manifest
	 */
	static void print(Path manifest)
	{
		System.out.print("replay_directory=" + manifest.getParent() + "\n");
		System.out.print("manifest=" + manifest + "\n");
	}
}
