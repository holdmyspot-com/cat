package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.contract.TrialRecord;
import io.github.cowwoc.cat.common.optimizer.evidence.EvidenceSection;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The record of a completed trial that its built-in tools and its judge decide from: every turn's ordered assistant
 * messages, every tool call of the main agent and its children, the staged source and the project copy that holds
 * the trial's file effects.
 * <p>
 * Unreported exit statuses remain null. A recorded list with no calls lets a built-in tool decide that the trial
 * never called a tool.
 * <p>
 * <b>Thread Safety:</b> Callers may read concurrently. Built-in tools that examine files read the project copy's
 * current state; callers
 * must ensure that its files remain unchanged while evaluating the trial.
 */
public final class TrialEvidence implements TrialRecord
{
	private final SessionResult.Completed trial;
	private final SessionEvidence activity;
	private final Path source;

	/**
	 * Creates the record of a trial.
	 *
	 * @param trial    the trial session's result
	 * @param activity the trial's activity, read from its harness events
	 * @param source   the staged source copied at the start of the trial
	 * @throws NullPointerException if any of the arguments are null
	 */
	public TrialEvidence(SessionResult.Completed trial, SessionEvidence activity, Path source)
	{
		requireThat(trial, "trial").isNotNull();
		requireThat(activity, "activity").isNotNull();
		requireThat(source, "source").isNotNull();
		this.trial = trial;
		this.activity = activity;
		this.source = source;
	}

	/**
	 * Reads the record of a trial.
	 *
	 * @param sessions the port that ran the trial, which reads its harness events
	 * @param trial    the trial session's result
	 * @param source   the staged source copied at the start of the trial
	 * @return the record
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the trial's transcript cannot be read
	 */
	public static TrialEvidence read(SessionPort sessions, SessionResult.Completed trial, Path source)
		throws IOException
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(trial, "trial").isNotNull();
		return new TrialEvidence(trial, sessions.readEvidence(trial.transcript()), source);
	}

	/**
	 * Renders the record as the judge's evidence section.
	 *
	 * @return the section
	 * @throws IOException if the source or the project copy cannot be read
	 */
	public String render() throws IOException
	{
		return EvidenceSection.render(activity, trial.effects(), source);
	}

	@Override
	public List<List<String>> replies()
	{
		return trial.responses();
	}

	@Override
	public List<SessionEvidence.Tool> tools()
	{
		List<SessionEvidence.Tool> tools = new ArrayList<>();
		for (SessionEvidence.Entry entry : activity.entries())
		{
			if (entry instanceof SessionEvidence.Tool tool)
				tools.add(tool);
		}
		return tools;
	}

	@Override
	public boolean exists(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return Files.exists(trial.effects().resolve(path), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A file whose real location lies outside the project copy, such as one reached through a link that the trial
	 * created, is not a file of the project. Bytes that are not valid UTF-8 are replaced, so that the tool observes
	 * them instead of failing to read the file.
	 */
	@Override
	public Optional<String> content(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return read(trial.effects(), path);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A file whose real location lies outside the staged source is not a staged file. Bytes that are not valid UTF-8
	 * are replaced.
	 */
	@Override
	public Optional<String> staged(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return read(source, path);
	}

	/**
	 * Reads a regular file of a directory.
	 *
	 * @param directory the directory
	 * @param path      the path relative to the directory
	 * @return the file's text, or empty if the path is not a regular file whose real location lies in the directory
	 * @throws IOException if the file cannot be read
	 */
	private static Optional<String> read(Path directory, String path) throws IOException
	{
		Path file = directory.resolve(path);
		if (!Files.isRegularFile(file))
			return Optional.empty();
		if (!file.toRealPath().startsWith(directory.toRealPath()))
			return Optional.empty();
		return Optional.of(new String(Files.readAllBytes(file), UTF_8));
	}

	/**
	 * Returns the tool calls that the trial made.
	 *
	 * @return the calls in the order they were made
	 */
	public Optional<List<ToolCall>> toolCalls()
	{
		return Optional.of(trial.toolCalls());
	}
}
