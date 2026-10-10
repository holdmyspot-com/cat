package io.github.cowwoc.cat.common.git.replay;

/**
 * The sealed tree transition for one retained output in final-history order.
 *
 * @param outputId the retained output identifier
 * @param beforeTree the tree inherited from the previous retained output, or the replay base for the first output
 * @param afterTree the tree after every batch for this output, or {@code null} while the output is open
 */
public record OutputWitness(String outputId, String beforeTree, String afterTree)
{
}
