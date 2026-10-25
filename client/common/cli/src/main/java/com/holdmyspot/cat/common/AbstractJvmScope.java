package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Implements {@link JvmScope}'s shared values and lifecycle, optionally owning an enclosing process scope.
 */
public abstract class AbstractJvmScope implements JvmScope
{
	private boolean closed;
	private final JvmScope parent;
	private final boolean indentJson;
	private final Reference<Path> temporaryDirectory;
	private final Reference<ExecutableFiles> executableFiles;
	private final Reference<JsonMapper> json = LazyReference.create(this::createJsonMapper);
	private final Reference<YAMLMapper> yaml = LazyReference.create(this::createYamlMapper);
	private final Reference<Clock> clock = LazyReference.create(this::createClock);
	private final Reference<FileLocks> fileLocks = LazyReference.create(this::createFileLocks);

	/**
	 * Creates a process scope with an explicit temporary directory.
	 *
	 * @param temporaryDirectory the process temporary directory
	 * @param executableFiles the process executable-file runner
	 * @throws NullPointerException if any argument is null
	 */
	protected AbstractJvmScope(Path temporaryDirectory, ExecutableFiles executableFiles)
	{
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		requireThat(executableFiles, "executableFiles").isNotNull();
		this.executableFiles = LazyReference.create(() -> executableFiles);
		this.parent = null;
		this.indentJson = false;
		this.temporaryDirectory = LazyReference.create(() -> temporaryDirectory.toAbsolutePath().normalize());
	}

	/**
	 * Creates a scope that owns and shares the supplied process scope.
	 *
	 * @param parent the process scope, closed when this scope closes
	 * @throws NullPointerException if {@code parent} is null
	 */
	protected AbstractJvmScope(JvmScope parent)
	{
		this(parent, false);
	}

	/**
	 * Creates a scope with optional indented protocol JSON.
	 *
	 * @param parent the owned process scope
	 * @param indentJson indicates whether JSON output is indented
	 * @throws NullPointerException if {@code parent} is null
	 */
	protected AbstractJvmScope(JvmScope parent, boolean indentJson)
	{
		requireThat(parent, "parent").isNotNull();
		this.parent = parent;
		this.executableFiles = LazyReference.create(parent::executableFiles);
		this.indentJson = indentJson;
		this.temporaryDirectory = null;
	}

	@Override
	public JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	@Override
	public YAMLMapper yaml()
	{
		ensureOpen();
		return yaml.getValue();
	}

	@Override
	public Clock clock()
	{
		ensureOpen();
		return clock.getValue();
	}

	@Override
	public ExecutableFiles executableFiles()
	{
		ensureOpen();
		return executableFiles.getValue();
	}

	@Override
	public FileLocks fileLocks()
	{
		ensureOpen();
		return fileLocks.getValue();
	}

	@Override
	public Path temporaryDirectory() throws IOException
	{
		ensureOpen();
		if (parent != null)
			return parent.temporaryDirectory();
		return Files.createDirectories(temporaryDirectory.getValue());
	}

	@Override
	public final boolean isClosed()
	{
		return closed;
	}

	@Override
	public void close()
	{
		if (closed)
			return;
		closed = true;
		if (parent != null)
			parent.close();
	}

	/**
	 * Creates this scope's JSON mapper.
	 *
	 * @return the mapper
	 */
	private JsonMapper createJsonMapper()
	{
		if (indentJson)
			return JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
		if (parent != null)
			return parent.json();
		return JsonMapper.builder().build();
	}

	/**
	 * Creates this scope's YAML mapper.
	 *
	 * @return the mapper
	 */
	private YAMLMapper createYamlMapper()
	{
		if (parent != null)
			return parent.yaml();
		return YAMLMapper.builder().build();
	}

	/**
	 * Creates this process's lock coordinator or shares the enclosing process's coordinator.
	 *
	 * @return the process lock coordinator
	 */
	private FileLocks createFileLocks()
	{
		if (parent != null)
			return parent.fileLocks();
		return new FileLocks();
	}

	/**
	 * Creates this scope's clock.
	 *
	 * @return the clock
	 */
	private Clock createClock()
	{
		if (parent != null)
			return parent.clock();
		return Clock.systemUTC();
	}

	/**
	 * Ensures that this scope remains open.
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected final void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
	}
}
