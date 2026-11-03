---
depends-on:
  - ../common/request-execution.md
---
# Requesting User Input

## Design Goals

- Make every user-input tool call carry a complete question eligible under Request Execution whose answer would change a concrete decision
	that the agent faces, and make that question name the missing information and the action that depends on it.
- Deliver every status update, progress report or other note that needs no answer in the agent's response text, so
	that no user-input call carries one.
- Make the agent act on information or authorization that the conversation already gives, so that the user never
	repeats it in answer to a user-input call.
- Never use the user-input tool to ask again for a decision that an earlier question in the conversation asked, whether
	or not the user has answered it yet, or to ask whether to continue, pause or keep working on an active goal or work
	that the user told the agent to continue.
- Make every start of a subagent, and every message for a subagent or the parent agent, reach that agent through a
	collaboration tool, since the user-input tool reaches only the user.
- Make the agent handle a message from a subagent or the parent agent by replying to that agent through a
	collaboration tool when it needs a reply, by asking the user only when it leaves a decision that only the user can
	make, and otherwise by continuing its work, so that no user-input call acknowledges, dismisses or answers it.
- After a mistaken user-input call, make the agent state the mistake in one sentence of its response and continue,
	since a user-input call cannot cancel, stop or retract anything.

## Guidance

This rule governs only calls to a user-input tool, such as `request_user_input` or `request_user_input_async`. A
user-input tool reaches only the user. In each situation below, take the action that it names.

Apply Request Execution's question eligibility before selecting a user-input tool. For an eligible unresolved decision,
call the tool with one complete question naming the missing input and the action that depends on it. An answer changing
your next action is not sufficient eligibility: implementation alternatives also change actions and remain yours to
resolve.

When you have a status update, progress report or other note that needs no answer, such as what you are doing, what
you have finished, or that work is continuing, put it in your response text and keep working. Such a note needs no
answer even when it is worded as a question.

When the user has already given information or authorization in this conversation, such as the option to use, the
scope of a change, or permission to go ahead, act on it as given.

Before a user-input call, compare its question with every question that you have asked in this conversation, answered
or not. If one asks for the same decision, even in other words, do not call: act on its answer, or keep waiting for it.
The answer to a `request_user_input_async` call arrives later as a user message, so the absence of an answer is never a
reason to ask again. An active goal or an instruction to continue authorizes continuing that work: never ask whether to
continue, pause or keep working on it.

When you decide to start a subagent, make `spawn_agent` your next tool call. When you have a message, update,
follow-up, correction or progress question for a subagent or the parent agent, send it to that agent with
`send_message` or `followup_task`. This includes telling a subagent about a change to a file it works from, following
up on a result it sent, and asking about its progress after a wait times out. These collaboration tools are direct
function tools that you call like any other function tool; `exec`'s `ALL_TOOLS` and `tools` do not list them, so call
them directly.

When a message from a subagent or the parent agent arrives, such as a `MESSAGE` or a `FINAL_ANSWER`, it comes from that
agent. If it needs a reply, send the reply to that agent with `send_message` or `followup_task`. If it leaves a
decision that only the user can make, ask the user as described above. Otherwise, continue with your next work step.

A user-input call only asks the user a question, and it leaves earlier calls and work in progress as they are. After a
mistaken user-input call, state that it was a mistake in one sentence of your response and continue your work; that
sentence is the whole correction.
