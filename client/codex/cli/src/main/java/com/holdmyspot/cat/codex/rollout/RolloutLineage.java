package com.holdmyspot.cat.codex.rollout;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the rollouts that together record a Codex thread's history.
 * <p>
 * When a thread is forked, Codex writes the fork's rollout with only the records that the fork itself produced. The
 * rollout's first record, {@code session_meta}, holds a {@code history_base} that names the rollout that the fork
 * inherited its earlier history from, and the {@code end_ordinal_exclusive} before which the fork inherited that
 * rollout's records. Each record of a paginated rollout carries an {@code ordinal} that numbers it within the thread's
 * whole history, so the records of a fork begin at the ordinal where its parent's inherited records end. A parent can
 * itself be a fork, and so on to a rollout with no {@code history_base}.
 * <p>
 * Codex keeps a rollout in {@code sessions/}, in a folder for the date it started, or in {@code archived_sessions/}
 * once its thread is archived, in a file named {@code rollout-<time>-<id>.jsonl}. The {@code id} is the rollout's own.
 * For a thread that was reverted it is the thread's identifier, an underscore and the rollout's identifier, and a
 * {@code history_base} refers to the rollout's. A rollout that Codex has compressed into a {@code .jsonl.zst} file is
 * not read.
 */
public final class RolloutLineage
{
	/**
	 * The {@code end_ordinal_exclusive} of the thread's own rollout: no record of it is left out.
	 */
	private static final long OWN_ROLLOUT_END = Long.MAX_VALUE;

	/**
	 * The records of one rollout that belong to a thread's history.
	 *
	 * @param rollout the rollout file
	 * @param endOrdinalExclusive the ordinal of the first record of the rollout that the thread does not include, or
	 *   {@link Long#MAX_VALUE} when it includes every record
	 */
	public record Segment(Path rollout, long endOrdinalExclusive)
	{
	}

	/**
	 * Where a fork's history begins in its parent: the {@code history_base} of a rollout.
	 *
	 * @param rolloutId the identifier of the parent rollout
	 * @param endOrdinalExclusive the ordinal of the first record of the parent that the fork does not include
	 */
	private record HistoryBase(String rolloutId, long endOrdinalExclusive)
	{
	}

	/**
	 * Prevents instantiation.
	 */
	private RolloutLineage()
	{
	}

	/**
	 * Lists the rollouts that hold a thread's history, oldest first. An ancestor whose rollout cannot be found, or whose
	 * first record cannot be read, ends the list at its end: the rollouts that it inherited from are left out, so a
	 * caller reads what exists.
	 *
	 * @param json the JSON mapper
	 * @param rollout the thread's own rollout file, which is always the last segment
	 * @return one segment for the rollout and for each ancestor that could be read, ending with the rollout's own
	 * @throws NullPointerException if {@code json} or {@code rollout} are null
	 * @throws IOException          if the thread's own rollout cannot be read
	 */
	public static List<Segment> resolve(JsonMapper json, Path rollout) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(rollout, "rollout").isNotNull();
		List<Segment> newestFirst = new ArrayList<>();
		newestFirst.add(new Segment(rollout, OWN_ROLLOUT_END));
		Set<Path> visited = new HashSet<>();
		visited.add(rollout.toAbsolutePath().normalize());
		Path child = rollout;
		HistoryBase base = readHistoryBase(json, rollout);
		try
		{
			while (base != null)
			{
				Path parent = findRollout(child, base.rolloutId());
				// A rollout that names itself or a descendant as its parent is malformed; stop rather than loop
				if (parent == null || !visited.add(parent.toAbsolutePath().normalize()))
					break;
				newestFirst.add(new Segment(parent, base.endOrdinalExclusive()));
				child = parent;
				base = readHistoryBase(json, parent);
			}
		}
		catch (IOException | UncheckedIOException _)
		{
			// The ancestors that could be read still supply their messages; the rest report as not found
		}
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/**
	 * Reads the {@code history_base} that a rollout's first record holds.
	 *
	 * @param json the JSON mapper
	 * @param rollout the rollout file
	 * @return the {@code history_base}, or {@code null} if the first record is not a {@code session_meta} that holds a
	 *   complete one, as for a thread that is not a fork
	 * @throws IOException if the rollout cannot be read
	 */
	private static HistoryBase readHistoryBase(JsonMapper json, Path rollout) throws IOException
	{
		String firstRecord;
		try (BufferedReader reader = Files.newBufferedReader(rollout, UTF_8))
		{
			firstRecord = reader.readLine();
		}
		if (firstRecord == null || !firstRecord.contains("\"history_base\""))
			return null;
		JsonNode base;
		try
		{
			base = json.readTree(firstRecord).path("payload").path("history_base");
		}
		catch (JacksonException _)
		{
			return null;
		}
		String rolloutId = base.path("thread_id").asString("");
		JsonNode end = base.path("end_ordinal_exclusive");
		if (rolloutId.isEmpty() || !end.isNumber())
			return null;
		return new HistoryBase(rolloutId, end.asLong(0));
	}

	/**
	 * Finds the file of a rollout in the Codex home that holds another rollout.
	 *
	 * @param sibling a rollout file under {@code sessions/} or {@code archived_sessions/}, which locates the Codex home
	 * @param rolloutId the identifier of the rollout to find
	 * @return the rollout file, preferring {@code sessions/} over {@code archived_sessions/}, or {@code null} if
	 *   {@code sibling} is under neither or no file there is named for {@code rolloutId}
	 * @throws IOException if a directory cannot be listed
	 */
	private static Path findRollout(Path sibling, String rolloutId) throws IOException
	{
		Path home = codexHome(sibling.toAbsolutePath());
		if (home == null)
			return null;
		for (String directory : List.of("sessions", "archived_sessions"))
		{
			Path root = home.resolve(directory);
			if (!Files.isDirectory(root))
				continue;
			try (Stream<Path> files = Files.walk(root))
			{
				Path found = files.filter(Files::isRegularFile).
					filter(file -> isRolloutFile(file.getFileName().toString(), rolloutId)).
					findFirst().orElse(null);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	/**
	 * Returns the Codex home that holds a rollout.
	 *
	 * @param rollout the rollout file
	 * @return the parent of the nearest enclosing {@code sessions} or {@code archived_sessions} directory, or
	 *   {@code null} if the rollout is under neither
	 */
	private static Path codexHome(Path rollout)
	{
		for (Path directory = rollout.getParent(); directory != null; directory = directory.getParent())
		{
			Path name = directory.getFileName();
			if (name == null)
				continue;
			String directoryName = name.toString();
			if (directoryName.equals("sessions") || directoryName.equals("archived_sessions"))
				return directory.getParent();
		}
		return null;
	}

	/**
	 * Indicates whether a file name is that of a given rollout's log.
	 *
	 * @param fileName the file name
	 * @param rolloutId the identifier of the rollout
	 * @return {@code true} if the name is {@code rollout-<time>-<id>.jsonl}, where {@code <id>} is the rollout's
	 *   identifier or ends with an underscore and that identifier
	 */
	private static boolean isRolloutFile(String fileName, String rolloutId)
	{
		boolean isRollout = fileName.startsWith("rollout-");
		boolean isOrdinaryName = fileName.endsWith("-" + rolloutId + ".jsonl");
		boolean isRevertedName = fileName.endsWith("_" + rolloutId + ".jsonl");
		return isRollout && (isOrdinaryName || isRevertedName);
	}
}
