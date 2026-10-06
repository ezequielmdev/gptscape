# GPTScape (RuneLite plugin)

GPTScape is an AI assistant for Old School RuneScape in the RuneLite sidebar, powered by the **official Google Gemini API** (free tier).

## Features

- **Streaming replies**: answers appear word by word, like modern AI chats.
- **Remembers the conversation**: follow-up questions keep their context (user/model history).
- **Replies in your language**: the interface is in English, but GPTScape answers in whatever language you write in and switches when you do. Official OSRS names (Theatre of Blood, Zulrah…) are kept untranslated.
- **Web access (free)**: GPTScape can search the web, read official OSRS news, check the OSRS Wiki and live Grand Exchange prices. The panel shows each lookup ("Searching…", "Checking price…").
- **Markdown**: headings, bold, italic, inline code, lists, tables, links and code blocks with **Copy code**.
- **Message actions**: Copy, Regenerate (last reply) and Try again (failed replies).
- **Stop** a reply at any time; **New chat** starts fresh.
- **Saved chat**: the current chat is kept on your computer between restarts (can be turned off).
- **Share Game Stats** (off by default): sends your combat and skill levels so answers fit your account. Your character name is never sent.

## Setup

1. Get a free API key at https://aistudio.google.com/apikey (no credit card required).
2. Open the GPTScape panel in RuneLite and paste the key, or set **Configuration → GPTScape → Gemini API Key**.

## Settings

| Section | Option | Default |
|---|---|---|
| — | Gemini API Key | — |
| Conversation | Web Access | On |
| Conversation | Save Chat History | On |
| Conversation | Share Game Stats | Off |
| Advanced Settings | Gemini Model | `gemini-3.5-flash-lite` (highest free daily quota); falls back to `gemini-3.5-flash`, `gemini-flash-latest`, `gemini-3.1-flash-lite` |
| Advanced Settings | System Prompt | Optional extra instructions |
| Advanced Settings | Conversation History | 30 messages sent as context |
| Advanced Settings | Website Link | `https://gemini.google.com/` |

### Free tier limits

The plugin uses the free tier by default. Google limits free requests per model per day (for example, `gemini-3.5-flash` allows only 20 per day). When a model runs out, the plugin automatically tries the next one. Answers that use web lookups use more requests. On the free tier, Google may use conversations to improve its products.

When every free model is out of quota, the chat shows when the free messages reset (midnight Pacific time, shown in your local time) and a **Get unlimited messages** link.

### Unlimited messages (optional)

1. Open https://aistudio.google.com/apikey and click **Set up billing** next to your key.
2. Add a payment method and, ideally, a monthly budget alert.

Your key stays the same and the plugin needs no changes. With `gemini-3.5-flash-lite` most messages cost a fraction of a cent, and paid usage is not used to train Google's models.

## How it works

- `GeminiClient` calls `models/{model}:streamGenerateContent?alt=sse` with the key in the `x-goog-api-key` header (never in the URL, prompt or logs) and parses the Server-Sent Events stream.
- Network calls run on a small background executor; Swing updates are batched every 50 ms on the EDT, and only the reply being generated is re-rendered.
- The chat is stored at `.runelite/gemini-chat/conversation.json` (role, text and timestamp only).

## Development

```bash
./gradlew run        # RuneLite with the plugin loaded (Windows: gradlew.bat run)
./gradlew build      # compile + unit tests
GEMINI_API_KEY=your_key ./gradlew test --tests com.gptscape.GeminiClientTest   # live API tests
WEB_TOOLS_TEST=1 ./gradlew test --tests com.gptscape.WebToolsTest            # live web tools tests
GEMINI_API_KEY=your_key ./gradlew preview   # renders panel screenshots to build/preview
```
