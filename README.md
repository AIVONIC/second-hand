# Second Hand

A hands-free coworker that lives in Ray-Ban Meta glasses. It sees what you see, answers in your ear while your
hands are on the job, points out the exact part, stops you before work that needs a licensed electrician, and
writes the job report and invoice draft when you say "done".

Built for the **Meta Global AI Developer Hackathon 2026** (Startup track, theme: Build for Your World).

## Built during the hackathon

Everything in this repository was written during the hackathon's building period, from **2026-10-26**. The
commit history is the record: the first commit is the empty repository on the first day.

## Meta AI used

- **Ray-Ban Meta glasses** through the Wearables Device Access Toolkit (camera, photo capture)
- **Muse Spark** (Meta Model API): reasons over what the glasses see and the job so far
- **SAM 3.1** (Meta Model API): segments the part the agent is talking about

## How it fits together

```
Ray-Ban Meta glasses --(Device Access Toolkit: camera stream + microphone)--> Android app (android/)
Android app --(one WebSocket per job: 16 kHz audio, photos)--> server (server/)
server --> Muse Voice Transcribe (hears) -> Muse Spark (thinks, decides when to stop) -> SAM 3.1 (outlines the part)
server --(the spoken reply, streamed)--> Android app --> the glasses' speakers
```

The key to Meta's models stays on the server. The app proves itself with a device token that is entered on the
phone once; it is never built into the APK, which anyone can download from this repository's Actions.

## Status

Day 2. The voice loop runs end to end on the server, and the Android app is built (not yet tried on the glasses).

## License

MIT
