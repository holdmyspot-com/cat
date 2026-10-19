package com.holdmyspot.cat.common.git.replay;

/**
 * One reviewer-authored topic output declaration.
 *
 * @param id the manifest-local output identifier
 * @param subject the replacement commit subject in the format {@code type: summary}
 */
public record TopicOutputRequest(String id, String subject)
{
}
