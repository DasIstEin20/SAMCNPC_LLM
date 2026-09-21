# Context rejection observed on 2026-09-21

Scope: read-only diagnosis of the installed client log/configuration and current
source. No model request, configuration edit or runtime fix in this session.
Times below are Europe/Warsaw. Player identifiers and goal text are omitted.

| Time | Evidence in Minecraft latest.log | Meaning from source |
|---|---|---|
| 12:35:14.377 | WAITING / INPUT_TOKEN_BOUND_EXCEEDED | InferenceWork rejected the serialized request before provider.complete |
| 12:35:50.805 | MODEL_CONTEXT_WINDOW_EXCEEDED | Configuration readiness rejected input + output exceeding declared context |
| 12:36:13.401 | Stop reports attempts 1/24, input 63488/1523712 | Pre-dispatch reservation was charged; this is not measured provider token usage |
| 12:36:15.801 | GOAL_FORGOTTEN | User cleared the stopped goal |
| 12:36:19.766 | MODEL_CONTEXT_WINDOW_EXCEEDED | Clearing the goal did not fix the configuration mismatch |

The current configuration reads inputTokens=63488, maxOutputTokens=4096,
contextWindow=65536, templateReserve=2048 and maxContextBytes=65536.
63488 + 4096 = 67584, exceeding the declared context by 2048 tokens. The previous
validated output setting was 1024: restoring that value fixes this configuration
inequality only. An alternative input allocation <=61440 fits 4096 output but
makes the separate input-bound rejection more likely until request sizing is fixed.
Do not increase the declared context beyond the model's actual loaded setting.

VerifiedByteLevel.upperBound currently uses the entire serialized HTTP JSON byte
count plus the 2048-token template reserve. This includes schema/JSON overhead
and is deliberately conservative, not the actual tokenizer count. With input
63488, only 61440 serialized bytes fit this guard, below the HTTP cap of 65536.
The observed error establishes that this guard failed; the log does not record
exact payload bytes, section sizes or actual input tokens. It does not prove
that Qwen actually exhausted its context. InferenceWork checks this before HTTP.
The model's precise rejected request cannot be reconstructed from this log alone.

ChatCompletionCodec creates two messages per request: system instructions with
operation contract, and the current structured NPC state. It does not append an
unbounded chat transcript. NpcContextEncoder already reduces optional detail to
fit STATE <=24 KiB, but does not retry that projection based on the size of the
complete request. The full operation contract/schema and dynamic state compete
for a narrow remaining envelope. A history-only compact command would not fix
the configuration mismatch or this oversized base request.

The newest LM Studio server log inspected was empty; `lms ps --json` returned no
loaded model at inspection time. No backend overflow trace was available there.
These observations do not establish its state at the earlier Minecraft timestamps.

Planned remedy: clearer numeric diagnostics/config validation, smaller catalog
representation, automatic whole-request compaction, optional manual compact and
verified token accounting. See the 2026-09-21 addendum in
[the integration plan](LLM_INTEGRATION_PLAN.md). Quota policy is a separate issue:
current hourly caps are 360/NPC and 720/server, with 24 calls per goal and no daily cap.
