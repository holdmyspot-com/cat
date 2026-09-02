# Optimizer reuse audit

This historical audit records the replacement of the pre-certification optimizer. The current client has one
optimization and certification workflow. References to retired components below exist only to define what the release
audit must exclude.

| Former component | Decision | Current owner and acceptance evidence |
| --- | --- | --- |
| Common and Codex/Claude `skills/prompt-builder` | retain | It remains an independently invoked prompt-authoring skill. Package inspection proves its entry point remains available without routing to a retired command. |
| Common and Codex/Claude `skills/prompt-optimizer` | replace | The Commercial skill starts the local certification process. `CertificationProcessCommandTest`, `CertificationProcessExecutorTest`, and Commercial package inspection cover the replacement. |
| `PromptEvaluation`, `PromptEvidenceCheck`, their commands, prompts, rules, fixtures, and documentation | retire | Their source, tests, launchers, and packaged routes are removed. `CertificationProcessCommand` is the replacement command. The release artifact audit must prove that no retired route is discoverable. |
| Whole-prompt suite contract, suite store, and SPRT decision path | retire | No current certification caller uses them. `FamilySequentialEngine`, `FamilySequentialPolicyDocument`, and `CertifiedCertificateOperationTest` own certification for each prompt-test family. |
| SHA-256 and artifact-fingerprint methods | retain after extraction | `Fingerprints` owns UTF-8, file, and deterministic artifact fingerprints. `FingerprintsTest`, `OperationDirectoryTest`, `CertificationProcessExecutorTest`, `WorkExecutePlanTest`, `HarnessRunReceiptTest`, and `FrontendLaunchGateTest` cover its callers. |
| `RecordStore` | retain after extraction | `io.github.cowwoc.cat.common.RecordStore` owns atomic JSON/text persistence and locking. `OperationDirectoryTest`, `PromptTestIdentityLedgerTest`, `CertificationProcessExecutorTest`, `CandidateAllowanceLedgerTest`, and `HarnessEvaluationOperationTest` cover its callers. |
| `BoundedJsonLines` | retain after extraction | `io.github.cowwoc.cat.common.BoundedJsonLines` owns bounded JSON Lines reading for ledgers and harness transcripts. `CertificationProcessLedgerTest`, `DeletionReceiptLedgerTest`, `PromptTestIdentityLedgerTest`, `HarnessTranscriptTest`, `CandidateAllowanceLedgerTest`, and `FormalTrialLedgerTest` cover its callers. |
| `InvocationDirectory` | retain | The `workflow-temp` launcher owns CAT work-directory creation, deletion, and handoff for prompt-builder and feedback workflows. `InvocationDirectoryTest` and the packaged artifact-runner tests cover it. |
| `FileStore` and `PromptTestInput` | retire | No current source or test depends on them. The replacement consumes the current prompt-test contracts directly. |
| `CandidateBundle` | retain after extraction | `io.github.cowwoc.cat.common.optimization.development.CandidateBundle` owns immutable candidate prompt bundles. `CandidateBundleTest`, `DevelopmentComparisonOperationTest`, `HarnessCandidateGeneratorTest`, `HarnessDevelopmentTrialExecutorTest`, and `DevelopmentReadinessOperationTest` cover it. |
| Certification ledgers, decisions, policies, and formal-trial operations | retain after extraction | `io.github.cowwoc.cat.common.optimization.certification` owns formal certification by prompt-test family. `CandidateAllowanceLedgerTest`, `FamilySequentialEngineTest`, `FormalCertificationRoundTest`, `FormalTrialOperationTest`, and `CertificationProcessExecutorTest` cover it. |
| Harness evaluation request and artifact types | retain after extraction | `io.github.cowwoc.cat.common.runner` owns typed harness requests and idempotent execution evidence. `PromptInputTest`, `HarnessEvaluationOperationTest`, `ContractExtractionInputTest`, `SemanticEvaluatorOperationTest`, `HarnessCandidateGeneratorTest`, and `HarnessDevelopmentTrialExecutorTest` cover them. |
| `Statistics` | retain after extraction | `io.github.cowwoc.cat.common.optimization.certification.Statistics` owns current certification mathematics. `StatisticsTest`, `FamilyCertificationTest`, `FamilySequentialDecisionTest`, `CertificationCoverageSimulationTest`, `PairedPromptTestFamilyEngineTest`, `DecisionTest`, and `CertificationProcessExecutorTest` cover it. |
| `RunnerArtifactStore` | retain after extraction | `io.github.cowwoc.cat.common.runner.RunnerArtifactStore` owns immutable runner artifacts. `HarnessDevelopmentTrialExecutorTest` and `HarnessEvaluationOperationTest` cover it. |

## Required release audit

Before release, search all source, tests, documentation, configuration, scripts, and assembled Personal and Commercial
packages for every retired identifier. Remove every reference except this audit. Build both packages and prove that:

1. the retained prompt-builder skill is discoverable;
2. the Commercial certification-process skill is discoverable;
3. no retired command, launcher, skill, rule, fixture, or documentation route is discoverable; and
4. every retained current entry point reaches its supported behavior through the assembled artifact.
