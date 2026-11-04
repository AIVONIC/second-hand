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
- **Muse Voice Transcribe** (Meta Model API): hears the technician in realtime and finds where each question ends
- **Muse Spark Open** (Meta's open-weight model, served on Baseten): writes the job report and invoice draft

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

Working end to end on Ray-Ban Meta glasses: it hears the question, answers in the glasses, outlines the part on the
phone, says stop before work that needs a licensed electrician, films the wearer's view, and writes the job report
and invoice draft as a PDF when the job is done.

Demo video (a real job, in real time, no cuts): https://youtu.be/WYckjCH-F9U

## License

MIT
