# LLM module rules

- This module may remain a no-op provider shell until Core + Behavior acceptance gates pass.
- It may depend on Core and Behavior public APIs, never their implementation internals unless an explicit API is added deliberately.
- No world mutation bypass.
- No Minecraft command/shell/script execution from model output.
- Candidate behavior data goes through the same schema + semantic validation as disk JSON.
- Provider HTTP/credentials/secrets exist only here.
- No network request at startup by default.
- Missing configuration must be a normal disabled state, not an exception.
- F02 follows docs/LLM_INTEGRATION_PLAN.md and ADR 0085 (2026-09-20): Translator, then Supervisor, then Planner; no model-requested body primitives or channel handover.
- Admit only validated known operations/amendments/controls through public Behavior APIs; provider work receives immutable data and never accesses the world.
- Build bounded legal context, react to decision events, retain current authority/revision checks, and let ordinary Behavior recovery work without inference.
- The two-account skin test is MANUAL_PENDING and does not gate autonomous implementation or closeout.
