package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns values and services shared while one CLI tool operation runs, including the CAT session directory of the session
 * whose tool shell runs the tool. The session is the one that a harness's session variable names, and its directory is
 * in the nearest enclosing CAT project.
 */
public final class ToolScope implements AutoCloseable
{
	private boolean closed;
	private final Path projectRoot;
	private final boolean inProject;
	private final Map<String, String> environment;
	private final List<SessionHarness> harnesses;
	private SessionDirectory sessionDirectory;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		build());
	private final Reference<YAMLMapper> yaml = LazyReference.create(() -> YAMLMapper.builder().
		build());

	/**
	 * Creates an open tool scope without a session.
	 */
	public ToolScope()
	{
		this(Path.of(""), Map.of(), List.of());
	}

	/**
	 * Creates an open tool scope for the session whose tool shell runs a tool.
	 *
	 * @param workingDirectory the directory from which the tool was invoked
	 * @param environment the tool's environment
	 * @param harnesses the harnesses whose session variables may name the session, in the order in which they are
	 *   consulted
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	public static ToolScope forSession(Path workingDirectory, Map<String, String> environment,
		List<SessionHarness> harnesses)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		return new ToolScope(workingDirectory, environment, harnesses);
	}

	/**
	 * Creates an open tool scope.
	 *
	 * @param workingDirectory the directory from which the tool was invoked
	 * @param environment the tool's environment
	 * @param harnesses the harnesses whose session variables may name the session
	 */
	private ToolScope(Path workingDirectory, Map<String, String> environment, List<SessionHarness> harnesses)
	{
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

	/**
	 * Returns the JSON mapper shared by this tool operation.
	 *
	 * @return the shared JSON mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	public JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	/**
	 * Returns the YAML mapper shared by this tool operation.
	 *
	 * @return the shared YAML mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	public YAMLMapper yaml()
	{
		ensureOpen();
		return yaml.getValue();
	}

	/**
	 * Indicates whether a CAT project encloses the tool's working directory.
	 *
	 * @return {@code true} if the working directory or one of its parents holds a {@code .cat} directory
	 * @throws IllegalStateException if this scope is closed
	 */
	public boolean isInProject()
	{
		ensureOpen();
		return inProject;
	}

	/**
	 * Returns the CAT session directory of the session that the first set session variable names, creating it and its
	 * marker on first use. Without a CAT project, the directory is in the tool's working directory.
	 *
	 * @return the CAT session directory, or empty when no session variable is set
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the session variable is not a UUID in canonical form, if the harness's configuration
	 *   directory cannot be found, or if the directory or its marker cannot be created
	 */
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
				configurationDirectory, Instant.now(), json());
			return Optional.of(sessionDirectory);
		}
		return Optional.empty();
	}

	/**
	 * Returns the CAT session directory of the session that the first set session variable names, as
	 * {@link #sessionDirectory()} does, for a tool that cannot run without one.
	 *
	 * @return the CAT session directory
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if no session variable is set, with a message that names every session variable, or for any
	 *   reason that {@link #sessionDirectory()} throws
	 */
	public SessionDirectory requireSessionDirectory() throws IOException
	{
		Optional<SessionDirectory> session = sessionDirectory();
		if (session.isEmpty())
			throw new IOException(missingSession());
		return session.get();
	}

	/**
	 * Returns the sentence that reports a tool shell without a session variable.
	 *
	 * @return the sentence, which names every session variable that this scope consults
	 * @throws IllegalStateException if this scope is closed
	 */
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

	/**
	 * Indicates whether this scope is closed.
	 *
	 * @return true if this scope is closed
	 */
	public boolean isClosed()
	{
		return closed;
	}

	/**
	 * Prevents further service access.
	 */
	@Override
	public void close()
	{
		if (closed)
			return;
		closed = true;
	}

	/**
	 * Ensures this scope remains open.
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	private void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
	}
}
