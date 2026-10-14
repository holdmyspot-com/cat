package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A part of the prompt system that exists only to deliver the target prompt file or to carry out, enforce or repair
 * what it instructs, such as a hook or a route that serves it. Control trials remove each dependent along with the
 * target's content, and nothing else.
 *
 * @param location where {@code path} lies
 * @param path the file that holds the dependent, relative to the project root or to the plugin directory
 * @param removal the part of the file that control trials remove
 * @param serves the target instruction that the dependent serves
 */
public record Dependent(Location location, Path path, Removal removal, String serves)
{
	/**
	 * Creates a dependent.
	 *
	 * @param location where {@code path} lies
	 * @param path the file that holds the dependent, relative to the project root or to the plugin directory
	 * @param removal the part of the file that control trials remove
	 * @param serves the target instruction that the dependent serves
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code path} is absolute, empty, not normalized or leaves its root, or if
	 *   {@code serves} is blank
	 */
	public Dependent
	{
		requireThat(location, "location").isNotNull();
		requireThat(path, "path").isNotNull();
		String text = path.toString();
		if (path.isAbsolute() || text.isEmpty() || !path.normalize().equals(path) || path.startsWith(".."))
		{
			throw new IllegalArgumentException("path must be a normalized relative path that stays below its root: " +
				path);
		}
		requireThat(removal, "removal").isNotNull();
		requireThat(serves, "serves").isNotBlank();
	}

	/**
	 * Returns this dependent at another location.
	 *
	 * @param location where {@code path} lies
	 * @param path the file that holds the dependent, relative to {@code location}
	 * @return the dependent
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code path} is absolute, empty, not normalized or leaves its root
	 */
	public Dependent at(Location location, Path path)
	{
		return new Dependent(location, path, removal, serves);
	}

	/**
	 * Where the file of a dependent lies.
	 */
	public enum Location
	{
		/**
		 * In the project, relative to the project root.
		 */
		PROJECT,
		/**
		 * In the plugin under test, relative to the plugin directory.
		 */
		PLUGIN
	}

	/**
	 * The part of a file that control trials remove.
	 */
	public sealed interface Removal
	{
	}

	/**
	 * Removes the whole file.
	 */
	public record WholeFile() implements Removal
	{
	}

	/**
	 * Removes an exact span of the file, which occurs in it exactly once.
	 *
	 * @param text the span
	 */
	public record Span(String text) implements Removal
	{
		/**
		 * Creates a span.
		 *
		 * @param text the span
		 * @throws NullPointerException if {@code text} is null
		 * @throws IllegalArgumentException if {@code text} is empty
		 */
		public Span
		{
			requireThat(text, "text").isNotEmpty();
		}
	}

	/**
	 * Unregisters a hook from a hook configuration: a JSON file whose {@code hooks} object maps each event to groups,
	 * each of which lists hooks with a {@code command}. Every hook of the event with the command is removed, and a
	 * group or event left without hooks is removed with it.
	 *
	 * @param event the event, such as {@code PostToolUse}
	 * @param command the hook's command, as the configuration spells it
	 */
	public record HookRegistration(String event, String command) implements Removal
	{
		/**
		 * Creates a hook registration.
		 *
		 * @param event the event, such as {@code PostToolUse}
		 * @param command the hook's command, as the configuration spells it
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if any argument is blank
		 */
		public HookRegistration
		{
			requireThat(event, "event").isNotBlank();
			requireThat(command, "command").isNotBlank();
		}
	}
}
