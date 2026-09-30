package io.github.cowwoc.cat.common.optimizer.runner;
import java.util.Objects;
/**
 * Checked failure at a native start or cleanup boundary.
 */
public final class NestedRunException extends Exception
{
    private static final long serialVersionUID = 1L;
    private final transient NestedRunFailure failure;

    /**
     * NestedRunException at the native runner boundary.
      *
      * @param failure the failure value
     */
    public NestedRunException(NestedRunFailure failure)
    {
        super(Objects.requireNonNull(failure, "failure").reason());
        this.failure = failure;
    }

    /**
     * failure at the native runner boundary.
      *
      * @return the resulting value
     */
    public NestedRunFailure failure()
    {
        return failure;
    }
}
