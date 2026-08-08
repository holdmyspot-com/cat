package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns values and services shared while one CLI tool operation runs.
 */
public final class ToolScope implements AutoCloseable
{
	private boolean closed;
	private final Path catWork;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		build());
	private final Reference<YAMLMapper> yaml = LazyReference.create(() -> YAMLMapper.builder().
		build());

	/**
	 * Creates an open tool scope.
	 */
	public ToolScope()
	{
		this(Path.of(""));
	}

	/**
	 * Creates an open tool scope for the project enclosing a tool's working directory.
	 *
	 * @param workingDirectory the directory from which the tool was invoked
	 * @return an open tool scope for that project
	 * @throws NullPointerException if {@code workingDirectory} is {@code null}
	 */
	public static ToolScope forWorkingDirectory(Path workingDirectory)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		return new ToolScope(workingDirectory);
	}

	/**
	 * Creates an open tool scope for one project directory.
	 *
	 * @param workingDirectory the directory from which the tool was invoked
	 */
	ToolScope(Path workingDirectory)
	{
		Path projectRoot = projectRoot(workingDirectory);
		catWork = projectRoot.resolve(".cat/work");
	}

	/**
	 * Finds the nearest enclosing CAT project root.
	 *
	 * @param workingDirectory the tool's invocation directory
	 * @return the enclosing CAT project root, or the invocation directory when it is not in a CAT project
	 */
	private static Path projectRoot(Path workingDirectory)
	{
		Path current = workingDirectory.toAbsolutePath().normalize();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent())
			if (Files.isDirectory(candidate.resolve(".cat")))
				return candidate;
		return current;
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
	 * Returns the CAT-owned work root for this tool operation.
	 *
	 * @return the work root
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path catWork()
	{
		ensureOpen();
		return catWork;
	}

	/**
	 * Returns the CAT-owned root for invocation-local temporary resources.
	 *
	 * @return the CAT work temp directory
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path temporaryWorkRoot()
	{
		ensureOpen();
		return catWork.resolve("temp");
	}

	/**
	 * Returns the CAT-owned root for invocation-local temporary resources.
	 *
	 * @return the CAT work temp directory
	 */
	public Path catTemp()
	{
		return temporaryWorkRoot();
	}

	/**
	 * Returns whether this scope is closed.
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
