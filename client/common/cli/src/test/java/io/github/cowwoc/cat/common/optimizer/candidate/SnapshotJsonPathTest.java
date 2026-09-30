package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the candidate evidence mapper preserves source path form during storage.
 */
public final class SnapshotJsonPathTest
{
	/**
	 * Creates path mapping tests.
	 */
	public SnapshotJsonPathTest()
	{
	}

	/**
	 * The installed mapper keeps a relative target relative and an absolute root absolute.
	 */
	@Test
	public void preservesRelativeAndAbsolutePaths()
	{
		JsonMapper json = SnapshotJson.mapper();
		Path relative = Path.of("target.md");
		Path absolute = relative.toAbsolutePath();
		PathValue decodedRelative = json.readValue(json.writeValueAsBytes(new PathValue(relative)),
			PathValue.class);
		PathValue decodedAbsolute = json.readValue(json.writeValueAsBytes(new PathValue(absolute)),
			PathValue.class);
		requireThat(decodedRelative.targetPath(), "relativeTarget").isEqualTo(relative);
		requireThat(decodedAbsolute.targetPath(), "absoluteTarget").isEqualTo(absolute);
	}

	/**
	 * Captures the same Path component shape used by immutable source evidence.
	 *
	 * @param targetPath exact source path
	 */
	private record PathValue(Path targetPath)
	{
	}
}
