# Briar fork: news and files without the internet

This is an unofficial fork of [Briar](https://briarproject.org). Briar's own README is below. This video explains the purpose of this fork and shows its extra features:

https://github.com/user-attachments/assets/76a11f7e-bf6d-4805-97bd-31ce1b0da0f2

## Why this fork exists

Briar works without the internet: phones pass messages to each other over Bluetooth, Wi-Fi or Tor. It was built for talking to people you know.

In an internet shutdown, people need two more things:

1. **News.** One source reaching many readers.
2. **Files.** Documents, videos and voice recordings, not just images.

Briar has neither. Everything in it is tied to a person and only travels between contacts, and it can only send images, only in private chats.

## What this fork adds

**Channels.** A channel is a news feed. Its publisher is a key pair, not a person, so nothing in it names who runs it. Only the publisher can post, and every post is signed, so nobody can fake one.

A channel reaches readers in several ways, and you choose which to turn on:

- **As a file.** Send the whole channel through any app, a USB stick or a domestic messenger. Opening the file subscribes you, with all its posts.
- **From a website or .onion address.** Readers download only what's new, and Briar checks every post.
- **Through contacts.** Posts pass from contact to contact, like the rest of Briar. The channel shows only its key, not who passed it on.
- **To phones nearby**, over Bluetooth. ⚠ **Still being tested on real phones.**

**Files everywhere.** Any file up to 29 MiB, in private chats, groups and channels. Files travel in small pieces, so a cut connection resumes where it stopped. Short messages go first, so a text isn't stuck behind a video.

**Announcement groups.** Private groups where only the creator posts.

⚠ **Not yet independently reviewed.** Don't rely on it where your safety depends on it. We're looking for reviewers, for the code, the crypto and the Android app.

---

# Briar

Briar is a messaging app designed for activists, journalists, and anyone else who needs a safe, easy and robust way to communicate.

Unlike traditional messaging apps, Briar doesn't rely on a central server - messages are synchronized directly between the users' devices. 

If the Internet's down, Briar can sync via Bluetooth or Wi-Fi, keeping information flowing in a crisis. If the Internet's up, Briar can sync via the Tor network, protecting users and their relationships from surveillance.

## Download Briar

[<img src="https://briarproject.org//img/fdroid_badge.png"  width="240">](https://briarproject.org/fdroid)
[<img src="https://briarproject.org/img/google_play_badge_web_generic.png"  width="240">](https://play.google.com/store/apps/details?id=org.briarproject.briar.android)

You can also [download the APK file](https://briarproject.org/apk) directly from
our site.

## Useful links
[Project website](https://briarproject.org/)

[Source code](https://code.briarproject.org/briar/briar/tree/master)

[User manual](https://briarproject.org/manual/)

[Wiki](https://code.briarproject.org/briar/briar/-/wikis/home)

[Privacy policy](https://briarproject.org/privacy)

## Reproducible builds

We provide [docker images](https://code.briarproject.org/briar/briar-reproducer#briar-reproducer)
to ease the task of verifying that the published APK binaries
include nothing but our publicly available source code.

You can either use those images or use them as a blueprint to build your own environment
for reproduction.

## Donate

[![Donate using Liberapay](https://briarproject.org/img/liberapay.svg)](https://liberapay.com/Briar/donate)   
Bitcoin and BCH: 1NZCKkUCtJV2U2Y9hDb9uq8S7ksFCFGR6K
