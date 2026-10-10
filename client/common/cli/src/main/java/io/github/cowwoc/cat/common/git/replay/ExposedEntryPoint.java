package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One immutable, tool-derived user-selectable entry point in the replay range.
 *
 * @param id the harness-neutral entry-point identifier
 * @param kind the supported entry-point kind
 * @param paths the changed manifests that expose it
 */
public record ExposedEntryPoint(String id, String kind, List<String> paths)
{
}
