package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryEffect.Items;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Describes an installer result to the person who runs the installer in a terminal: what happened, what changed in
 * their configuration and what to do next.
 */
public final class HumanReport
{
	/**
	 * The first sentences of a success message that the report's headline says already.
	 */
	private static final List<String> HEADLINE_SENTENCES = List.of("CAT was installed.", "CAT was updated.",
		"CAT is up to date.", "CAT was removed from this harness.", "The installation state was read.");

	/**
	 * Prevents instantiation.
	 */
	private HumanReport()
	{
	}

	/**
	 * Renders a result.
	 *
	 * @param result  the result object that the installer prints as JSON when its standard output is not a terminal
	 * @param product the name of the result's harness, or empty when the installer does not know the harness
	 * @param entries the permission and sandbox entries that a success lists, or null when it lists none
	 * @return the report, without a final line separator
	 * @throws NullPointerException if {@code result} or {@code product} are null
	 */
	public static String render(ObjectNode result, Optional<String> product, ReportedEntries entries)
	{
		requireThat(result, "result").isNotNull();
		requireThat(product, "product").isNotNull();
		Result kind = Result.valueOf(result.path("result").stringValue().toUpperCase(Locale.ROOT));
		List<String> sections = new ArrayList<>();
		if (kind.exitStatus() == 0)
			addSuccess(kind, result, product.orElse(result.path("harness").stringValue()), entries, sections);
		else
			addFailure(kind, result, product, sections);
		return String.join(System.lineSeparator() + System.lineSeparator(), sections);
	}

	/**
	 * Adds the sections of a success.
	 *
	 * @param kind     the result
	 * @param result   the result object
	 * @param product  the name of the result's harness
	 * @param entries  the permission and sandbox entries that the success lists, or null when it lists none
	 * @param sections receives the sections
	 */
	private static void addSuccess(Result kind, ObjectNode result, String product, ReportedEntries entries,
		List<String> sections)
	{
		String version = result.path("version").stringValue();
		String installed = "CAT is installed for " + product + ".";
		if (!version.isEmpty())
			installed = "CAT " + version + " is installed for " + product + ".";
		String restart = "Restart " + product + " for the changes to take effect.";
		String headline;
		switch (kind)
		{
			case INSTALLED -> headline = installed + " " + restart;
			case UPDATED ->
			{
				String previous = result.path("previous_version").stringValue("");
				String from = "";
				if (!previous.isEmpty())
					from = " from version " + previous;
				String to = "";
				if (!version.isEmpty())
					to = " to version " + version;
				headline = "CAT is updated for " + product + from + to + ". " + restart;
			}
			case UP_TO_DATE ->
			{
				headline = "CAT " + version + " is already installed for " + product + " and up to date.";
				if (version.isEmpty())
					headline = "CAT is already installed for " + product + " and up to date.";
			}
			case UNINSTALLED -> headline = "CAT is removed from " + product + ". " + restart;
			default ->
			{
				// STATUS
				headline = "CAT is not installed for " + product + ".";
				if (result.path("installed").booleanValue(false))
				{
					headline = installed;
					if (!result.path("consistent").booleanValue(true))
					{
						headline += System.lineSeparator() + "The installation is inconsistent; run the install " +
							"command again to repair it.";
					}
				}
			}
		}
		sections.add(headline);
		String notes = withoutHeadline(result);
		if (!notes.isEmpty())
			sections.add(notes);
		if (entries != null)
			entries(entries, sections);
	}

	/**
	 * Returns the message without the sentence that the headline says already.
	 *
	 * @param result the result object
	 * @return the remaining sentences, which may be empty
	 */
	private static String withoutHeadline(ObjectNode result)
	{
		String message = result.path("message").stringValue("");
		List<String> headlines = new ArrayList<>(HEADLINE_SENTENCES);
		headlines.add("CAT is installed for " + result.path("harness").stringValue("") + ".");
		for (String sentence : headlines)
		{
			if (message.startsWith(sentence))
				return message.substring(sentence.length()).strip();
		}
		return message;
	}

	/**
	 * Adds the sections that list the permission and sandbox entries: the files that hold them, a group for each
	 * effect of each setting, under the effect's heading and the setting, and the meaning of the abbreviations.
	 * Without {@link ReportedEntries#exact()}, a group lists the installer operations, paths or names that its entries
	 * name, and a last section says how to list the entries exactly.
	 *
	 * @param entries  the entries
	 * @param sections receives the sections; nothing when no group holds an entry
	 */
	private static void entries(ReportedEntries entries, List<String> sections)
	{
		List<EntryGroup> groups = new ArrayList<>();
		for (EntryGroup group : entries.groups())
		{
			if (!lines(group, entries.exact(), new Abbreviation(entries.userHome())).isEmpty())
				groups.add(group);
		}
		if (groups.isEmpty())
			return;
		groups.sort(Comparator.comparing(EntryGroup::effect).thenComparing(EntryGroup::file).
			thenComparing(EntryGroup::setting));

		Abbreviation abbreviation = new Abbreviation(entries.userHome());
		Set<Path> files = new LinkedHashSet<>();
		for (EntryGroup group : groups)
			files.add(group.file());
		List<String> fileNames = new ArrayList<>();
		for (Path file : files)
			fileNames.add(abbreviation.of(entries.harnessHome().resolve(file)));
		StringJoiner section = new StringJoiner(System.lineSeparator());
		section.add("CAT changed " + sentenceList(fileNames) + ". Uninstalling CAT undoes these changes.");
		int width = 0;
		for (EntryGroup group : groups)
			width = Math.max(width, group.effect().heading().length());
		for (EntryGroup group : groups)
		{
			section.add("").add("  " + heading(group, width, files.size() > 1));
			for (String line : lines(group, entries.exact(), abbreviation))
				section.add("    " + line);
		}

		List<String> names = new ArrayList<>();
		List<String> values = new ArrayList<>();
		if (!entries.exact())
		{
			names.add("installer");
			values.add(abbreviation.of(entries.installer()));
		}
		if (abbreviation.used)
		{
			names.addFirst("~");
			values.addFirst(entries.userHome().toString());
		}
		if (!names.isEmpty())
			section.add("").add(legend(names, values));
		sections.add(section.toString());
		if (!entries.exact())
			sections.add("Run installer status --verbose to see the exact entries.");
	}

	/**
	 * Returns the line that introduces a group.
	 *
	 * @param group     the group
	 * @param width     the length of the longest heading of the report's groups
	 * @param manyFiles {@code true} if the report lists the entries of more than one file, so that the line names the
	 *                  group's file
	 * @return the group's heading, followed by its file and setting in parentheses, aligned with the other groups'
	 */
	private static String heading(EntryGroup group, int width, boolean manyFiles)
	{
		String location = group.setting();
		if (manyFiles)
		{
			location = group.file().toString();
			if (!group.setting().isEmpty())
				location += ": " + group.setting();
		}
		String heading = group.effect().heading();
		if (location.isEmpty())
			return heading;
		// Three spaces separate the longest heading from its location
		return heading + " ".repeat(width - heading.length() + 3) + "(" + location + ")";
	}

	/**
	 * Returns the lines that list a group's entries.
	 *
	 * @param group        the group
	 * @param exact        {@code true} to list each entry as the configuration holds it
	 * @param abbreviation names the paths that the group lists
	 * @return the lines, empty when the group lists nothing: installer operations on one line, separated by commas,
	 * 	and each other item on its own line, in alphabetical order without repeats
	 */
	private static List<String> lines(EntryGroup group, boolean exact, Abbreviation abbreviation)
	{
		if (exact)
		{
			List<String> lines = new ArrayList<>();
			for (String entry : group.entries())
				entry.lines().forEach(lines::add);
			return lines;
		}
		Items kind = group.effect().items();
		Set<String> items = new TreeSet<>();
		for (String item : group.items())
		{
			switch (kind)
			{
				case OPERATIONS -> items.add("installer " + item);
				case PATHS -> items.add(abbreviation.of(Path.of(item)));
				case NAMES -> items.add(item);
			}
		}
		if (items.isEmpty())
			return List.of();
		if (kind == Items.OPERATIONS)
			return List.of(String.join(", ", items));
		return List.copyOf(items);
	}

	/**
	 * Joins names as a sentence lists them.
	 *
	 * @param names one or more names
	 * @return the names, separated by commas, and by {@code and} before the last
	 */
	private static String sentenceList(List<String> names)
	{
		if (names.size() == 1)
			return names.getFirst();
		return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
	}

	/**
	 * Returns the lines that say what the report's abbreviations stand for.
	 *
	 * @param names  the abbreviations
	 * @param values what each abbreviation stands for
	 * @return the lines, with the values aligned
	 */
	private static String legend(List<String> names, List<String> values)
	{
		int width = 0;
		for (String name : names)
			width = Math.max(width, name.length());
		StringJoiner legend = new StringJoiner(System.lineSeparator());
		String prefix = "  where ";
		for (int i = 0; i < names.size(); ++i)
		{
			String name = names.get(i);
			legend.add(prefix + name + " ".repeat(width - name.length()) + " = " + values.get(i));
			prefix = " ".repeat(prefix.length());
		}
		return legend.toString();
	}

	/**
	 * Adds the sections of a result that is not a success.
	 *
	 * @param kind     the result
	 * @param result   the result object
	 * @param product  the name of the result's harness, if known
	 * @param sections receives the sections
	 */
	private static void addFailure(Result kind, ObjectNode result, Optional<String> product, List<String> sections)
	{
		String forProduct = product.map(name -> " for " + name).orElse("");
		String lineSeparator = System.lineSeparator();
		String headline = switch (kind)
		{
			case USAGE -> "The installer did not run: the command is not valid.";
			case REFUSED -> "The installer refused the request" + forProduct + ".";
			case DOWNLOAD_FAILED -> "The installer could not download CAT" + forProduct + ".";
			case UNINSTALL_INCOMPLETE -> "The uninstall of CAT" + forProduct + " did not finish.";
			case LOCKED -> "Another CAT operation" + forProduct + " is running.";
			case NOT_WRITABLE -> "The installer cannot write a file that it needs" + forProduct + ".";
			default -> "The installer stopped with an error" + forProduct + ".";
		};
		StringBuilder cause = new StringBuilder(headline).append(lineSeparator).append(result.path("message").
			stringValue(""));
		JsonNode paths = result.path("paths");
		if (paths.isArray() && !paths.isEmpty())
		{
			cause.append(lineSeparator).append(lineSeparator).append("Files involved:");
			for (JsonNode path : paths)
				cause.append(lineSeparator).append("  ").append(path.stringValue());
		}
		sections.add(cause.toString());
		sections.add("Next step: " + nextStep(kind, result));
	}

	/**
	 * Returns what the person does next.
	 *
	 * @param kind   the result, which is not a success
	 * @param result the result object
	 * @return the instruction
	 */
	private static String nextStep(Result kind, ObjectNode result)
	{
		return switch (kind)
		{
			case USAGE -> "correct the command as the usage above shows, then run it again.";
			case REFUSED ->
			{
				String command = result.path("consent_command").stringValue("");
				if (command.isEmpty())
					yield "do what the message above asks, then run the same command again.";
				yield "to allow the change, run this command:" + System.lineSeparator() + "  " + command;
			}
			case DOWNLOAD_FAILED -> "check your network connection and proxy settings, then run the same command " +
				"again.";
			case UNINSTALL_INCOMPLETE -> "run the same uninstall command again; it continues where it stopped.";
			case LOCKED -> "wait for the other CAT operation to finish, then run the same command again.";
			case NOT_WRITABLE -> "make the files above writable by your user, then run the same command again.";
			default -> "run the same command again, which repairs what the failure left.";
		};
	}

	/**
	 * Writes paths in the user's home directory relative to {@code ~}, and remembers whether it did.
	 */
	private static final class Abbreviation
	{
		private final Path userHome;
		/**
		 * Whether a path was written relative to {@code ~}.
		 */
		private boolean used;

		/**
		 * Creates an abbreviation.
		 *
		 * @param userHome the user's home directory
		 */
		private Abbreviation(Path userHome)
		{
			this.userHome = userHome;
		}

		/**
		 * Returns how the report writes a path.
		 *
		 * @param path an absolute path
		 * @return {@code ~}, followed by the path relative to the user's home directory when the path lies in it;
		 * 	otherwise the path
		 */
		private String of(Path path)
		{
			if (!path.startsWith(userHome))
				return path.toString();
			used = true;
			if (path.equals(userHome))
				return "~";
			return "~" + userHome.getFileSystem().getSeparator() + userHome.relativize(path);
		}
	}
}
