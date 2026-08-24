# IntentGuard — Infrastructure Cost & Testing Tooling

A breakdown of what it costs and what tools are needed to build and fully test the
IntentGuard semantic firewall prototype.

**Bottom line:** This is a cheap project. Almost everything is free or open-source,
and the one metered cost (Gemini) is negligible for a hackathon. You can build and
fully test IntentGuard for **$0** on a local Linux VM with free-tier Gemini and MongoDB.

---

## The one thing that actually shapes your setup

`auditd`, the shell hook, the Unix domain socket, and the separate-service-account
reference-monitor model **only work on real Linux with root**. The team is on macOS,
so a Linux environment is required. That's the single most important infra decision —
everything else runs anywhere.

Two ways to get it, both effectively free:

- **Local Linux VM (recommended)** — UTM, Multipass, or VirtualBox on the Mac running
  Ubuntu. Cost: **$0**. Gives you root, auditd, and a clean demo environment you fully
  control. Best for a reliable live demo.
- **Small cloud VM** — a 2 vCPU / 4 GB instance if you'd rather work in the cloud.
  Roughly **$5–15/month**, and usually covered by free trial credits
  (AWS/GCP/Azure/Oracle all offer them). Not necessary, but handy for remote collaboration.

---

## Full cost table

| Component          | Tool                                                   | Cost                                        |
| ------------------ | ------------------------------------------------------ | ------------------------------------------- |
| Linux environment  | Ubuntu in a local VM (UTM/Multipass)                   | **$0**                                      |
| Database           | MongoDB Community locally, or Atlas M0 free tier (512 MB) | **$0**                                    |
| LLM                | Gemini API                                             | **$0 free tier**, or ~$5–10 if paid         |
| JDK + build        | JDK 21, Maven/Gradle                                   | **$0**                                      |
| Property/unit tests| jqwik, JUnit 5                                         | **$0**                                      |
| Integration tests  | Docker + Testcontainers (spins up MongoDB)             | **$0**                                      |
| Frontend           | Runs locally; Vercel/Netlify free tier if hosted       | **$0**                                      |
| Demo agent         | Your replay harness (Task 17), or an open-source agent | **$0**                                      |

**Realistic total: $0**, or **under ~$15** if you choose a cloud VM and paid Gemini for comfort.

---

## Gemini cost, quantified

The test suite is designed to barely touch the API: property tests and demo scenarios
use a **deterministic stub** (no calls), and only a handful of integration tests hit
live Gemini. The real usage is your own manual dev testing.

Each command scoring is a tiny call — a few hundred input tokens, ~50 output tokens.
Even at a few thousand calls across four weeks you're looking at roughly a couple
million input tokens.

Current Gemini API pricing (see the [official pricing page](https://ai.google.dev/gemini-api/docs/pricing)):
the Flash tier runs about $1.50 per million input tokens and Flash-Lite input is around
$0.10 per million (pricing rephrased for licensing compliance). So a full test cycle
lands around **$5–10 on the paid tier — and the free tier will very likely cover the
entire hackathon**.

> **Caveat:** The Gemini free tier lets Google use your content to improve its products.
> For hackathon test data that's usually fine, but don't feed it anything sensitive. If
> that matters, switch to the paid key — it's only a few dollars.

**Cost-control tips:**

- Use **Flash-Lite** for the semantic score (a cheap classification-style call).
- Keep prompts short.
- Cache identical `command + intent` pairs so replays during dev don't re-bill.

---

## Tools you'll install

- JDK 21, Maven or Gradle
- Docker Desktop (for Testcontainers-based MongoDB integration tests)
- A Linux VM host (UTM or Multipass on macOS)
- MongoDB — local install in the VM, or an Atlas free account
- A Gemini API key from Google AI Studio
- `auditd` and `bash-preexec` (or zsh) inside the VM

---

## One honest recommendation for the demo

For the "we stopped a hijacked AI agent live" moment, resist wiring a real third-party
agent into the live demo — it adds a network dependency that can fail on stage. Your
**deterministic replay harness (Task 17)** produces the identical, convincing result
with zero cost and zero risk. Keep a real-agent version as a backup if you want, but
demo from the harness.
