package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * A reviewer's complete observable-behavior and pairwise split evidence.
 *
 * @param rows one observable behavior for every candidate-universe entry
 * @param pairs one split assessment for every unordered pair of rows
 */
public record ActionResultMatrix(List<ActionResultRow> rows, List<ActionPairAssessment> pairs)
{
}
