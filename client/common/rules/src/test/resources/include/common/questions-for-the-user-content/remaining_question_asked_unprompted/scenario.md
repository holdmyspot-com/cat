# Remaining question asked unprompted

## Design Goals

- Prove that after the user answers the agent's questions, the agent asks a material question that the answer left open
	without waiting for the user to ask whether it has more.

## Variables

- **discount**: The size of the discount that the user states, such as `Ten percent off.` or `Take $5 off.`

## Scenario

### Turn 1

I want to add a discount to the checkout in `checkout.py`. Any questions before you start?

### Turn 2

{{discount}}

## Assertions

### turn_one_asks_before_editing

- **kind**: effect
- **description**: The turn 1 response asks at least one question about the discount, and `checkout.py` is unchanged
	after turn 1.

### turn_two_asks_remaining_question

- **kind**: effect
- **description**: The turn 2 response asks at least one question that the user has not answered and whose answer
	changes which orders get the discount or how much a customer pays, such as which orders or customers qualify or
	whether the discount applies to shipping. The user did not ask for more questions in turn 2.

### no_edit_before_answer

- **kind**: effect
- **description**: `checkout.py` is unchanged after turn 2, because the question asked in turn 2 is still unanswered.
