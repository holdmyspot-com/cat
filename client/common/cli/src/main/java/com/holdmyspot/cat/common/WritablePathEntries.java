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
	 * The sentence that states which commands the check covers.
	 */
	public static final String COVERAGE = "This check covers only the commands that run inside the sandbox: a command " +
		"that runs with the sandbox disabled or excluded from it, and the harness's own file tools, are not covered.";

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
			// A grant that is certain names the folder in preference to one that is only possible
			int covering = -1;
			for (int i = 0; i < grantRoots.size(); ++i)
			{
				if (!real.startsWith(grantRoots.get(i)))
					continue;
				if (!writes.grants().get(i).possible())
				{
					covering = i;
					break;
				}
				if (covering < 0)
					covering = i;
			}
			if (covering >= 0)
				writable.add(new Writable(folder.get(), writes.grants().get(covering)));
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
	 * Returns the notice that tells the user which folders of the search path the sandbox lets commands write, or may
	 * let them write, and what CAT could not read. It states which commands the check covers.
	 *
	 * @param harnessName the name of the harness whose sandbox it is, as the user knows it
	 * @param writable    the writable folders
	 * @param problems    what CAT could not read, each as a sentence
	 * @return the notice
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code writable} and {@code problems} are both empty
	 */
	public static String notice(String harnessName, List<Writable> writable, List<String> problems)
	{
		requireThat(harnessName, "harnessName").isNotBlank();
		requireThat(writable, "writable").isNotNull();
		requireThat(problems, "problems").isNotNull();
		if (writable.isEmpty() && problems.isEmpty())
			throw new IllegalArgumentException("writable and problems must not both be empty");
		StringJoiner notice = new StringJoiner("\n");
		if (writable.isEmpty())
		{
			notice.add("CAT: it could not check the folders on PATH against the sandbox of this " + harnessName +
				" session:");
		}
		else
		{
			String subject = "a folder";
			if (writable.size() != 1)
				subject = "folders";
			notice.add("CAT: the sandbox of this " + harnessName + " session " + verb(writable) + " its commands write " +
				subject + " on PATH, so a command could plant a program there that CAT or another tool outside the " +
				"sandbox later runs:");
			for (Writable entry : writable)
			{
				if (entry.grant().possible())
				{
					notice.add("- " + entry.folder() + ", which may be writable, because " + entry.grant().setting() +
						" may grant it and CAT cannot tell whether the sandbox applies it");
				}
				else
					notice.add("- " + entry.folder() + ", granted by " + entry.grant().setting());
			}
		}
		if (!problems.isEmpty())
		{
			if (!writable.isEmpty())
				notice.add("CAT could not read everything that can grant a write, so other folders may be writable:");
			for (String problem : problems)
				notice.add("- " + problem);
		}
		if (!writable.isEmpty())
			notice.add("Remove the folder from PATH, or take the grant out of the setting, to close this.");
		notice.add(COVERAGE);
		return notice.toString();
	}

	/**
	 * Returns the verb that states how the sandbox treats the folders.
	 *
	 * @param writable the writable folders, which are not empty
	 * @return {@code "lets"} if the sandbox certainly lets its commands write all of them, {@code "may let"} if it may
	 *   let them write all of them, otherwise {@code "lets or may let"}
	 */
	private static String verb(List<Writable> writable)
	{
		boolean anyPossible = false;
		boolean anyCertain = false;
		for (Writable entry : writable)
		{
			if (entry.grant().possible())
				anyPossible = true;
			else
				anyCertain = true;
		}
		if (!anyPossible)
			return "lets";
		if (!anyCertain)
			return "may let";
		return "lets or may let";
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
