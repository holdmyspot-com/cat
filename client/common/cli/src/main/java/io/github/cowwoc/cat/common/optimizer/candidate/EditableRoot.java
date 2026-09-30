package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A confirmed project-relative pathname at which candidate writes may occur.
 *
 * @param path exact normalized project-relative root
 * @param kind root grant kind
 */
public record EditableRoot(Path path, Kind kind)
{
	/** Validates an exact, nonempty project-relative root. */
	public EditableRoot
	{
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(kind, "kind");
		if (path.isAbsolute() || path.toString().isEmpty())
			throw new IllegalArgumentException("Editable root must be nonempty and project-relative: " + path);
		for (Path component: path)
		{
			if (component.toString().equals(".") || component.toString().equals(".."))
				throw new IllegalArgumentException("Editable root must not contain dot components: " + path);
		}
		path = path.normalize();
	}

	/** The pathnames granted by this root. */
	public enum Kind
	{
		/** Only the exact pathname, including when it is absent. */
		FILE,
		/** Regular files strictly beneath the pathname. */
		DIRECTORY
	}
}
