package io.github.cowwoc.cat.distribution.bundle.secrets;

import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;

import java.util.spi.ToolProvider;

/**
 * Creates {@link JlinkImageBuilder} instances with a caller-supplied {@code jlink} tool.
 */
@FunctionalInterface
public interface JlinkImageBuilderAccess
{
	/**
	 * Creates a builder using an in-process jlink tool.
	 *
	 * @param jlink the JDK jlink tool
	 * @return the builder
	 */
	JlinkImageBuilder create(ToolProvider jlink);
}
