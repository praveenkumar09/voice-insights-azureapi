# Voice Insights: 1-minute speech · The architecture diagram

**Job of this minute:** make Group IT and Risk relax. Nothing outside our walls. Nothing the company has not already approved.
**Length:** about 150 spoken words, so 1:00 with pauses. Show the diagram (`spark-architecture.png`) for the whole minute.

## What the diagram should show, left to right

```
Advisor (browser / phone, HTTPS + secure websocket)
        |
   Our Azure: AKS or Container Apps, one namespace
   +----------------------------+     +-----------------------+
   | Spark app (UI + API)       |---->| Azure OpenAI          |
   | Java / Spring Boot         |     | gpt-4.1, speech-to-   |
   | agent team runs INSIDE it  |     | text, embeddings,     |
   +-------------+--------------+     | text-to-speech        |
                 |                    +-----------------------+
        +--------+---------+
        | PostgreSQL       |  Redis (session cache)
        | + pgvector       |
        | profiles, runs,  |
        | packs, product   |
        | document search  |
        +------------------+
```

**Facts behind it (from `juno-poc-overview.md` §8, `voice-insights-k8s/README.md`, `pom.xml`)**
- Four Azure OpenAI models, in our own Azure OpenAI resource: gpt-4.1 (reasoning), gpt-4o-transcribe (speech to text), text-embedding-3-large (meaning search), gpt-4o-mini-tts (Spark's voice).
- PostgreSQL with pgvector holds profiles, runs, advice packs, product-document search and campaign vectors. Redis is only a session cache.
- Delivered as Docker containers on Azure AKS or Azure Container Apps.
- The agent orchestration is a library (LangGraph4j) *inside* our Java application, not a service we call.
- ID and card numbers are redacted on the server and never stored.

---

## The speech

*(Diagram on screen. Point to each box as you name it, then stop pointing.)*

> "Now, the question Group IT is already asking. What did you bring into our house?
>
> [pause]
>
> Nothing.
>
> [pause]
>
> This is our Azure. Our container platform. Our network.
>
> The brain is **Azure OpenAI**, the same service the company has already approved. Four models: one to think, one to listen, one to find meaning, one to speak. Inside our own Azure resource.
>
> Our data sits in **PostgreSQL**. The same database that holds the product search. No special database to buy. A session cache in Redis.
>
> The team of agents? Not a service we call. It runs *inside* our application.
>
> [pause]
>
> No third-party AI. No extra vendor. No data leaving our Azure.
>
> And before anything is stored, ID and card numbers are removed.
>
> [pause]
>
> It is built from parts Group IT already knows how to run, secure and audit.
>
> **That is why it can be in a pilot in weeks, not in a procurement cycle.**"

**WOW · stand still:** "It is built from parts we already know how to run, secure and audit."

## Say it honestly (EXCO and Group IT will check)

- **"No data leaving our Azure"** is true of the design: the app calls Azure OpenAI only. It holds only if the production Azure OpenAI resource is in our approved tenant and region. Confirm that before the day.
- **The proof-of-concept deployment is not the production one.** The pilot manifests currently use a Let's Encrypt certificate, Docker Hub images and Postgres and Redis as pods. For production, swap in our company's container registry, certificates and gateway, and managed Postgres and Redis. Do not say "this exact deployment is production".
- **Single sign-on** is not built yet; it is a phase-2 integration with the company's identity provider. Do not say it is done.
- **Do not claim a certification** (ISO, SOC) for Spark. Say "built on services we already approve".
- If asked "is there a prompt-output filter?": rules are in prompts and a server wording check; a full output filter is not yet there. Say the Compliance test pack checks it.

## Cold line

*"Built from parts we already know how to run, secure and audit."*
