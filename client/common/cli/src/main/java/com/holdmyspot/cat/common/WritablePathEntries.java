package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Finds the folders of a search path that a harness's sandbox lets its commands write.
 * <p>
 * A command that can write a folder of the search path can plant a program there that a process outside the sandbox
 * then runs.
 */
public final class WritablePathEntries
{
	/**
	 * Prevents instantiation.
	 */
	private WritablePathEntries()
	{
	}

	/**
	 * A folder of the search path that the sandbox lets its commands write.
	 *
	 * @param folder the absolute folder, as the search path names it
	 * @param grant  the grant that covers the folder
	 */
	public record Writable(Path folder, WriteGrant grant)
	{
		/**
		 * Creates the entry.
		 *
		 * @param folder the absolute folder, as the search path names it
		 * @param grant  the grant that covers the folder
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code folder} is relative
		 */
		public Writable
		{
			requireThat(folder, "folder").isAbsolute();
			requireThat(grant, "grant").isNotNull();
		}
	}

	/**
	 * Returns the folders of a search path that the sandbox lets its commands write: a folder is writable when a grant
	 * covers it or a folder above it, and no denial covers it. An empty or relative entry names the project folder or a
	 * folder below it, because the session's commands run there. Paths compare by the files that they reach.
	 *
	 * @param searchPath       the search path, in the format of the platform's {@code PATH}
	 * @param projectDirectory the absolute project folder
	 * @param writes           where the sandbox lets commands write
	 * @return the writable folders, in the order of the search path, each once
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code projectDirectory} is relative
	 * @throws IOException              if a symbolic link in a path cannot be read
	 */
	public static List<Writable> find(String searchPath, Path projectDirectory, SandboxWrites writes)
		throws IOException
	{
		requireThat(searchPath, "searchPath").isNotNull();
		requireThat(projectDirectory, "projectDirectory").isAbsolute();
		requireThat(writes, "writes").isNotNull();
		if (searchPath.isEmpty() || writes.grants().isEmpty())
			return List.of();
		List<Path> grantRoots = new ArrayList<>();
		for (WriteGrant grant : writes.grants())
			grantRoots.add(RealPaths.resolve(grant.path()));
		List<Path> denials = RealPaths.resolveAll(writes.denials());
		List<Writable> writable = new ArrayList<>();
		Set<Path> seen = new HashSet<>();
		for (String entry : searchPath.split(File.pathSeparator, -1))
		{
			Optional<Path> folder = folder(entry, projectDirectory);
			if (folder.isEmpty())
				continue;
			Path real = RealPaths.resolve(folder.get());
			if (!seen.add(real) || RealPaths.isInsideAny(real, denials))
				continue;
			for (int i = 0; i < grantRoots.size(); ++i)
			{
				if (real.startsWith(grantRoots.get(i)))
				{
					writable.add(new Writable(folder.get(), writes.grants().get(i)));
					break;
				}
			}
		}
		return List.copyOf(writable);
	}

	/**
	 * Returns the search path of a process's environment.
	 *
	 * @param environment the environment variables
	 * @return the value of {@code PATH}, which Windows spells in any case, or an empty string if there is none
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static String searchPath(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String value = environment.get("PATH");
		if (value == null && Platform.current().isWindows())
		{
			for (Map.Entry<String, String> entry : environment.entrySet())
			{
				if (entry.getKey().equalsIgnoreCase("PATH"))
					return entry.getValue();
			}
		}
		if (value == null)
			return "";
		return value;
	}

	/**
	 * Returns the notice that tells the user which folders of the search path the sandbox lets commands write.
	 *
	 * @param harnessName the name of the harness whose sandbox it is, as the user knows it
	 * @param writable    the writable folders, which are not empty
	 * @return the notice
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code writable} is empty
	 */
	public static String notice(String harnessName, List<Writable> writable)
	{
		requireThat(harnessName, "harnessName").isNotBlank();
		requireThat(writable, "writable").isNotEmpty();
		String subject = "a folder";
		if (writable.size() != 1)
			subject = "folders";
		StringJoiner notice = new StringJoiner("\n");
		notice.add("CAT: the sandbox of this " + harnessName + " session lets its commands write " + subject +
			" on PATH, so a command could plant a program there that CAT or another tool outside the sandbox later " +
			"runs:");
		for (Writable entry : writable)
			notice.add("- " + entry.folder() + ", granted by " + entry.grant().setting());
		notice.add("Remove the folder from PATH, or take the grant out of the setting, to close this.");
		return notice.toString();
	}

	/**
	 * Returns the folder that an entry of a search path names.
	 *
	 * @param entry            the entry, which may be empty or quoted
	 * @param projectDirectory the absolute project folder, which a relative or empty entry resolves against
	 * @return the absolute, normalized folder, or empty if the entry is not a path
	 */
	private static Optional<Path> folder(String entry, Path projectDirectory)
	{
		String text = entry.strip();
		if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
			text = text.substring(1, text.length() - 1);
		try
		{
			return Optional.of(projectDirectory.resolve(text).normalize());
		}
		catch (InvalidPathException _)
		{
			return Optional.empty();
		}
	}
}
