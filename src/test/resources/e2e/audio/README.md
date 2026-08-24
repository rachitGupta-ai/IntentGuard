# Audio Test Fixtures

This directory contains pre-recorded audio files of spoken Linux commands in Indian languages,
used as input to the Audio Translation E2E scenario.

## Naming Convention

```
{language_tag}_{command_index}.wav
```

- **language_tag** — BCP-47 tag for the spoken language (e.g., `hi`, `ta`, `bn`, `te`, `mr`, `gu`, `kn`, `ml`, `pa`, `or`)
- **command_index** — Zero-padded 3-digit integer matching the entry's position in the Command Corpus (`corpus.json`), starting at 001

### Examples

| File | Language | Corpus Entry |
|------|----------|--------------|
| `hi_001.wav` | Hindi | Command #1 |
| `ta_002.wav` | Tamil | Command #2 |
| `bn_003.wav` | Bengali | Command #3 |

## Format Requirements

| Property | Value |
|----------|-------|
| Container | WAV |
| Encoding | Linear PCM |
| Bit depth | 16-bit |
| Sample rate | 16 kHz |
| Channels | Mono |
| Max duration | 10 seconds |

These constraints match the `AudioClip` contract accepted by the `GeminiSpeechProvider`.

## Fallback Behavior

If a pre-recorded fixture does not exist for a given (language, command) combination,
the E2E test runner will attempt to synthesize audio via Gemini TTS as a fallback.
