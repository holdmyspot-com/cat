package io.github.cowwoc.cat.common.optimizer.state;

import java.io.IOException;

/**
 * Supplies a value while allowing an evidence write to report an I/O failure.
 *
 * @param <T> supplied value type
 */
@FunctionalInterface
public interface CheckedSupplier<T>
{
	/**
	 * Produces one value.
	 *
	 * @return produced value
	 * @throws IOException if the operation fails
	 */
	T get() throws IOException;
}
