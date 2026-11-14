# Privacy

HOPA reads the screen to act on it. That is sensitive by default.

## Data inventory

| Data | Where | Why | Default retention |
|---|---|---|---|
| Screen text | In memory, then the model request | Decide the next action | Not stored |
| Node tree | In memory | Refs and clicks | Not stored |
| Screenshots | Local, opt-in | Debug and OCR fallback | Off by default |
| Run records | `.hopa/runs.jsonl` | Audit and replay | 30 days |
| Learned notes | Knowledge store | Reuse a known path | Until deleted |
| Model prompts | Provider | Produce a decision | Provider policy |
| API key | Env / HUKS | Authenticate | Until rotated |
| Diagnostics | Local bundle, opt-in | Support | 7 days |

## Consent

- The first run explains what is read and when.
- Screen capture is a separate consent.
- A model call is a network action; the user can run offline with the stub.
- Consent can be withdrawn; the next action stops.

## Redaction

Before text leaves the process, HOPA removes emails, phone numbers, card-like
numbers, passwords and OTPs. The rules are conservative; false positives are
preferred over leaks.

## Windows client

| Data | Where | Why | Retention |
|---|---|---|---|
| Conversations and turns | `%USERPROFILE%\.hopa\windows\conversations.json` | History and drafts | Until deleted |
| Run records | `%USERPROFILE%\.hopa\windows\records.json` | Audit and the run view | Until deleted |
| Pending approvals | `%USERPROFILE%\.hopa\windows\approvals.json` | Deny on the next start | Cleared on start |
| Settings (no secrets) | `%LOCALAPPDATA%\HOPA\settings.json` | Provider, bridge address, window state | Until changed |
| Model key, bridge token and refresh token | Windows Credential Manager (`hopa.model.key`, `hopa.bridge.token`, `hopa.bridge.refresh`) | Authenticate | Until cleared |
| Screen observations | In memory only | Decide and verify | Not stored |
| Screenshots | Not captured in 0.1.0 | - | - |

The model key travels to the sidecar once, in memory, over the local stdio pipe;
the sidecar never writes it to disk or logs it. A run record stores the action
(which can include typed text) and hashes, not the screen tree.

## Export and delete

- Records are plain JSONL and can be copied.
- `hopa-baseline records --path … --json` exports them.
- Delete the file and the screenshots directory to remove local history.
- In-app export/delete is not built yet.

## Encryption

- The PC store is plain JSONL. Use full-disk encryption.
- On-device encryption at rest is not implemented yet; this is a known gap.
- The API key must be stored in HUKS on device.

## No training

HOPA does not send user data for model training. The model provider's terms and
data-retention policy must be reviewed before a public release.

## Sub-processors

| Processor | Data | Notes |
|---|---|---|
| Model provider | Prompts and screen text | Chosen by the deployment; see ADR-0006 |
| App store / crash reporting | Crash metadata | Opt-in; not enabled by default |

## Android (0.1.0)

| Data | Where | Why | Retention |
|---|---|---|---|
| Screen text and tree | In memory, then the model request | Decide the next action | Not stored |
| Screenshots | Never by default; a run may take at most one per second | Evidence and OCR fallback | Not stored; a secure window yields nothing |
| Microphone | Only while the mic button is active | Dictation through the PC bridge | The WAV is sent once and not kept |
| Run records | Room `run_records` | Audit and replay | Last 100 rows |
| Chat history | Room `sessions`/`turns` | The user's threads | Until deleted; tombstones persist |
| Bridge token | Android Keystore | Authenticate to the PC | Until unpaired |
| Analytics / crash reporting | None | — | — |

Redaction happens before anything leaves the device: the observation producer
drops password fields and replaces emails, phones, cards and OTPs. The
disclosure screen (`Settings > Phone access`) states this and has a kill switch
that refuses every device action even when Android still has the service on.

### Play policy note

- **Accessibility**: declared with a disclosure screen, a kill switch, a
  self-package filter and fail-closed checks; it is the fallback path, the PC
  bridge is primary.
- **Package visibility**: `<queries>` lists only `MAIN`/`LAUNCHER` intents; no
  `QUERY_ALL_PACKAGES`.
- **Foreground service**: not declared yet. The planned type is `shortService`
  for a bounded run with an ongoing notification; until then a run lives with
  the activity.
- **Cleartext**: permitted by `network_security_config.xml` because the config
  language cannot express a private CIDR range; HTTPS endpoints keep the system
  trust anchors.

## Children

HOPA is not directed at children. A minor-protection policy is required before
a public release.

## Status

- Data inventory: done.
- Redaction: implemented and tested.
- Retention: default documented, not enforced in code yet.
- DPIA: draft outline only.
- Privacy policy: not published.
