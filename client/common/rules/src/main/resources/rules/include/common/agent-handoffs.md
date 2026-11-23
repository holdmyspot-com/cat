# Workflow Data Transfers and Agent Handoffs

## Design Goals

<!--
A contract author returned a 290 KB proposal as its final message, 215 KB of it filler text that the model typed. Each
correction round made it send the whole proposal again, its context filled, compaction dropped the proposal, and
re-reading it in one command returned output trimmed to a fraction of its size, so the author could not correct it.
-->
- Preserve workflow inputs at each receiving boundary. Code delivers any payload it can obtain directly to the
	receiving program or model, and each receiving agent reads only what its next decision needs.
- Let an agent revise a large artifact without rewriting or re-reading all of it, and let it recover the artifact
	after its context is compacted or reset.
<!--
Agents that meant to notify a subagent called a user-input tool with placeholder titles such as "Ignore" or
"STOP" right after reading this rule; the message never reached the subagent and the user received an empty question.
-->
- Deliver every message meant for another agent to that agent, so that none reaches only the user as a request for
	input.

## Guidance

When a workflow passes data to a program or model, have code obtain it from its authoritative source and deliver it
directly to the receiver whenever code can do so. Use a documented input, or pass the path to a file the receiver
can read. A model must never transcribe an existing payload, even when it is short. Reuse its authoritative file
when one exists. If a new file is needed, have code read or generate the data and write the file.

When a workflow requires judgment or new content from a model, let the model produce that result once. Have code
capture the result at that boundary, or let the model author it directly in its destination file if capture is
unavailable. Use code or the resulting file for every subsequent transfer.

When passing a file, verify that the receiver can read the path in its own environment. If the receiver cannot,
have code deliver the file to a store the receiver can reach and pass that path. If neither code nor a readable
file can provide the data, report the missing transfer capability before continuing with work that depends on it.
When the data must arrive byte for byte, verify equality at the destination before relying on the transfer.

An agent can write a short new instruction, verdict, finding, or correction directly in its message. For a
handoff that is large, will be revised across rounds, or has multiple reviewers, put the content in a readable
file and send the path and the sections the recipient needs. Keep the instructions for the recipient in the message.
In a workflow that consumes an agent's result as input, capture that result when the agent produces it and use
the data-transfer procedure above for each consumer.

Send that message or path through the tool that delivers to the recipient agent, such as a collaboration or messaging
tool; a tool that asks the user for input never reaches another agent.

When revising an artifact in response to feedback, change only the parts the feedback concerns. If you cannot write the
artifact, send new revision instructions or use a readable file for the replacement text. Do not regenerate the
whole artifact to change part of it. When reporting problems in an artifact, locate each problem precisely, such as a
file and line, a heading, or a JSON path, so that the recipient can read and change only that part.

When an agent needs part of a large artifact, read only that part, for example with a JSON query, a line range, or a
search. When it needs all of it, read it in parts that each fit the tool-output limit, and check that each part
arrived whole; a single command whose output is trimmed silently loses the rest. After a context reset or compaction,
recover content exchanged as an artifact from its file, not from earlier messages or logs.
