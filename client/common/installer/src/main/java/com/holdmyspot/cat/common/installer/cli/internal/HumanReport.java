package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
	 * @param home    the harness home that the result's permission entries lie in, or empty when the result lists none
	 * @return the report, without a final line separator
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String render(ObjectNode result, Optional<String> product, Optional<Path> home)
	{
		requireThat(result, "result").isNotNull();
		requireThat(product, "product").isNotNull();
		requireThat(home, "home").isNotNull();
		Result kind = Result.valueOf(result.path("result").stringValue().toUpperCase(Locale.ROOT));
		List<String> sections = new ArrayList<>();
		if (kind.exitStatus() == 0)
			addSuccess(kind, result, product.orElse(result.path("harness").stringValue()), home, sections);
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
	 * @param home     the harness home that the permission entries lie in
	 * @param sections receives the sections
	 */
	private static void addSuccess(Result kind, ObjectNode result, String product, Optional<Path> home,
		List<String> sections)
	{
		String version = result.path("version").stringValue();
		String installed = "CAT is installed for " + product + ".";
		if (!version.isEmpty())
			installed = "CAT " + version + " is installed for " + product + ".";
		String restart = "Restart " + product + " for changes to take effect.";
		String headline;
		switch (kind)
		{
			case INSTALLED -> headline = installed + System.lineSeparator() + restart;
			case UPDATED ->
			{
				String previous = result.path("previous_version").stringValue("");
				String from = "";
				if (!previous.isEmpty())
					from = " from version " + previous;
				String to = "";
				if (!version.isEmpty())
					to = " to version " + version;
				headline = "CAT is updated for " + product + from + to + "." + System.lineSeparator() + restart;
			}
			case UP_TO_DATE ->
			{
				headline = "CAT " + version + " is already installed for " + product + " and up to date.";
				if (version.isEmpty())
					headline = "CAT is already installed for " + product + " and up to date.";
			}
			case UNINSTALLED -> headline = "CAT is removed from " + product + "." + System.lineSeparator() + restart;
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
		JsonNode permissions = result.path("permissions");
		if (permissions.isObject() && !permissions.isEmpty())
			sections.add(entries(permissions, home));
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
	 * Lists the permission and sandbox entries that install added.
	 *
	 * @param permissions the entries by the file and the setting that hold them
	 * @param home        the harness home that the files lie in
	 * @return the section
	 */
	private static String entries(JsonNode permissions, Optional<Path> home)
	{
		String lineSeparator = System.lineSeparator();
		StringBuilder section = new StringBuilder("CAT added these permission and sandbox entries. Uninstalling " +
			"CAT removes them:");
		for (String key : permissions.propertyNames())
		{
			String file = key;
			String setting = "";
			int separator = key.indexOf('#');
			if (separator >= 0)
			{
				file = key.substring(0, separator);
				setting = " (" + key.substring(separator + 1) + ")";
			}
			if (home.isPresent())
				file = home.get().resolve(file).toString();
			section.append(lineSeparator).append("  ").append(file).append(setting);
			for (JsonNode entry : permissions.path(key))
				section.append(lineSeparator).append("    ").append(entry.stringValue());
		}
		return section.toString();
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
}
