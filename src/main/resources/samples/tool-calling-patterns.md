# Tool-calling patterns for LLM applications

Letting a model invoke functions turns it from a text generator into something
that can act. This note describes the general patterns and their failure modes.

## The basic loop

The application advertises a set of tools, each with a name, a natural-language
description, and a schema for its arguments. The model may respond with a tool
call instead of prose; the application executes it, appends the result to the
conversation, and calls the model again. This repeats until the model produces a
final answer. Each iteration is a full model round trip, so a turn involving
tools costs several calls, and usage accounting must sum them rather than take
only the last.

## Descriptions are the interface

The model chooses tools based on their descriptions, so descriptions are prompt
engineering rather than documentation. Effective ones state what the tool
returns, when to prefer it over a similar tool, and what it will not do.
Overlapping descriptions produce erratic selection; the fix is usually to
sharpen the boundary between two tools or to merge them.

## Authority belongs to the application

The single most important rule: the model decides *whether* to call a tool, never
*as whom*. Identity and authorisation must be supplied by the application from
the authenticated request context and must not be derivable from tool arguments.
A tool that accepts a user identifier as a parameter is a privilege-escalation
primitive, because model output is influenced by retrieved content and any
content in the context window can attempt to steer it.

## Prompt injection through tool results

Tool results re-enter the context, so a tool returning attacker-influenced text
can carry instructions. Treating tool output as data rather than instruction,
keeping tools narrow, and making side-effecting tools require explicit
confirmation all reduce the blast radius.

## Idempotency and retries

Tools may be called more than once — by a retry, by a model that repeats itself,
or by a user resending a request. Read-only tools are naturally safe. Mutating
tools should be idempotent or should carry a caller-supplied key so that a
duplicate call is recognised rather than applied twice.

## Observability

A tool-calling turn is a small distributed system: several model calls, several
local executions, and error paths through both. Recording each tool invocation
with its arguments, duration, and outcome is what makes such turns debuggable,
and tracing them as spans under the request shows immediately whether latency
came from the model or from the tool.
