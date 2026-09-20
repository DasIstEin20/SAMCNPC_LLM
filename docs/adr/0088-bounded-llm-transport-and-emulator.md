# ADR 0088: Bounded OpenAI-compatible transport and CPU-only emulator

Date: 2026-09-20. Status: accepted; grouped clean/runtime verification passed.

The old public LlmProvider.propose(String): Result<BehaviorProposal> was an unused
shell and no LLM integration release exposed an implementation. Replace it with
complete(LlmRequest): LlmCall and close(), retaining status(). This is an explicit
source API change before the first LLM release. Candidate pack validation remains
separate through LlmBoundary and BehaviorPackValidationApi; no automatic conversion
of decision JSON into behavior packs is introduced.

Requests carry UUID and immutable system/context/schema strings, never server,
player, entity, ItemStack, NBT, facade or action handles. Candidate results are
untrusted JSON plus optional exact token counts. Only the later decision/admission
layer can authorize a Behavior operation. There is no world API in the transport.

Use Java17 HttpClient and already supplied Gson, with no new dependency. POST to
the administrator-configured base path plus /chat/completions, one non-streaming
choice, explicit JSON_SCHEMA or JSON_OBJECT profile, max_tokens and temperature.
No model discovery, startup requests, redirects or automatic profile downgrade.
Optional credentials are resolved only inside LLM, never included in result/logs.

There are at most two active exchanges per provider, two daemon I/O threads with
a bounded 64-entry work queue, and one deadline scheduler. Overload returns BUSY.
A bounded body subscriber cancels on byte overflow. A separate whole-request timer
also cancels bodies that stall after headers; the JDK header timeout alone does
not establish this guarantee. Cancellation and close complete once, remove active
state and cancel the underlying exchange. No blind transport retries are included;
the later scheduler owns fair admission, budgets, backoff and reconciliation.

Request UTF-8 and final encoded payload are capped at configured <=64KiB. Response
envelopes are capped at configured <=256KiB; candidate JSON is <=16KiB. HTTP JSON
gets duplicate/lexical/Unicode/depth/node/number bounds, one assistant choice and
an explicit stop result. Refusal, incomplete output, tool/function calls, malformed
JSON, unsupported content types, auth/rate-limit/service failures return stable
codes without raw server bodies or exception messages. Local decision-schema
validation remains mandatory after transport acceptance.

Tests use an in-process loopback HttpServer on an ephemeral port with deterministic
responses, delays and connection faults. It is test-only code: no model, downloads,
GPU or VRAM allocation. Actual Forge smoke drivers run it on an isolated test worker
and check ticking/rendering while a response body stalls. This proves the protocol
and isolation boundary; it does not prove a real model's reasoning or backend profile.

Real LM Studio/Ollama/model tests are USER_DEFERRED by the user and remain unverified.
L1 context, L2 decisions/admission, L3.3 scheduling and Translator/Supervisor/Planner
are separate unfinished requirements; this transport cannot execute any NPC work.

References (checked 2026-09-20):
- [Java17 BodySubscriber](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html)
- [LM Studio structured output](https://lmstudio.ai/docs/developer/openai-compat/structured-output)
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
