package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.workspace.Fork;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CAT session directory of one harness session, {@code .cat/work/<session-id>/} in the session's project, which
 * holds the files that CAT writes for the session. Its {@code temp/} directory is the session's {@code CAT_TEMP}.
 * <p>
 * This class is the only code that names the directory that holds the CAT session directories or builds a CAT session
 * directory's path. Its first use creates
 * the directory, {@code temp/} and the marker {@code session.json}, which records the harness, the harness's
 * configuration directory, the creation time in UTC and where that configuration directory keeps the session's
 * transcript. A later use keeps the marker. The sweep removes the directories of sessions that their harness no longer
 * keeps.
 * <p>
 * The session's prompt-optimizer campaigns live in {@code optimizer/<campaign-id>/}, and the runs of the nested
 * sessions that they start live below {@code optimizer-runs/}.
 */
public final class SessionDirectory
{
	/**
	 * The directory, relative to a project, that holds the CAT session directories. Code that excludes them from a copy,
	 * a listing or a Git operation takes the directory from this constant.
	 */
	public static final String ROOT = ".cat/work";
	/**
	 * The stand-in that a rule text writes for the session's ID, which {@link #insertSessionId(String)} replaces.
	 */
	public static final String SESSION_ID_DIRECTIVE = "<!-- cat:session-id -->";
	/**
	 * How long a session directory is kept, from its marker's creation time, before the sweep may remove it.
	 */
	public static final Duration RETENTION = Duration.ofHours(24);
	private static final String MARKER = "session.json";
	private static final String TEMP = "temp";
	private static final String CAMPAIGNS = "optimizer";
	private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9-]+");
	private static final Pattern GLOB = Pattern.compile("[*?\\[{]");
	private final Path projectRoot;
	private final Path directory;
	private final String sessionId;
	private final JsonMapper json;

	/**
	 * Creates a CAT session directory value.
	 *
	 * @param projectRoot the session's project, absolute and normalized
	 * @param directory the session's directory
	 * @param sessionId the session's ID
	 * @param json the JSON mapper that reads markers
	 */
	private SessionDirectory(Path projectRoot, Path directory, String sessionId, JsonMapper json)
	{
		this.projectRoot = projectRoot;
		this.directory = directory;
		this.sessionId = sessionId;
		this.json = json;
	}

	/**
	 * Returns a session's CAT session directory, creating the directory, its {@code temp/} directory and its marker when
	 * they are missing. The marker is written to a temporary file in the directory and then linked into place, so
	 * concurrent first uses leave one complete marker, and an existing marker is never overwritten.
	 *
	 * @param projectRoot the session's project
	 * @param sessionId the harness's session ID, a UUID in canonical form
	 * @param harness the harness whose session owns the directory
	 * @param configurationDirectory the harness's configuration directory
	 * @param now the current time, which a new marker records
	 * @param json the JSON mapper that writes and reads markers
	 * @return the CAT session directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if {@code sessionId} is not a UUID in canonical form, or if the directory or marker cannot be
	 *   created
	 */
	public static SessionDirectory open(Path projectRoot, String sessionId, SessionHarness harness,
		Path configurationDirectory, Instant now, JsonMapper json) throws IOException
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(sessionId, "sessionId").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(now, "now").isNotNull();
		requireThat(json, "json").isNotNull();
		if (!isSessionId(sessionId))
			throw new IOException("The session ID is not a UUID in canonical form: " + sessionId);
		Path directory = root(projectRoot).resolve(sessionId);
		Files.createDirectories(directory.resolve(TEMP));
		Path marker = directory.resolve(MARKER);
		if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS))
			writeMarker(marker, markerContent(sessionId, harness, configurationDirectory, now), json);
		return new SessionDirectory(projectRoot.toAbsolutePath().normalize(), directory, sessionId, json);
	}

	/**
	 * Returns the directory that holds the CAT session directories of a project.
	 *
	 * @param projectRoot the project
	 * @return the absolute, normalized directory
	 */
	private static Path root(Path projectRoot)
	{
		return projectRoot.toAbsolutePath().normalize().resolve(ROOT);
	}

	/**
	 * Indicates whether a name is a session ID: a UUID in canonical form.
	 *
	 * @param name the name
	 * @return {@code true} if {@code name} is a lower-case UUID in its 8-4-4-4-12 form
	 */
	private static boolean isSessionId(String name)
	{
		try
		{
			return UUID.fromString(name).toString().equals(name);
		}
		catch (IllegalArgumentException _)
		{
			return false;
		}
	}

	/**
	 * Returns the content of a new marker.
	 *
	 * @param sessionId the session's ID
	 * @param harness the harness whose session owns the directory
	 * @param configurationDirectory the harness's configuration directory
	 * @param now the creation time
	 * @return the marker's properties
	 */
	private static Map<String, Object> markerContent(String sessionId, SessionHarness harness,
		Path configurationDirectory, Instant now)
	{
		List<String> transcripts = new ArrayList<>();
		for (String pattern : harness.transcripts())
			transcripts.add(pattern.replace(SessionHarness.SESSION_ID, sessionId));
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("harness", harness.id());
		content.put("configuration_directory", configurationDirectory.toAbsolutePath().normalize().toString());
		content.put("created_at", now.toString());
		content.put("transcripts", transcripts);
		return content;
	}

	/**
	 * Writes a marker unless one exists.
	 *
	 * @param marker the marker's path
	 * @param content the marker's properties
	 * @param json the JSON mapper
	 * @throws IOException if the marker cannot be written
	 */
	private static void writeMarker(Path marker, Map<String, Object> content, JsonMapper json) throws IOException
	{
		Path partial = Files.createTempFile(marker.getParent(), MARKER + "-", ".tmp");
		try
		{
			Files.write(partial, json.writeValueAsBytes(content));
			try
			{
				// Unlike a move, a link fails when the marker exists, so a concurrent first use cannot replace it
				Files.createLink(marker, partial);
			}
			catch (UnsupportedOperationException _)
			{
				Files.move(partial, marker);
			}
		}
		catch (FileAlreadyExistsException _)
		{
			// Another first use wrote the marker
		}
		finally
		{
			Files.deleteIfExists(partial);
		}
	}

	/**
	 * Returns the session's ID.
	 *
	 * @return the session's ID
	 */
	public String sessionId()
	{
		return sessionId;
	}

	/**
	 * Returns the session's directory.
	 *
	 * @return the absolute, normalized directory
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Returns the session's temporary directory, its {@code CAT_TEMP}.
	 *
	 * @return the directory, which exists
	 */
	public Path temp()
	{
		return directory.resolve(TEMP);
	}

	/**
	 * Returns the session's marker.
	 *
	 * @return the marker's path
	 */
	public Path marker()
	{
		return directory.resolve(MARKER);
	}

	/**
	 * Returns the directory that holds the session's prompt-optimizer campaigns, one directory per campaign. This method
	 * does not create it.
	 *
	 * @return the directory's path
	 */
	public Path campaigns()
	{
		return directory.resolve(CAMPAIGNS);
	}

	/**
	 * Returns the directory that holds the runs of the nested sessions that the session's campaigns start through a
	 * harness's runner. This method does not create it.
	 *
	 * @param harnessId the runner's harness, such as {@code claude}
	 * @return the directory's path
	 * @throws NullPointerException if {@code harnessId} is null
	 * @throws IllegalArgumentException if {@code harnessId} is blank
	 */
	public Path runRoot(String harnessId)
	{
		requireThat(harnessId, "harnessId").isNotBlank();
		return directory.resolve("optimizer-runs").resolve(harnessId);
	}

	/**
	 * Lists the campaign directory of every session of the project, this session's included, without creating any.
	 * Only a campaign's own session changes it, so a session uses another session's directory only to read it.
	 *
	 * @return the {@link #campaigns()} directory of each CAT session directory of the project, by session ID in
	 *   ascending order, whether or not it exists
	 * @throws IOException if the directory that holds the CAT session directories cannot be listed
	 */
	public SortedMap<String, Path> campaignsBySession() throws IOException
	{
		SortedMap<String, Path> campaigns = new TreeMap<>();
		for (Path session : sessionDirectories())
			campaigns.put(session.getFileName().toString(), session.resolve(CAMPAIGNS));
		return campaigns;
	}

	/**
	 * Lists the CAT session directories of the project, this session's included.
	 *
	 * @return the directories that a session ID names
	 * @throws IOException if the directory that holds the CAT session directories cannot be listed
	 */
	private List<Path> sessionDirectories() throws IOException
	{
		List<Path> sessions = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory.getParent()))
		{
			for (Path entry : entries)
			{
				if (isSessionId(entry.getFileName().toString()) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
					sessions.add(entry);
			}
		}
		return sessions;
	}

	/**
	 * Returns the file that holds the rules that the session's main agent reads.
	 *
	 * @return the file's path
	 */
	public Path rulesFile()
	{
		return directory.resolve("rules.md");
	}

	/**
	 * Returns the file that holds the rules that the main agent of another session of the same project reads, without
	 * creating that session's CAT session directory.
	 *
	 * @param sessionId the harness's ID of the other session, a UUID in canonical form
	 * @return the file's path
	 * @throws NullPointerException if {@code sessionId} is null
	 * @throws IOException if {@code sessionId} is not a UUID in canonical form
	 */
	public Path rulesFileOf(String sessionId) throws IOException
	{
		requireThat(sessionId, "sessionId").isNotNull();
		if (!isSessionId(sessionId))
			throw new IOException("The session ID is not a UUID in canonical form: " + sessionId);
		return directory.resolveSibling(sessionId).resolve("rules.md");
	}

	/**
	 * Returns the file that holds the rules that one of the session's subagents reads.
	 *
	 * @param agentId the harness's ID of the subagent
	 * @return the file's path
	 * @throws NullPointerException if {@code agentId} is null
	 * @throws IOException if {@code agentId} holds a character other than an ASCII letter, a digit or {@code -}
	 */
	public Path subagentRulesFile(String agentId) throws IOException
	{
		requireThat(agentId, "agentId").isNotNull();
		if (!AGENT_ID.matcher(agentId).matches())
			throw new IOException("The subagent ID cannot name a file: " + agentId);
		return subagentRulesDirectory().resolve(agentId + ".md");
	}

	/**
	 * Returns the directory of the subagents' rules files.
	 *
	 * @return the directory's path
	 */
	private Path subagentRulesDirectory()
	{
		return directory.resolve("subagents");
	}

	/**
	 * Deletes the session's rules files, those of its main agent and of its subagents, and nothing else.
	 *
	 * @throws IOException if a rules file cannot be deleted
	 */
	public void deleteRulesFiles() throws IOException
	{
		Files.deleteIfExists(rulesFile());
		Path subagents = subagentRulesDirectory();
		if (!Files.isDirectory(subagents, LinkOption.NOFOLLOW_LINKS))
			return;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(subagents, "*.md"))
		{
			for (Path file : files)
				Files.deleteIfExists(file);
		}
		try
		{
			Files.deleteIfExists(subagents);
		}
		catch (DirectoryNotEmptyException _)
		{
			// A file that CAT did not name stays where its owner put it
		}
	}

	/**
	 * Replaces each {@link #SESSION_ID_DIRECTIVE} in a text with the session's ID.
	 *
	 * @param text the text
	 * @return the text with the session's ID in place of each stand-in
	 * @throws NullPointerException if {@code text} is null
	 */
	public String insertSessionId(String text)
	{
		requireThat(text, "text").isNotNull();
		return text.replace(SESSION_ID_DIRECTIVE, sessionId);
	}

	/**
	 * Indicates whether a directory is the temporary directory of a CAT session directory.
	 *
	 * @param candidate the directory
	 * @return {@code true} if {@code candidate} is {@code temp} directly inside a CAT session directory, a directory
	 *   named by a session ID in the directory that holds a project's CAT session directories
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public static boolean isTemporaryDirectory(Path candidate)
	{
		requireThat(candidate, "candidate").isNotNull();
		Path normalized = candidate.toAbsolutePath().normalize();
		Path session = normalized.getParent();
		if (session == null || !normalized.getFileName().toString().equals(TEMP))
			return false;
		Path root = session.getParent();
		Path sessionName = session.getFileName();
		if (root == null || sessionName == null || !isSessionId(sessionName.toString()))
			return false;
		Path cat = root.getParent();
		if (cat == null || cat.getParent() == null)
			return false;
		return root(cat.getParent()).equals(root);
	}

	/**
	 * Removes the directory of every other session of the project whose marker is older than {@link #RETENTION} and
	 * whose transcript the marker's configuration directory no longer holds. Before it removes a directory, it removes
	 * the optimization worktree of each campaign in the directory's {@code optimizer/} through Git, so the repository
	 * keeps no record of a worktree that is gone. A directory without a readable marker is kept, and so is a directory
	 * whose campaign worktree cannot be removed. That failure, a directory that cannot be removed, and a transcript
	 * location that cannot be read, are reported on {@code error}; the sweep then continues with the next directory.
	 *
	 * @param now the current time
	 * @param error receives the failures
	 * @throws NullPointerException if any argument is null
	 */
	public void sweep(Instant now, PrintStream error)
	{
		sweep(now, error, Fork::remove, TemporaryDirectories::deleteRecursively);
	}

	/**
	 * Removes the directory of every other session of the project whose marker is older than {@link #RETENTION} and
	 * whose transcript the marker's configuration directory no longer holds, as {@link #sweep(Instant, PrintStream)}
	 * does, through given removals.
	 *
	 * @param now the current time
	 * @param error receives the failures
	 * @param worktrees removes the optimization worktree of one campaign in a session directory that the sweep removes
	 * @param removal removes one session directory
	 * @throws NullPointerException if any argument is null
	 */
	public void sweep(Instant now, PrintStream error, WorktreeRemoval worktrees, Removal removal)
	{
		requireThat(now, "now").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(worktrees, "worktrees").isNotNull();
		requireThat(removal, "removal").isNotNull();
		List<Path> sessions = new ArrayList<>();
		try
		{
			for (Path session : sessionDirectories())
			{
				if (!session.getFileName().toString().equals(sessionId))
					sessions.add(session);
			}
		}
		catch (IOException e)
		{
			error.println("CAT could not list " + directory.getParent() + ": " + e.getMessage());
			return;
		}
		for (Path session : sessions)
		{
			boolean gone;
			try
			{
				gone = isGone(session, now);
			}
			catch (IOException e)
			{
				error.println("CAT kept the CAT session directory " + session + " because it could not look for the " +
					"session's transcript: " + e.getMessage());
				continue;
			}
			if (!gone || !removeWorktrees(session, worktrees, error))
				continue;
			try
			{
				removal.remove(session);
			}
			catch (IOException e)
			{
				error.println("CAT could not remove the CAT session directory " + session + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Removes the optimization worktree of each campaign in another session's directory.
	 *
	 * @param session another session's directory
	 * @param worktrees removes one campaign's worktree
	 * @param error receives the failures
	 * @return {@code false} if the campaigns could not be listed or a campaign's worktree could not be removed
	 */
	private boolean removeWorktrees(Path session, WorktreeRemoval worktrees, PrintStream error)
	{
		List<Path> campaigns = new ArrayList<>();
		try
		{
			for (Path child : children(session.resolve(CAMPAIGNS)))
			{
				if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
					campaigns.add(child);
			}
		}
		catch (IOException e)
		{
			error.println("CAT kept the CAT session directory " + session + " because it could not list the " +
				"session's campaigns: " + e.getMessage());
			return false;
		}
		for (Path campaign : campaigns)
		{
			try
			{
				worktrees.remove(projectRoot, campaign);
			}
			catch (IOException e)
			{
				error.println("CAT kept the CAT session directory " + session + " because it could not remove the " +
					"optimization worktree of campaign " + campaign.getFileName() + ": " + e.getMessage());
				return false;
			}
		}
		return true;
	}

	/**
	 * Indicates whether a session's directory may be removed.
	 *
	 * @param session another session's directory
	 * @param now the current time
	 * @return {@code true} if its marker is older than {@link #RETENTION} and no transcript that the marker names
	 *   exists
	 * @throws IOException if the marker or a transcript location cannot be read
	 */
	private boolean isGone(Path session, Instant now) throws IOException
	{
		Path marker = session.resolve(MARKER);
		JsonNode content;
		try
		{
			content = json.readTree(Files.readAllBytes(marker));
		}
		catch (NoSuchFileException | JacksonException _)
		{
			return false;
		}
		JsonNode configuration = content.path("configuration_directory");
		JsonNode created = content.path("created_at");
		JsonNode transcripts = content.path("transcripts");
		if (!configuration.isString() || !created.isString() || !transcripts.isArray() || transcripts.isEmpty())
			return false;
		try
		{
			if (Instant.parse(created.stringValue()).plus(RETENTION).isAfter(now))
				return false;
		}
		catch (DateTimeException _)
		{
			return false;
		}
		Path configurationDirectory = Path.of(configuration.stringValue());
		if (!configurationDirectory.isAbsolute())
			return false;
		for (JsonNode transcript : transcripts)
		{
			if (!transcript.isString())
				return false;
			if (matches(configurationDirectory, List.of(transcript.stringValue().split("/")), 0))
				return false;
		}
		return true;
	}

	/**
	 * Indicates whether a file matches the remaining segments of a transcript pattern.
	 *
	 * @param base the directory that the remaining segments are relative to
	 * @param segments the pattern's segments
	 * @param index the index of the first remaining segment
	 * @return {@code true} if a regular file matches
	 * @throws IOException if a directory cannot be listed
	 */
	private static boolean matches(Path base, List<String> segments, int index) throws IOException
	{
		if (index == segments.size())
			return Files.isRegularFile(base);
		// A configuration directory, or a directory in it, may be a link to the directory that holds the transcripts
		if (!Files.isDirectory(base))
			return false;
		String segment = segments.get(index);
		if (segment.equals("**"))
		{
			if (matches(base, segments, index + 1))
				return true;
			// A link that "**" followed could lead back to a parent directory
			for (Path child : children(base))
				if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && matches(child, segments, index))
					return true;
			return false;
		}
		if (!GLOB.matcher(segment).find())
			return matches(base.resolve(segment), segments, index + 1);
		PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + segment);
		for (Path child : children(base))
			if (matcher.matches(child.getFileName()) && matches(child, segments, index + 1))
				return true;
		return false;
	}

	/**
	 * Returns the entries of a directory.
	 *
	 * @param directory the directory
	 * @return its entries, or none when it vanished
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> children(Path directory) throws IOException
	{
		List<Path> children = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			for (Path entry : entries)
				children.add(entry);
		}
		catch (NoSuchFileException | NotDirectoryException _)
		{
			return List.of();
		}
		return children;
	}

	/**
	 * Removes the optimization worktree of a campaign, and Git's record of it, as
	 * {@link #sweep(Instant, PrintStream, WorktreeRemoval, Removal)} does before it removes the campaign's session
	 * directory.
	 */
	@FunctionalInterface
	public interface WorktreeRemoval
	{
		/**
		 * Removes the optimization worktree that a campaign directory holds, if any, and Git's record of it.
		 *
		 * @param project the project, whose repository records the worktree
		 * @param campaign the campaign directory
		 * @throws IOException if Git fails or the worktree cannot be deleted
		 */
		void remove(Path project, Path campaign) throws IOException;
	}

	/**
	 * Removes a directory that CAT wrote for a session, such as a session directory that
	 * {@link #sweep(Instant, PrintStream, WorktreeRemoval, Removal)} removes.
	 */
	@FunctionalInterface
	public interface Removal
	{
		/**
		 * Removes a directory and everything in it.
		 *
		 * @param directory the directory
		 * @throws IOException if the directory cannot be removed
		 */
		void remove(Path directory) throws IOException;
	}
}
