package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns values and services shared for one native hook invocation.
 */
public final class HookScope implements AutoCloseable
{
	private boolean closed;
	private final Path pluginRoot;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		build());
	private final Reference<YAMLMapper> yaml = LazyReference.create(() -> YAMLMapper.builder().
		build());

	/**
	 * Creates an open hook scope from an explicit plugin root.
	 *
	 * @param pluginRoot a non-null CAT plugin root
	 * @throws NullPointerException if {@code pluginRoot} is null
	 */
	public HookScope(Path pluginRoot)
	{
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		this.pluginRoot = pluginRoot.toAbsolutePath().normalize();
	}

	/**
	 * Returns the JSON mapper shared by this hook invocation.
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
	 * Returns the YAML mapper shared by this hook invocation.
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
	 * Returns CAT's installation directory for this hook invocation.
	 *
	 * @return the normalized CAT installation directory
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path pluginRoot()
		throws IOException
	{
		ensureOpen();
		if (!Files.isDirectory(pluginRoot))
			throw new IOException("CAT installation directory is not a directory");
		return pluginRoot;
	}

	/**
	 * Resolves the native hook's current working directory.
	 *
	 * @param event the native SessionStart event
	 * @param workingDirectory the hook process's working directory
	 * @return the normalized current working directory
	 * @throws NullPointerException if {@code event} or {@code workingDirectory} are null
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the resolved project directory is not a directory
	 */
	public Path projectDir(JsonNode event, Path workingDirectory) throws IOException
	{
		ensureOpen();
		String candidate = text(event, "cwd");
		Path result = workingDirectory;
		if (!candidate.isEmpty())
			result = Path.of(candidate);
		result = result.toAbsolutePath().normalize();
		if (!Files.isDirectory(result))
			throw new IOException("Project directory is not a directory");
		return result;
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

	/**
	 * Returns a stripped textual JSON property, or an empty string.
	 *
	 * @param object a non-null JSON object
	 * @param name a non-blank property name
	 * @return the property value
	 */
	private static String text(JsonNode object, String name)
	{
		assert that(name, "name").isNotBlank().elseThrow();
		JsonNode value = object.get(name);
		if (value == null || !value.isString())
			return "";
		return value.asString().strip();
	}
}
