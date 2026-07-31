# Spring AI in Corpus: ChatClient, advisors, and provider portability

Spring AI is the framework layer between Corpus and the model providers. It
contributes three things the application would otherwise hand-roll: a fluent
chat API with a middleware chain, a portable vector-store abstraction, and
provider autoconfiguration that turns model selection into configuration.

## ChatClient and the advisor chain

All chat goes through a single `ChatClient`. Its defining feature is the
advisor chain: every request passes through an ordered list of advisors that
can rewrite the prompt, act on the response, or short-circuit entirely —
middleware for model calls. Corpus registers a memory advisor that loads the
last twenty messages of the active conversation into the prompt and persists
new turns afterwards, keyed by a conversation id parameter. Tool calling is
handled by a built-in advisor that executes tool invocations requested by the
model and loops the results back until the model produces a final answer.

## Function calling with user context

Corpus exposes a document-metadata lookup as a model-callable tool: a plain
Java method annotated with a tool description whose JSON schema is derived
from its parameters. The subtlety is identity: the model must never choose
which user's data a tool reads. The acting user's id travels through the
tool-context mechanism — set by the service when it builds the request,
invisible to the model, and read by the tool implementation to scope its
query. The model decides *whether* to call the tool; the application decides
*as whom*.

## Streaming

`ChatClient` returns streamed responses as a Reactor `Flux` of chunks. Corpus
consumes that flux with blocking iteration on a virtual thread and forwards
deltas as Server-Sent Events, which keeps the programming model imperative
while the wire stays streaming. Usage metadata (token counts) arrives on the
final chunk of the stream, which is why the service tracks the last chunk
carrying non-zero usage before emitting its usage event.

## Provider portability

The same code runs against Anthropic, OpenAI, or a local Ollama daemon. Each
provider ships as a starter dependency, and `spring.ai.model.chat` selects
which one autoconfigures the active `ChatModel` — `anthropic`, `openai`,
`ollama`, or `none` to disable chat entirely. Embeddings select
independently via `spring.ai.model.embedding`, including a `transformers`
option that runs an ONNX sentence-transformer entirely in-process: no
network, no key, deterministic vectors — which is exactly what CI uses.
Model ids and options are properties too, so switching the local chat model
from one Ollama tag to another is an environment variable, not a build.

## The vector-store abstraction

Ingestion writes chunks through Spring AI's `VectorStore` interface —
documents in, embeddings handled internally, metadata preserved — and vector
search reads through the same interface with a portable filter-expression
DSL for metadata scoping. Corpus points it at pgvector, but the interface has
a dozen backends; the hybrid-search SQL that reaches into the same table for
full-text ranking is deliberately confined to one DAO class, so a hypothetical
store migration would touch exactly two files: configuration and that DAO.
