package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns values and services shared while one CLI tool operation runs, including the CAT session directory of the session
 * whose tool shell runs the tool. The session is the one that a harness's session variable names, and its directory is
 * in the nearest enclosing CAT project.
 */
public abstract class AbstractToolScope extends AbstractJvmScope implements ToolScope
{
	private final Path projectRoot;
	private final boolean inProject;
	private final Map<String, String> environment;
	private final List<SessionHarness> harnesses;
	private SessionDirectory sessionDirectory;

	/**
	 * Creates an open tool scope for the session whose tool shell runs a tool.
	 *
	 * @param workingDirectory the directory from which the tool was invoked
	 * @param environment the tool's environment
	 * @param harnesses the harnesses whose session variables may name the session, in the order in which they are
	 *   consulted
	 * @param waiting how the tool waits between the attempts of a retried operation
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	public static ToolScope forSession(Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses, Waiting waiting)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		return new MainToolScope(new MainJvmScope(environment, waiting), workingDirectory, environment, harnesses);
	}

	/**
	 * Creates an open tool scope.
	 *
	 * @param parent the owned process scope
	 * @param workingDirectory the directory from which the tool was invoked
	 * @param environment the tool's environment
	 * @param harnesses the harnesses whose session variables may name the session
	 */
	protected AbstractToolScope(JvmScope parent, Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses)
	{
		super(parent);
		Path current = workingDirectory.toAbsolutePath().normalize();
		Path root = null;
		for (Path candidate = current; candidate != null && root == null; candidate = candidate.getParent())
			if (Files.isDirectory(candidate.resolve(".cat")))
				root = candidate;
		this.inProject = root != null;
		if (root == null)
			root = current;
		this.projectRoot = root;
		this.environment = Map.copyOf(environment);
		this.harnesses = List.copyOf(harnesses);
	}

	@Override
	public boolean isInProject()
	{
		ensureOpen();
		return inProject;
	}

	@Override
	public Optional<SessionDirectory> sessionDirectory() throws IOException
	{
		ensureOpen();
		if (sessionDirectory != null)
			return Optional.of(sessionDirectory);
		for (SessionHarness harness : harnesses)
		{
			String sessionId = environment.get(harness.sessionVariable());
			if (sessionId == null || sessionId.isBlank())
				continue;
			Path configurationDirectory = harness.configurationDirectory(environment,
				Platform.current().homeVariable());
			sessionDirectory = SessionDirectory.open(projectRoot, sessionId.strip(), harness,
				configurationDirectory, clock().instant(), json());
			return Optional.of(sessionDirectory);
		}
		return Optional.empty();
	}

	@Override
	public SessionDirectory requireSessionDirectory() throws IOException
	{
		Optional<SessionDirectory> session = sessionDirectory();
		if (session.isEmpty())
			throw new IOException(missingSession());
		return session.get();
	}

	@Override
	public String missingSession()
	{
		ensureOpen();
		List<String> variables = new ArrayList<>();
		for (SessionHarness harness : harnesses)
			variables.add(harness.sessionVariable());
		return switch (variables.size())
		{
			case 0 -> "No harness session variable is known";
			case 1 -> variables.getFirst() + " is not set";
			case 2 -> "Neither " + variables.getFirst() + " nor " + variables.getLast() + " is set";
			default -> "None of " + String.join(", ", variables) + " is set";
		};
	}
}
