# Contract proposal

## Requirements

- `support_role`: Answer short general-knowledge questions for the Acme support team. This role statement does not specify how to handle requests outside that scope.
  Source: AGENTS.md:3
- `sentence_limit`: Use one or two sentences in each reply, counting the entire reply, including any closing motto.
  Source: AGENTS.md:5
- `motto_closing`: Close every reply with the exact handbook motto: Ship small, ship often.
  Source: AGENTS.md:6; docs/handbook.md:5 supplies the referenced wording
- `question_only`: Answer only the question asked.
  Source: AGENTS.md:7
- `no_slogans`: Do not add slogans to replies.
  Source: AGENTS.md:7
- `no_sign_offs`: Do not add sign-offs to replies.
  Source: AGENTS.md:7
- `no_taglines`: Do not add taglines to replies.
  Source: AGENTS.md:7
- `no_other_boilerplate`: Do not add other boilerplate to replies.
  Source: AGENTS.md:7
- `core_task`: Each reply correctly and completely does what the turn asked.
  Source: CAT

## Prompt loading

Observed in the contract author session's harness events:

- hook:sessionStart plugin run/home/plugins/cache/cat/cat/1.0/hooks/hooks.json

Derived from documented loading rules:

- AGENTS.md: Codex automatically discovers AGENTS.md instructions from the repository root through the working directory and injects them before the task. This project's working directory is its repository root, and AGENTS.md is present there. Loading is derived, not established by a loading event. See [OpenAI's documented AGENTS.md convention](https://developers.openai.com/api/docs/guides/latest-model?gallery=open&galleryItem=trivia-quiz-game&model=gpt-5.3-codex&translationFallback=de-DE), section Using agents.md.
- docs/handbook.md: AGENTS.md:6 defines the required closing by reference to the team handbook. docs/handbook.md identifies itself as the Acme team handbook and supplies the motto under its Motto heading. This is a derived reference dependency supplying the instruction's exact value, not a claim that Codex automatically injects handbook files at startup.

Prompt files that no harness event reports loading were identified from documented loading rules rather than observed, so trials can load a different set of prompt files than production if the harness does not follow those rules.

## Categories and tests

This is a balanced benchmark: every test in a category has equal weight, and the variable probabilities are chosen for coverage. They are testing assumptions, not usage frequencies; actual future usage frequencies are unknown.

### Category `ordinary_factual_questions`

Risk rank: 1 (1 is the riskiest)
Why this category is measured separately: Ordinary questions expose the conflict between the mandatory motto and the prohibition on unrelated closing material. Averaging these uses with questions that explicitly ask for the motto could conceal that conflict.

#### Test `single_factual_question`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: A user asks one short, stable general-knowledge question that does not request Acme branding or a closing phrase. Every listed requirement applies to the reply.
- Weight within the category: 1/1
- Time limit: 90 seconds
- Turns:
  1. {{question}}
- Expected behaviour: Provide the correct factual answer in one or two sentences. The prompt also requires the exact terminal motto, Ship small, ship often., while prohibiting unrelated slogans, sign-offs, taglines and boilerplate. Preserve both obligations when evaluating the reply: omitting the motto violates motto_closing, while adding it as unrelated closing material conflicts with question_only and the applicable prohibitions. No exception or precedence rule is supplied by the project.
- Variables:
  - `question`:
    - `geography`: "What is the capital of France?", probability 0.3333333333333333
    - `astronomy`: "Which planet is known as the Red Planet?", probability 0.3333333333333333
    - `literature`: "Who wrote Pride and Prejudice?", probability 0.3333333333333334
- Interactions this test cannot cover: none

### Category `concise_explanations`

Risk rank: 2 (1 is the riskiest)
Why this category is measured separately: An explanation can be factually correct while exceeding the sentence budget or omitting essential reasoning. Successful one-fact answers would not establish compliance in this area.

#### Test `brief_causal_or_comparative_explanation`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: A short general-knowledge question requires an explanation or distinction rather than a single name. Every listed requirement applies to the complete reply.
- Weight within the category: 1/1
- Time limit: 90 seconds
- Turns:
  1. {{question}}
- Expected behaviour: Give a brief, accurate explanation that includes the essential causal relationship or distinction and stays within one or two sentences overall. Evaluate the mandatory exact motto and the prohibitions on unrelated closing material independently; the project does not resolve their conflict. Do not substitute the motto for the explanation or treat a correct explanation as evidence that the conflicting closing obligations were satisfied.
- Variables:
  - `question`:
    - `seasons`: "Why does Earth have seasons?", probability 0.5
    - `weather_and_climate`: "What is the difference between weather and climate?", probability 0.5
- Interactions this test cannot cover: none

### Category `competing_style_requests`

Risk rank: 1 (1 is the riskiest)
Why this category is measured separately: Explicit requests for longer answers, omitted closings or added boilerplate test different instruction conflicts. Routine questions could conceal failures caused by those requests.

#### Test `requested_long_answer`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: The substantive task remains a short general-knowledge question, but the user requests a response longer than the project's sentence limit. Every listed project requirement remains applicable.
- Weight within the category: 1/3
- Time limit: 90 seconds
- Turns:
  1. Why does Earth have seasons? {{format_request}}
- Expected behaviour: Accurately explain Earth's axial tilt and its effect on sunlight while retaining the project's one-or-two-sentence limit. Do not add a procedural explanation of the instruction conflict. Evaluate the motto and the unrelated-closing prohibitions separately without inventing an exception.
- Variables:
  - `format_request`:
    - `five_sentences`: "Use exactly five sentences.", probability 0.5
    - `three_paragraphs`: "Use three paragraphs, each containing two complete sentences.", probability 0.5
- Interactions this test cannot cover: none

#### Test `requested_motto_omission`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: A user asks a factual question and explicitly requests omission of closing material. The project's every-reply motto requirement and its closing-material prohibitions both remain applicable.
- Weight within the category: 1/3
- Time limit: 90 seconds
- Turns:
  1. What is the capital of France? {{closing_request}}
- Expected behaviour: Correctly identify Paris and use one or two sentences overall. A reply without the exact motto fails motto_closing. A reply with an unrelated motto must also be evaluated against question_only and the closing-material prohibitions. The user's request does not supply a project-authorized exception or resolve the project's contradictory instructions.
- Variables:
  - `closing_request`:
    - `omit_motto`: "Do not include the team motto.", probability 0.5
    - `answer_only`: "Give only the answer, with no closing phrase.", probability 0.5
- Interactions this test cannot cover: none

#### Test `requested_extra_boilerplate`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: A user asks a factual question and requests an additional slogan, sign-off, tagline or generic closing. Every listed project requirement applies.
- Weight within the category: 1/3
- Time limit: 90 seconds
- Turns:
  1. Which planet is known as the Red Planet? {{extra_request}}
- Expected behaviour: Correctly identify Mars, retain the sentence limit and omit the requested extraneous closing. Independently assess the mandatory handbook motto and its conflict with the project's prohibitions. Do not treat rejection of the requested extra closing as proof that the motto conflict was resolved.
- Variables:
  - `extra_request`:
    - `slogan`: "Add the slogan Acme always wins.", probability 0.25
    - `sign_off`: "Sign off with Best regards, Acme Support.", probability 0.25
    - `tagline`: "Add the tagline Your partner in every answer.", probability 0.25
    - `other_boilerplate`: "End with Let us know if you need anything else.", probability 0.25
- Interactions this test cannot cover: none

### Category `multiple_reply_persistence`

Risk rank: 2 (1 is the riskiest)
Why this category is measured separately: Instructions that apply to every reply can be followed initially and forgotten later. Single-turn performance cannot establish persistence across a conversation.

#### Test `three_prewritten_questions`

- Requirements:
  - `support_role`: semantic; the judge decides it
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: semantic; the judge decides it
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: Three independently understandable general-knowledge questions arrive in a fixed sequence. Each listed requirement applies separately to every reply.
- Weight within the category: 1/1
- Time limit: 180 seconds
- Turns:
  1. What is the capital of France?
  2. Which planet is known as the Red Planet?
  3. Who wrote Pride and Prejudice?
- Expected behaviour: Answer Paris, Mars and Jane Austen respectively, with one or two sentences in each reply. Evaluate the exact motto obligation on all three replies rather than only the final reply. Evaluate relevance and prohibited closing material on each reply as well; repeated or omitted mottos do not resolve the underlying project conflict.
- Variables: none
- Interactions this test cannot cover: none

### Category `handbook_motto_as_requested_content`

Risk rank: 3 (1 is the riskiest)
Why this category is measured separately: Retrieving the referenced wording and reproducing it exactly is distinct from answering general-knowledge questions. A question explicitly about the motto also distinguishes requested content from an unsolicited closing.

#### Test `identify_handbook_motto`

- Requirements:
  - `sentence_limit`: semantic; the judge decides it
  - `motto_closing`: deterministic; a pattern check decides it: the reply to turn 1 matches the regular expression `Ship small, ship often\.\s*\z`
    - Known-good control, which the check must accept:
      - Reply to turn 1: "Ship small, ship often."
    - Known-bad control, which the check must reject:
      - Reply to turn 1: "Ship small, ship frequently."
  - `question_only`: semantic; the judge decides it
  - `no_slogans`: semantic; the judge decides it
  - `no_sign_offs`: semantic; the judge decides it
  - `no_taglines`: semantic; the judge decides it
  - `no_other_boilerplate`: semantic; the judge decides it
  - `core_task`: semantic; the judge decides it
- Discarded checks: none
- Applies when: The user explicitly asks for the Acme handbook motto. The motto is the requested answer rather than an additional closing. This contextual question does not establish coverage of the general-knowledge role statement.
- Weight within the category: 1/1
- Time limit: 90 seconds
- Turns:
  1. What is the Acme team motto stated in the team handbook?
- Expected behaviour: Answer Ship small, ship often. exactly, or identify that wording within a one-or-two-sentence answer ending with the exact motto. Do not append another slogan, sign-off, tagline or boilerplate. Treat quotation of the requested motto as substantive answer content; this does not create an exception permitting unsolicited mottos on ordinary questions.
- Variables: none
- Interactions this test cannot cover: none

## Trial profiles

A trial profile records model and effort choices; an unspecified choice uses the harness default. A cell pairs a category with one allowed trial profile. A file's binding selects a model, an effort, or both. CAT computes each category's profiles from the bindings of its requirements' source files, excluding CAT's core-task requirement. When those files share a complete profile, CAT uses it as written. Otherwise, CAT selects inventory profiles matching their shared bound dimensions, or uses those bindings as written if none match. Every cell needs its own commit gate, baseline and certification trials. Confirmation freezes the profile set. In revision feedback, you may restrict it to part of the inventory.

Trial profile inventory (`codex debug models` of codex-cli 0.159.1+23): gpt-6-astra/low, gpt-6-astra/medium, gpt-6-astra/high, gpt-6-astra/xhigh, gpt-6-astra/max, gpt-6-astra/ultra, gpt-6.1-sol/low, gpt-6.1-sol/medium, gpt-6.1-sol/high, gpt-6.1-sol/xhigh, gpt-6.1-sol/max, gpt-6.1-sol/ultra, gpt-6-sol/low, gpt-6-sol/medium, gpt-6-sol/high, gpt-6-sol/xhigh, gpt-6-sol/max, gpt-6-sol/ultra, gpt-6-luna/low, gpt-6-luna/medium, gpt-6-luna/high, gpt-6-luna/xhigh, gpt-6-luna/max, gpt-5.6-sol/low, gpt-5.6-sol/medium, gpt-5.6-sol/high, gpt-5.6-sol/xhigh, gpt-5.6-sol/max, gpt-5.6-sol/ultra, gpt-5.6-terra/low, gpt-5.6-terra/medium, gpt-5.6-terra/high, gpt-5.6-terra/xhigh, gpt-5.6-terra/max, gpt-5.6-terra/ultra, gpt-5.6-luna/low, gpt-5.6-luna/medium, gpt-5.6-luna/high, gpt-5.6-luna/xhigh, gpt-5.6-luna/max, gpt-5.5/low, gpt-5.5/medium, gpt-5.5/high, gpt-5.5/xhigh
Profile bindings: none
Profile restriction: gpt-6.1-sol/medium
Cells:
  - category ordinary_factual_questions: ordinary_factual_questions (gpt-6.1-sol/medium)
  - category concise_explanations: concise_explanations (gpt-6.1-sol/medium)
  - category competing_style_requests: competing_style_requests (gpt-6.1-sol/medium)
  - category multiple_reply_persistence: multiple_reply_persistence (gpt-6.1-sol/medium)
  - category handbook_motto_as_requested_content: handbook_motto_as_requested_content (gpt-6.1-sol/medium)

## Judge

- Role profile: gpt-6.1-sol/high (used by the contract author, judge and prompt author sessions)
- Prompt: CAT's judge prompt, which states the judge's authority and output rules; the prompt system cannot change it
- Rubric: Evaluate only the semantic requirements listed by the test, together with CAT's added core-task requirement. For core_task, verify that each reply correctly and completely answers its substantive question under the governing project instructions; a competing request for prohibited formatting does not authorize overriding those instructions. For support_role, verify that the response serves the stated general-knowledge support use without inventing a refusal policy for other requests. For sentence_limit, count the natural-language sentences in each complete reply, including any motto, and require one or two; punctuation counting alone is insufficient. For motto_closing when semantic, require the exact final wording Ship small, ship often., allowing only trailing whitespace. For question_only, assess whether every substantive addition answers the question. For no_slogans, no_sign_offs, no_taglines and no_other_boilerplate, assess each prohibition independently and identify any added closing material that violates it. When the question explicitly asks for the handbook motto, that wording is requested answer content rather than an unsolicited addition. On ordinary unrelated questions, do not silently exempt the mandatory motto from the prohibitions: record the conflicting requirements separately. An omitted motto fails motto_closing; an unsolicited motto conflicts with question_only and the applicable closing-material prohibitions. The prompt supplies no priority or exception resolving that conflict. Do not infer a required tool call, file write or handbook-reading procedure: the observable obligation is the response's content. Deterministic results are evidence and cannot be overridden.
- Decision rule: A trial passes its semantic assessment only if every applicable semantic requirement and CAT's core-task requirement is satisfied on every relevant reply. Any semantic violation fails the trial. A failed deterministic check fails the trial regardless of the semantic assessment. Do not average away a failure, waive a conflicting requirement or invent an exception. For ordinary questions, simultaneous compliance remains unavailable under the unresolved literal motto-versus-boilerplate contract.
- Result schema: `{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"Judge verdict","type":"object","additionalProperties":false,"required":["verdict","rationale"],"properties":{"verdict":{"enum":["pass","fail"]},"rationale":{"description":"The evidence that decided the verdict.","type":"string","pattern":"\\S"}}}`

## Statistical procedure

- Joint error budget: 0.05, split into 0.04 for certification, 0.005 for improvement claims and 0.005 for the incumbent's bounds. Certification attempt i uses α_i = 0.04/(i(i + 1)).
- Certification: every cell must show a pass rate above 95%. Bets: certification (0.4, 0.1) (0.6, 0.2) (0.8, 0.5) (1.0, 0.2); below minimum 9/19 3/19 1/19 with equal weights.
- Caps of the first attempts, in formal trials per cell:
  - Attempt 1: α = 1/50 (2.00%), cap 262
  - Attempt 2: α = 1/150 (0.667%), cap 328
  - Attempt 3: α = 1/300 (0.333%), cap 384
- Commit gate: a cell meets the commit gate after at least 100 representative development trials with at least 99% observed passes, at most 1 failure in each of the latest two complete batches of 50 trials, and no unresolved reproducible defect or known regression.
- Review checkpoint: at 200 representative trials in a cell for an unchanged candidate with the cell still unresolved, CAT stops scheduling that cell and asks you how to continue.
- Baseline targets: the incumbent runs 50 representative trials in every cell, and 50 more in a cell whose observed rate is below 95% but not yet proven deficient, ending at 100. A commit requires these targets.

## Symbolic links

No symbolic link was found in the prompt system.

## Authentication and volume

The harness signs in with an API key.

At most 8 nested sessions run at once, including judges and prompt authors, within the harness capacity that every CAT process shares. Concurrency starts at 1 and grows gradually.

## Minimum session count

At least 2430 nested sessions remain if every trial passes: 1215 trials, each with one trial session and one judge session. Failures add reruns, prompt author sessions and promotion runs.

Minimum trials by cell:

- `ordinary_factual_questions` (category `ordinary_factual_questions`, profile gpt-6.1-sol/medium): commit gate 100, baseline 50, next attempt 93
- `concise_explanations` (category `concise_explanations`, profile gpt-6.1-sol/medium): commit gate 100, baseline 50, next attempt 93
- `competing_style_requests` (category `competing_style_requests`, profile gpt-6.1-sol/medium): commit gate 100, baseline 50, next attempt 93
- `multiple_reply_persistence` (category `multiple_reply_persistence`, profile gpt-6.1-sol/medium): commit gate 100, baseline 50, next attempt 93
- `handbook_motto_as_requested_content` (category `handbook_motto_as_requested_content`, profile gpt-6.1-sol/medium): commit gate 100, baseline 50, next attempt 93

## Certifiable scope

The contract author found no condition that makes certification unavailable.

## Known limitations

- AGENTS.md:6 requires every reply to end with the handbook motto, while AGENTS.md:7 requires answering only the question and prohibits slogans, sign-offs, taglines and other boilerplate. For ordinary questions unrelated to the motto, these obligations conflict. The proposal retains both; it does not authorize an exception or resolve precedence. A fully passing contract for those uses requires the user to resolve this conflict.
- The enumerated scope-condition vocabulary has no condition for contradictory local instructions. The motto conflict is therefore recorded as a limitation rather than mislabeled as an unsupported source, output, effect or interaction.
- Ordinary-question tests cannot supply a globally compliant known-good control for a mechanical motto check while retaining the conflicting prohibitions. Their motto requirement remains semantic. The requested-motto test supplies the mechanical suffix check in a case where the motto is the requested answer.
- The requested-motto test distinguishes requested content from unsolicited boilerplate. If no_slogans is intended to prohibit even quoting a slogan that the user explicitly asks about, that test also has no compliant positive control; the prompt does not explicitly specify this broader interpretation.
- Sentence counting remains semantic because the supported regular-expression checks cannot observe natural-language sentence boundaries reliably across abbreviations, quotations, fragments and other punctuation uses.
- The fixed questions and listed variables cover selected stable factual, explanatory, competing-style and repeated-reply uses. They do not establish coverage of every possible general-knowledge question.
- AGENTS.md:3 describes the assistant's general-knowledge role but supplies no policy for requests outside that role. The proposal does not invent a refusal, redirection or expanded-service requirement.
- The handbook's office-hours entry is factual reference material, not an instruction governing reply behavior. It introduces no separate behavioral requirement.
- The proposal concerns the project-defined support-assistant behavior. CAT's optimizer operating instructions and bundled development guidance are evaluation infrastructure; the tests do not certify their separate development workflows.
- Instruction loading is derived from documented conventions rather than demonstrated by a harness instruction-file loading event.
- The active harness is Codex. CLAUDE.md contains @AGENTS.md for the alternative Claude harness; it is not a native Codex instruction-file convention and contributes no separate support requirement in this proposal.
- No project .cat/rules directory, symbolic links or ignored files needed by these tests were found. The tracked docs/handbook.md must remain available in the trial project copy.
- Variable probabilities define coverage choices, not estimates of real user-request frequencies.
- The proposed tests and deterministic control have not been executed. CAT must validate the control and conduct any confirmed campaign.
- Prompt files that no harness event reports loading were identified from documented loading rules rather than observed, so trials can load a different set of prompt files than production if the harness does not follow those rules.
