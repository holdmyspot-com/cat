package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the processes of runner runs that an earlier {@link Driver} left running.
 * <p>
 * The runner records each run in {@code <runRoot>/<harness>/<id>/run.json}, including the run's owner process
 * ({@code ownerPid}, {@code ownerStartedAt}) and its harness process ({@code harnessPid},
 * {@code harnessStartedAt}). The run root must hold only the runs of one campaign, so that every run still marked
 * {@code running} while the driver holds the campaign's lock belongs to an earlier driver of that campaign.
 * <p>
 * A manifest that cannot be parsed, or that lacks a process's start time, identifies no process: without the start
 * time, a reused process identifier cannot be told apart from the recorded process.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class LeftoverRuns
{
	private final Path runRoot;
	private final JsonMapper json;

	/**
	 * Creates a finder.
	 *
	 * @param runRoot the directory that holds the campaign's runner runs
	 * @param json the JSON mapper that parses run manifests
	 * @throws NullPointerException if any of the arguments are null
	 */
	public LeftoverRuns(Path runRoot, JsonMapper json)
	{
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(json, "json").isNotNull();
		this.runRoot = runRoot;
		this.json = json;
	}

	/**
	 * Returns the processes of every run that is still marked {@code running}.
	 *
	 * @return the owner and harness processes, in directory order
	 * @throws IOException if the run directories cannot be listed
	 */
	public List<RecordedProcess> find() throws IOException
	{
		List<RecordedProcess> processes = new ArrayList<>();
		if (!Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS))
			return processes;
		for (Path harness : list(runRoot))
		{
			for (Path run : list(harness))
			{
				Path manifest = run.resolve("run.json");
				if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
					processes.addAll(parse(Files.readAllBytes(manifest)));
			}
		}
		return processes;
	}

	/**
	 * Lists the subdirectories of a directory, without following links.
	 *
	 * @param directory the directory
	 * @return the subdirectories, sorted by name
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> list(Path directory) throws IOException
	{
		List<Path> children = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
		{
			for (Path child : stream)
			{
				if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
					children.add(child);
			}
		}
		children.sort(null);
		return children;
	}

	/**
	 * Returns the processes that a run manifest records for a running run.
	 *
	 * @param manifest the manifest's content
	 * @return the owner process and, if recorded, the harness process; empty if the run is not running or the
	 *   manifest cannot be parsed
	 */
	private List<RecordedProcess> parse(byte[] manifest)
	{
		List<RecordedProcess> processes = new ArrayList<>();
		JsonNode root;
		try
		{
			root = json.readTree(manifest);
		}
		catch (JacksonException _)
		{
			return processes;
		}
		if (root == null || !root.isObject() || !text(root, "state").equals(Optional.of("running")))
			return processes;
		process(root, "ownerPid", "ownerStartedAt").ifPresent(processes::add);
		process(root, "harnessPid", "harnessStartedAt").ifPresent(processes::add);
		return processes;
	}

	/**
	 * Returns a process that a manifest records.
	 *
	 * @param root the manifest
	 * @param pidField the name of the process identifier's field, a number or a string of digits
	 * @param startedAtField the name of the start time's field
	 * @return the process, or empty if either field is missing, null or malformed
	 */
	private static Optional<RecordedProcess> process(JsonNode root, String pidField, String startedAtField)
	{
		JsonNode pidNode = root.get(pidField);
		if (pidNode == null)
			return Optional.empty();
		long pid;
		if (pidNode.isIntegralNumber() && pidNode.canConvertToLong())
			pid = pidNode.asLong();
		else if (pidNode.isString() && pidNode.asString().matches("[1-9][0-9]{0,17}"))
			pid = Long.parseLong(pidNode.asString());
		else
			return Optional.empty();
		if (pid <= 0)
			return Optional.empty();
		Optional<String> startedAt = text(root, startedAtField);
		if (startedAt.isEmpty())
			return Optional.empty();
		try
		{
			return Optional.of(new RecordedProcess(pid, Instant.parse(startedAt.get())));
		}
		catch (DateTimeParseException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Returns a string field.
	 *
	 * @param root the object that holds the field
	 * @param name the field's name
	 * @return the value, or empty if the field is missing or not a string
	 */
	private static Optional<String> text(JsonNode root, String name)
	{
		JsonNode value = root.get(name);
		if (value == null || !value.isString())
			return Optional.empty();
		return Optional.of(value.asString());
	}

	/**
	 * A process that a run manifest recorded.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param pid the process identifier
	 * @param startedAt the process's recorded start time
	 */
	public record RecordedProcess(long pid, Instant startedAt)
	{
		/**
		 * Creates a recorded process.
		 *
		 * @param pid the process identifier
		 * @param startedAt the process's recorded start time
		 * @throws NullPointerException if {@code startedAt} is null
		 * @throws IllegalArgumentException if {@code pid} is not positive
		 */
		public RecordedProcess
		{
			requireThat(pid, "pid").isPositive();
			requireThat(startedAt, "startedAt").isNotNull();
		}
	}
}
