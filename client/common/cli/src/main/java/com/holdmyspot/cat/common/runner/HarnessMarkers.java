package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads which harnesses' sessions run a command from the markers that the harnesses set in the command's environment,
 * as {@link LaunchRoute#decide(String, String, Set, String[], Map)} takes them. Each plugin supplies the markers that
 * it knows, so that common code names no harness's variable.
 * <p>
 * <b>Thread Safety:</b> This class is immutable.
 *
 * @param markers the markers, one for each harness that the reader recognizes
 */
public record HarnessMarkers(List<Marker> markers)
{
	/**
	 * Creates a reader.
	 *
	 * @param markers the markers, one for each harness that the reader recognizes
	 * @throws NullPointerException if {@code markers} is null or holds null
	 */
	public HarnessMarkers
	{
		requireThat(markers, "markers").isNotNull().doesNotContain(null);
		markers = List.copyOf(markers);
	}

	/**
	 * Returns an environment without the variables of these markers. CAT's own starters give it to the runners that
	 * they start, so that a runner does not take its launch for one that a harness session made.
	 *
	 * @param environment the starter's environment
	 * @return an unmodifiable copy of {@code environment} without the markers' variables, which on Windows
	 *   matches names regardless of case, as the platform's environment does
	 * @throws NullPointerException if {@code environment} is null
	 */
	public Map<String, String> without(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		Map<String, String> copy;
		if (Platform.current().isWindows())
			copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		else
			copy = new HashMap<>();
		copy.putAll(environment);
		for (Marker marker : markers)
			copy.remove(marker.variable());
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Returns the harnesses whose sessions run a command, as their markers in the command's environment say.
	 *
	 * @param environment the command's environment
	 * @return the identifiers of the harnesses, empty if no harness runs the command
	 * @throws NullPointerException if {@code environment} is null
	 */
	public Set<String> running(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		Set<String> harnesses = new HashSet<>();
		for (Marker marker : markers)
		{
			if (marker.marks(environment))
				harnesses.add(marker.harness());
		}
		return Set.copyOf(harnesses);
	}

	/**
	 * The variable that a harness sets in the environment of the commands that its sessions run.
	 *
	 * @param harness  the harness's identifier
	 * @param variable the variable
	 * @param value    the value that marks the harness's command, or empty if any text that is not blank marks it
	 */
	public record Marker(String harness, String variable, Optional<String> value)
	{
		/**
		 * Creates a marker.
		 *
		 * @param harness  the harness's identifier
		 * @param variable the variable
		 * @param value    the value that marks the harness's command, or empty if any text that is not blank marks it
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code harness} or {@code variable} are blank
		 */
		public Marker
		{
			requireThat(harness, "harness").isNotBlank();
			requireThat(variable, "variable").isNotBlank();
			requireThat(value, "value").isNotNull();
		}

		/**
		 * Returns a marker that a harness sets to one value.
		 *
		 * @param harness  the harness's identifier
		 * @param variable the variable
		 * @param value    the value that marks the harness's command
		 * @return the marker
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code harness} or {@code variable} are blank
		 */
		public static Marker equalTo(String harness, String variable, String value)
		{
			requireThat(value, "value").isNotNull();
			return new Marker(harness, variable, Optional.of(value));
		}

		/**
		 * Returns a marker that a harness sets to text that is not blank, such as the ID of the command's thread.
		 *
		 * @param harness  the harness's identifier
		 * @param variable the variable
		 * @return the marker
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code harness} or {@code variable} are blank
		 */
		public static Marker notBlank(String harness, String variable)
		{
			return new Marker(harness, variable, Optional.empty());
		}

		/**
		 * Indicates whether an environment holds this marker.
		 *
		 * @param environment the command's environment
		 * @return {@code true} if the harness's session runs the command
		 */
		private boolean marks(Map<String, String> environment)
		{
			String text = environment.get(variable);
			if (value.isPresent())
				return value.get().equals(text);
			return text != null && !text.isBlank();
		}
	}
}
