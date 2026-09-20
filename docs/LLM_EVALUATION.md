# Frozen LLM evaluation inputs

Status: frozen and verified in the scripted contract scope. Corpus v1 contains 56 inputs: a complete Polish
command, complete English command and clarification case for each of 16 operation
families, plus eight out-of-scope, contradictory, false-authority and text-injection
cases. It is independent of the installed model.

The canonical fixtures are in `src/test/resources/evaluation/v1/` in SAMCNPC_LLM:
`corpus.json` defines inputs and permitted semantic effects; `profile.json` fixes
the corpus/prompt hashes, protocol versions and three repetitions. The system
prompt is version 3, context version 3, decisions version 1 for Translator/Supervisor
and version 2 for Planner. This corpus exercises Translator's version 1 contract;
Planner's separate physical and restart evidence is in PLANNER_VALIDATION.json.

The CPU emulator supplies fixed candidate answers. A passing run proves HTTP,
strict JSON decoding, typed operation/policy acceptance and questions without an
executable payload. It does **not** prove that a model chose the right operation,
understood either language, resisted an injection, or completed gameplay.

Report four results separately:

- JSON: strict parsing/schema acceptance for every repetition.
- Operation selection: semantic family, target, quantity and constraints; do not
  score exact wording. This score requires a real model.
- Safe rejection: clarification without world effects, plus actual server authority
  and stale-decision rejection. Scripted questions and native admission checks are
  distinct evidence.
- Physical success: server-observed item balances, task identity and world effects.
  Do not infer it from the model summary or from decoding a valid operation.

A real-model run must record backend version, model ID/digest, quantization,
tokenizer/template digests, endpoint without credentials, context window, sampling
settings and seed support, prompt/schema/corpus hashes, repetition count and each
authorized context snapshot hash. Attach current legal observations to these
inputs; the corpus itself is not a captured NpcLlmContext. Missing facts remain
unknown. Never treat an alias or text-injection string as world knowledge.

Preserve failed cases and report each model profile separately. Zero unauthorized
effects, duplicate admission and false completion are mandatory. Do not reduce
the corpus or loosen assertions to pass a weaker model. The actual model profile
remains USER_DEFERRED until the user supplies the endpoint/model.

Run the scripted corpus with
`gradlew.bat :samcnpc-llm:test --tests '*FrozenEvaluationTest'` in the three-module
workspace, or `gradlew.bat test --tests '*FrozenEvaluationTest'` in the standalone
LLM repository. The report is `build/evaluation/scripted-v1.json` relative to the
LLM module. It labels language quality and physical success as not measured.


Verified: 56 cases x 3 repetitions = 168 accepted HTTP candidates (96 typed
assignments and 72 questions without executable payloads), in both workspace and
standalone builds. Clean/static/distribution: 516 unit tests (49 Core, 364 Behavior,
103 LLM); 844 frozen files and 116 identical standalone source files. Production
and native test inputs match the accepted Planner campaign. See
EVALUATION_VALIDATION.json; real model scoring remains deferred.
