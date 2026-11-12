package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the paths that a campaign document records inside its project valid after the project moves or is copied.
 * <p>
 * A document records a path inside the project relative to the project root, so that it names the same file wherever
 * the project is. A path outside the project, such as a shared file that a link reaches, stays absolute. Documents
 * that older versions of CAT wrote record absolute paths below the root that the campaign recorded; reading moves each
 * of them below the project root that the store belongs to, so that a copy of a project never reads or writes the
 * original project's files through them.
 * <p>
 * The paths that this class converts are the campaign's target and the fields {@code transcript}, {@code effects} and
 * {@code initial_project}, which name where a trial session's or a prompt author's evidence was retained.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class ProjectPaths
{
	/**
	 * The names of the fields that hold a path that may lie inside the project.
	 */
	private static final Set<String> PATH_FIELDS = Set.of("transcript", "effects", "initial_project");
	private final Path recorded;
	private final Path current;

	/**
	 * Creates the conversion of a document's paths.
	 *
	 * @param recorded the project root that the document's absolute paths inside the project lie below, or
	 *   {@code null} if it is unknown
	 * @param current the project root that the document's relative paths are relative to now, or {@code null} to read
	 *   them relative to {@code recorded}
	 */
	ProjectPaths(Path recorded, Path current)
	{
		if (recorded != null)
			recorded = recorded.normalize();
		if (current != null)
			current = current.normalize();
		else
			current = recorded;
		this.recorded = recorded;
		this.current = current;
	}

	/**
	 * Returns the project root that the paths are read in.
	 *
	 * @return the root, or {@code null} if it is unknown
	 */
	Path current()
	{
		return current;
	}

	/**
	 * Returns a recorded path as it names a file now.
	 *
	 * @param text the recorded path
	 * @return a relative path resolved against the current project root, an absolute path below the recorded root
	 *   moved below the current root, or any other path unchanged
	 * @throws InvalidPathException if {@code text} is not a path
	 */
	Path read(String text)
	{
		Path path = Path.of(text);
		if (current == null || !PortablePaths.isRooted(current))
			return path;
		if (!PortablePaths.isRooted(path))
			return current.resolve(path).normalize();
		Path normalized = path.normalize();
		if (recorded != null && !recorded.equals(current) && normalized.startsWith(recorded))
			return current.resolve(recorded.relativize(normalized)).normalize();
		return path;
	}

	/**
	 * Returns a path as a document records it.
	 *
	 * @param path the path
	 * @param root the project root, or {@code null} if it is unknown
	 * @return the path relative to {@code root} if it lies below it, and otherwise the path as it is
	 */
	static String write(Path path, Path root)
	{
		requireThat(path, "path").isNotNull();
		if (root != null && PortablePaths.isRooted(root) && PortablePaths.isRooted(path))
		{
			Path normalizedRoot = root.normalize();
			Path normalized = path.normalize();
			if (normalized.startsWith(normalizedRoot) && !normalized.equals(normalizedRoot))
				return PortablePaths.text(normalizedRoot.relativize(normalized));
		}
		return PortablePaths.text(path);
	}

	/**
	 * Records every evidence path of a document that lies inside the project relative to the project root.
	 *
	 * @param document a document that holds absolute evidence paths
	 * @param root the project root, or {@code null} to leave every path absolute
	 */
	static void relativize(JsonNode document, Path root)
	{
		if (root == null)
			return;
		convert(document, text -> write(Path.of(text), root));
	}

	/**
	 * Replaces every evidence path of a document with the path that it names now.
	 *
	 * @param document a document as stored
	 * @throws IllegalArgumentException if an evidence field does not hold a path
	 */
	void resolve(JsonNode document)
	{
		if (current == null)
			return;
		convert(document, text ->
		{
			try
			{
				return read(text).toString();
			}
			catch (InvalidPathException e)
			{
				throw new IllegalArgumentException(text + " is not a path: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Replaces the text of every evidence field in a document.
	 *
	 * @param document the document
	 * @param conversion returns the new text of a field's text
	 */
	private static void convert(JsonNode document, Conversion conversion)
	{
		List<JsonNode> pending = new ArrayList<>();
		pending.add(document);
		while (!pending.isEmpty())
		{
			JsonNode node = pending.removeLast();
			if (node instanceof ObjectNode object)
			{
				Map<String, String> converted = new LinkedHashMap<>();
				for (Map.Entry<String, JsonNode> property : object.properties())
				{
					JsonNode value = property.getValue();
					if (PATH_FIELDS.contains(property.getKey()) && value.isString())
						converted.put(property.getKey(), conversion.apply(value.asString()));
					else if (value.isContainer())
						pending.add(value);
				}
				for (Map.Entry<String, String> field : converted.entrySet())
					object.put(field.getKey(), field.getValue());
			}
			else if (node.isArray())
			{
				for (JsonNode element : node)
				{
					if (element.isContainer())
						pending.add(element);
				}
			}
		}
	}

	/**
	 * Converts the text of an evidence field.
	 */
	@FunctionalInterface
	private interface Conversion
	{
		/**
		 * Converts a field's text.
		 *
		 * @param text the field's text
		 * @return the new text
		 */
		String apply(String text);
	}
}
