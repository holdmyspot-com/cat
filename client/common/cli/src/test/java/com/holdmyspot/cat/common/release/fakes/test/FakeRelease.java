package com.holdmyspot.cat.common.release.fakes.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.runner.InstalledRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.StringJoiner;

/**
 * Builds minimal CAT plugins and Java runtimes, as a release holds them, for tests of the code that finds and checks
 * them.
 */
public final class FakeRelease
{
	/**
	 * Prevents instantiation.
	 */
	private FakeRelease()
	{
	}

	/**
	 * The Java feature release of the runtimes that {@link #runtime} creates, which the requirement of the artifacts that
	 * {@link #artifact} creates names.
	 */
	public static final int JAVA_FEATURE = 27;

	/**
	 * Creates a minimal CAT artifact as a release archive holds it: its version file, manifest, {@code plugin-info}
	 * and runner launchers, hook launcher, and a runtime requirement of Java {@link #JAVA_FEATURE} with
	 * {@code java.base}, in the form that an artifact built for a platform holds them. It holds no runtime binding.
	 *
	 * @param directory the artifact's directory, which this method creates
	 * @param manifest  the manifest's path relative to the artifact
	 * @param runner    the runner launcher's name
	 * @param version   the CAT version that the artifact's version file states
	 * @param platform  the platform that the artifact is built for
	 * @return {@code directory}
	 * @throws IOException if the files cannot be written
	 */
	public static Path artifact(Path directory, String manifest, String runner, String version, Platform platform)
		throws IOException
	{
		String extension = "";
		if (platform.isWindows())
			extension = ".cmd";
		write(directory.resolve("bin/VERSION"), version + "\n", false);
		write(directory.resolve(manifest), "{}\n", false);
		write(directory.resolve("bin/plugin-info" + extension), "#!/bin/sh\n", true);
		write(directory.resolve("bin/" + runner + extension), "#!/bin/sh\n", true);
		write(directory.resolve("bin/session-start" + extension), "#!/bin/sh\n", true);
		requirement(directory, JAVA_FEATURE, "java.base");
		return directory;
	}

	/**
	 * Replaces the runtime requirement of an artifact.
	 *
	 * @param artifact the artifact's directory
	 * @param feature  the required Java feature release
	 * @param modules  the required modules
	 * @throws IOException if the requirement cannot be written
	 */
	public static void requirement(Path artifact, int feature, String... modules) throws IOException
	{
		write(artifact.resolve("bin/runtime-requirement.json"), "{\"format\": 1, \"java_feature\": " + feature +
			", \"modules\": " + jsonArray(modules) + "}\n", false);
	}

	/**
	 * Creates a minimal installation Java runtime of Java {@link #JAVA_FEATURE}, holding the modules {@code java.base}
	 * and {@code java.net.http}: its {@code java} program, its description and its manifest, which also lists the files
	 * that the directory already holds. A directory that already holds a runtime is returned unchanged.
	 *
	 * @param directory the runtime's directory
	 * @param platform  the platform that the runtime is built for
	 * @return the runtime's absolute normalized directory
	 * @throws IOException if the runtime cannot be written
	 */
	public static Path runtime(Path directory, Platform platform) throws IOException
	{
		Path result = directory.toAbsolutePath().normalize();
		if (Files.exists(result.resolve(InstalledRuntime.MANIFEST)))
			return result;
		String java = "bin/java";
		if (platform.isWindows())
			java = "bin/java.exe";
		write(result.resolve(java), "#!/bin/sh\n", true);
		write(result.resolve(InstalledRuntime.DESCRIPTION), "{\"format\": 1, \"java_version\": \"" + JAVA_FEATURE +
			".0.1\", \"java_feature\": " + JAVA_FEATURE + ", \"modules\": " + jsonArray("java.base",
			"java.net.http") + "}\n", false);
		InstalledRuntime.writeManifest(result);
		return result;
	}

	/**
	 * Returns strings as a JSON array.
	 *
	 * @param values the strings, which need no escaping
	 * @return the array
	 */
	private static String jsonArray(String... values)
	{
		StringJoiner joiner = new StringJoiner(", ", "[", "]");
		for (String value : values)
			joiner.add("\"" + value + "\"");
		return joiner.toString();
	}

	/**
	 * Writes a file, creating its parent directories.
	 *
	 * @param file       the file
	 * @param content    the file's content
	 * @param executable {@code true} to let every user run the file
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file, String content, boolean executable) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		String mode = "rw-r--r--";
		if (executable)
			mode = "rwxr-xr-x";
		PortableFiles.setMode(file, PosixFilePermissions.fromString(mode));
	}
}
