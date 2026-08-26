## Question

Front-end design pass for Aitken's session screen: surgically note every UI component,
why it's there instead of anywhere else, and what's deliberately left bare for future
features (autonomous-tagging confidence display, etc).

Type: grilling (HITL)
Status: closed
Blocked by: none

## Resolution

Closed. Adapting Prototype 1's DebugScreen directly rather than redesigning —
waveform canvas, tap buttons, session toggle are already proven on-device. Extended
with a range-tag toggle distinct from point-tap buttons, and a confidence indicator
left visibly present but unwired until ClassifierRunner exists.
