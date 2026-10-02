package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.CheckEvidence;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The recorded evidence of a completed trial session that its deterministic checks decide from: the session's final
 * replies, the project copy that holds its file effects, and the root agent's tool calls in invocation order.
 * <p>
 * Unreported commands and exit statuses remain null. A recorded list with no calls lets a tool-use check decide
 * that the trial never called a tool.
 * <p>
 * <b>Thread Safety:</b> Callers may read concurrently. File checks read the project copy's current state; callers
 * must ensure that its files remain unchanged while evaluating the trial.
 */
final class TrialEvidence implements CheckEvidence
{
	private final SessionResult.Completed trial;

	/**
	 * Creates the evidence of a trial.
	 *
	 * @param trial the trial session's result
	 * @throws NullPointerException if {@code trial} is null
	 */
	TrialEvidence(SessionResult.Completed trial)
	{
		requireThat(trial, "trial").isNotNull();
		this.trial = trial;
	}

	@Override
	public List<String> replies()
	{
		return trial.responses();
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
	 * created, is not a file of the project. Bytes that are not valid UTF-8 are replaced, so that the check observes
	 * them instead of failing to read the file.
	 */
	@Override
	public Optional<String> content(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		Path file = trial.effects().resolve(path);
		if (!Files.isRegularFile(file))
			return Optional.empty();
		if (!file.toRealPath().startsWith(trial.effects().toRealPath()))
			return Optional.empty();
		return Optional.of(new String(Files.readAllBytes(file), UTF_8));
	}

	@Override
	public Optional<List<ToolCall>> toolCalls()
	{
		return Optional.of(trial.toolCalls());
	}
}
