package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The environment that a certification was measured in, worded for a user who knows none of CAT's terms.
 * <p>
 * Each line of the block shows conditions that the measurement identity records. A line whose condition the identity
 * does not record says {@code not recorded}, and a line whose condition the reporting session could not check ends
 * with {@code (not checked)}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param identity    the measurement conditions that the certification was measured under
 * @param cells       the identifiers of the cells of the certification scope, in cell order
 * @param promptFiles the certified prompt files, relative to the project, in the order that the block lists them
 * @param unchecked   the recorded conditions that the reporting session could not check
 */
public record CertifiedEnvironment(MeasurementIdentity identity, List<String> cells, List<Path> promptFiles,
	List<MeasurementCondition> unchecked)
{
	/**
	 * The text that stands for a condition that the measurement identity does not record.
	 */
	private static final String NOT_RECORDED = "not recorded";
	/**
	 * The text that ends the harness settings line: trials run in an isolated harness home that receives only these
	 * settings from the user's own configuration.
	 */
	private static final String USER_SETTINGS = ", with your connection settings and tool restrictions carried over";

	/**
	 * Creates an environment.
	 *
	 * @param identity    the measurement conditions that the certification was measured under
	 * @param cells       the identifiers of the cells of the certification scope, in cell order
	 * @param promptFiles the certified prompt files, relative to the project, in the order that the block lists them
	 * @param unchecked   the recorded conditions that the reporting session could not check
	 * @throws NullPointerException if any of the arguments are null or contain null
	 */
	public CertifiedEnvironment
	{
		requireThat(identity, "identity").isNotNull();
		requireThat(cells, "cells").isNotNull();
		requireThat(promptFiles, "promptFiles").isNotNull();
		requireThat(unchecked, "unchecked").isNotNull();
		cells = List.copyOf(cells);
		promptFiles = List.copyOf(promptFiles);
		unchecked = List.copyOf(unchecked);
	}

	/**
	 * Returns the environment of a certification record. The block lists the target before the other prompt files.
	 *
	 * @param record    the record
	 * @param unchecked the recorded conditions that the reporting session could not check
	 * @return the environment
	 * @throws NullPointerException if any of the arguments are null or contain null
	 */
	public static CertifiedEnvironment of(CertificationRecord record, List<MeasurementCondition> unchecked)
	{
		requireThat(record, "record").isNotNull();
		List<String> cells = new ArrayList<>();
		for (CertificationRecord.CellResult cell : record.cells())
			cells.add(cell.cell());
		return new CertifiedEnvironment(record.identity(), cells,
			targetFirst(record.target(), record.promptFiles().keySet()), unchecked);
	}

	/**
	 * Orders prompt files with the target first and the others in path order.
	 *
	 * @param target the campaign's target, relative to the project
	 * @param files  the prompt files, relative to the project
	 * @return the files, with the target first if it is one of them
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<Path> targetFirst(Path target, Set<Path> files)
	{
		requireThat(target, "target").isNotNull();
		requireThat(files, "files").isNotNull();
		List<Path> ordered = new ArrayList<>();
		if (files.contains(target))
			ordered.add(target);
		files.stream().filter(file -> !file.equals(target)).sorted().forEach(ordered::add);
		return ordered;
	}

	/**
	 * Returns the block's lines.
	 *
	 * @return the lines, beginning with {@code Certified for the following environment:} and an empty line
	 */
	public List<String> lines()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Certified for the following environment:");
		lines.add("");
		lines.add(checked(harness(), MeasurementCondition.PROVIDER, MeasurementCondition.HARNESS_VERSION));
		lines.add(checked("Plugins: " + plugins(), MeasurementCondition.TRIAL_PLUGINS,
			MeasurementCondition.PLUGIN_BASES, MeasurementCondition.PLUGIN_VERSIONS, MeasurementCondition.PLUGIN_BUILDS,
			MeasurementCondition.PLUGIN_MAPPINGS));
		if (cells.isEmpty())
		{
			// A record without cell results reports the campaign-wide model conditions once
			lines.add(checked("Model: " + model(null), MeasurementCondition.MODEL, MeasurementCondition.REASONING));
		}
		for (String cell : cells)
		{
			lines.add(checked("Model: " + model(cell), MeasurementCondition.MODEL, MeasurementCondition.REASONING));
		}
		lines.add(checked("Prompt files loaded: " + promptFileList(), MeasurementCondition.PROMPT_SOURCES));
		lines.add(checked("Harness settings: " + settings(), MeasurementCondition.LOADER_CONFIGURATION,
			MeasurementCondition.LOADER_FILES));
		lines.add(checked("Trial setup: " + trialSetup(), MeasurementCondition.SESSION_ENVIRONMENT_VERSION,
			MeasurementCondition.SESSION_ENVIRONMENT_RELEASE));
		lines.add(checked("Tools run directly: " + listed(MeasurementCondition.HOST_TOOLS),
			MeasurementCondition.HOST_TOOLS));
		lines.add(checked("Outside sources: " + listed(MeasurementCondition.EXTERNAL_SOURCES),
			MeasurementCondition.EXTERNAL_SOURCES));
		return lines;
	}

	/**
	 * Marks a line whose conditions the reporting session could not all check.
	 *
	 * @param line       the line
	 * @param conditions the conditions that the line shows
	 * @return the line, ending with {@code (not checked)} if the session could not check one of the conditions
	 */
	private String checked(String line, MeasurementCondition... conditions)
	{
		for (MeasurementCondition condition : conditions)
		{
			if (unchecked.contains(condition))
				return line + " (not checked)";
		}
		return line;
	}

	/**
	 * Names the harness and its version.
	 *
	 * @return the line, such as {@code Codex 0.160.0+41}
	 */
	private String harness()
	{
		String harness = identity.values().get(MeasurementCondition.PROVIDER);
		String name;
		if (harness == null)
			name = "Harness " + NOT_RECORDED;
		else
			name = Harness.fromCommandName(harness).map(Harness::productName).orElse(harness);
		String version = identity.values().get(MeasurementCondition.HARNESS_VERSION);
		if (version == null)
			return name + ", version " + NOT_RECORDED;
		return name + " " + versionNumber(version);
	}

	/**
	 * Returns the version number in a harness's version output.
	 *
	 * @param output the first line that {@code <harness> --version} printed, such as {@code codex-cli 0.160.0+41} or
	 *               {@code 2.1.286 (Claude Code)}
	 * @return the first word that starts with a digit, or {@code output} if no word does
	 */
	private static String versionNumber(String output)
	{
		for (String word : output.split(" "))
		{
			if (!word.isEmpty() && Character.isDigit(word.charAt(0)))
				return word;
		}
		return output;
	}

	/**
	 * Lists the trial plugins with their versions.
	 *
	 * @return each plugin's name and manifest version, marking one that the project builds, or {@code none}
	 */
	private String plugins()
	{
		String names = identity.values().get(MeasurementCondition.TRIAL_PLUGINS);
		if (names == null)
			return NOT_RECORDED;
		if (names.equals("none"))
			return "none";
		Map<String, String> versions = pluginLines(MeasurementCondition.PLUGIN_VERSIONS, " ");
		Map<String, String> builds = pluginLines(MeasurementCondition.PLUGIN_BUILDS, ": ");
		StringJoiner plugins = new StringJoiner(", ");
		for (String name : names.split("\n"))
		{
			StringBuilder plugin = new StringBuilder(name);
			List<String> notes = new ArrayList<>();
			String version = versions.get(name);
			if (version == null)
				notes.add("version " + NOT_RECORDED);
			else if (version.equals(MeasurementIdentity.NO_PLUGIN_VERSION))
				notes.add("no version in its manifest");
			else
				plugin.append(' ').append(version);
			String build = builds.get(name);
			if (build != null && !build.equals(MeasurementIdentity.INSTALLED_PLUGIN))
				notes.add("built from the project");
			if (!notes.isEmpty())
				plugin.append(" (").append(String.join(", ", notes)).append(')');
			plugins.add(plugin);
		}
		return plugins.toString();
	}

	/**
	 * Reads a plugin condition that records one line per plugin, which starts with the plugin's name.
	 *
	 * @param condition the condition
	 * @param separator the text between the plugin's name and its value
	 * @return each plugin's value, keyed by the plugin's name; empty if the condition is not recorded
	 */
	private Map<String, String> pluginLines(MeasurementCondition condition, String separator)
	{
		Map<String, String> values = new HashMap<>();
		String value = identity.values().get(condition);
		if (value == null)
			return values;
		for (String line : value.split("\n"))
		{
			int end = line.indexOf(separator);
			if (end > 0)
				values.put(line.substring(0, end), line.substring(end + separator.length()));
		}
		return values;
	}

	/**
	 * Describes the model of a cell.
	 *
	 * @param cell the cell's identifier, or {@code null} for the campaign-wide conditions
	 * @return the observed model and reasoning
	 */
	private String model(String cell)
	{
		String model = valueOf(cell, MeasurementCondition.MODEL);
		String reasoning = valueOf(cell, MeasurementCondition.REASONING);
		StringJoiner text = new StringJoiner(", ");
		if (model == null)
			text.add("model " + NOT_RECORDED);
		else
			text.add(model);
		if (reasoning == null)
			text.add("reasoning " + NOT_RECORDED);
		else
			text.add("reasoning " + reasoning);
		return text.toString();
	}

	/**
	 * Returns the value of a condition for a cell.
	 *
	 * @param cell      the cell's identifier, or {@code null} for the campaign-wide value
	 * @param condition the condition
	 * @return the cell's value, the campaign-wide value if the cell records none, or {@code null} if neither exists
	 */
	private String valueOf(String cell, MeasurementCondition condition)
	{
		if (cell == null)
			return identity.values().get(condition);
		return identity.valueOf(cell, condition);
	}

	/**
	 * Lists the certified prompt files.
	 *
	 * @return the files by project path, or {@code none}
	 */
	private String promptFileList()
	{
		if (promptFiles.isEmpty())
			return "none";
		StringJoiner files = new StringJoiner(", ");
		for (Path file : promptFiles)
			files.add(PortablePaths.text(file));
		return files.toString();
	}

	/**
	 * Describes the project's harness settings.
	 *
	 * @return the project's settings files, or {@code defaults} if it has none, followed by the user's settings that
	 *   trials received; or {@code not recorded}
	 */
	private String settings()
	{
		String files = identity.values().get(MeasurementCondition.LOADER_FILES);
		if (files == null)
			return NOT_RECORDED;
		if (files.equals("none"))
			return "defaults" + USER_SETTINGS;
		return "the project's " + String.join(", ", files.split("\n")) + USER_SETTINGS;
	}

	/**
	 * Describes the session environment by the CAT release that introduced it.
	 *
	 * @return the description
	 */
	private String trialSetup()
	{
		String release = identity.values().get(MeasurementCondition.SESSION_ENVIRONMENT_RELEASE);
		if (release != null)
			return "as introduced in CAT " + release;
		String version = identity.values().get(MeasurementCondition.SESSION_ENVIRONMENT_VERSION);
		if (version == null)
			return NOT_RECORDED;
		return "version " + version + ", CAT release " + NOT_RECORDED;
	}

	/**
	 * Lists the entries of a condition whose value holds one entry per line.
	 *
	 * @param condition the condition
	 * @return the entries, {@code none} if there are none, or {@code not recorded}
	 */
	private String listed(MeasurementCondition condition)
	{
		String value = identity.values().get(condition);
		if (value == null)
			return NOT_RECORDED;
		return String.join(", ", value.split("\n"));
	}
}
