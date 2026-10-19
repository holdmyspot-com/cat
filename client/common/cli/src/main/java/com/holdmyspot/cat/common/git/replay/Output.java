package com.holdmyspot.cat.common.git.replay;

/**
 * A commit expected to remain after replay.
 *
 * @param id the manifest-local output identifier
 * @param source the optional source commit for a mechanically preserved default output
 * @param subject the reviewer-selected replacement commit subject in the format {@code type: summary}
 */
public record Output(String id, String source, String subject)
{
}
