# Agent Handoffs

## Design Goals

<!--
A contract author returned a 290 KB proposal as its final message, 215 KB of it filler text that the model typed. Each
correction round made it send the whole proposal again, its context filled, compaction dropped the proposal, and
re-reading it in one command returned output trimmed to a fraction of its size, so the author could not correct it.
-->
- When information passes between agents, keep each agent's context to what its next decision needs: content that is
  large, revised over rounds, or read by several recipients crosses the boundary as a durable artifact that the
  recipient can read, and the recipient reads only the parts it needs.
- Let an agent revise a large artifact without rewriting or re-reading all of it, and let it recover the artifact
  after its context is compacted or reset.

## Guidance

Before sending information to another agent, such as a delegated task, a returned result, a correction request, or a
message to another session, choose how it crosses the boundary:

- **Inline**: send the content in the message when it is short enough for the recipient to read in one tool output
  and no one will revise it after this message, such as a verdict, a short list of findings, or a quoted excerpt.
- **Artifact**: otherwise, write the content to a file that the recipient can read and send its path, what it
  contains, and which parts the recipient's decision needs. This applies when the content would approach the
  recipient's tool-output limit, will be revised across rounds, or will be read by more than one recipient. Confirm
  that the recipient can read the path: a remote session, another machine, or a sandbox that denies the path cannot.
  When it cannot, use a store that both can reach, or send the content inline in parts that each fit.

When designing a workflow or protocol whose agents exchange such content, make the artifact the exchange: the producer
writes or edits the file, and the consumer reads the file rather than parsing the producer's final message. Do not
require an agent to repeat the complete content in a message to report a change.

When revising an artifact in response to feedback, edit only the parts the feedback concerns. Do not regenerate the
whole artifact to change part of it. When reporting problems in an artifact, locate each problem precisely, such as a
file and line, a heading, or a JSON path, so that the recipient can read and change only that part.

When an agent needs part of a large artifact, read only that part, for example with a JSON query, a line range, or a
search. When it needs all of it, read it in parts that each fit the tool-output limit, and check that each part
arrived whole; a single command whose output is trimmed silently loses the rest. After a context reset or compaction,
recover the artifact from its file, not from earlier messages or logs.

When content is repetitive, generated, or otherwise deterministic, such as filler around a fact, a large table, or
copies of one template, produce it with a tool or generator and pass the call or the resulting file. Do not have a
model write that content token by token.
