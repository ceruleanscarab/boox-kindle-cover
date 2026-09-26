# Boox Kindle Cover

Shows the cover of whatever you're reading in the Kindle app as the screensaver on a Boox e-reader (built for the Note Air4 C).

## How it works

1. An accessibility service watches the Kindle app and reads the book title off the screen.
2. It looks up the cover on Google Books, falling back to Open Library.
3. It scales the cover to your screen and overwrites the image Boox uses as its screensaver in `Screensaver/cloud`. Boox reads that file fresh each time the device sleeps.

The original image is backed up the first time it's replaced, and the app has a button to restore it.

## Install

Download the latest APK on the Boox itself:

**https://github.com/ceruleanscarab/boox-kindle-cover/releases/latest/download/kindle-cover.apk**

Every push to `main` builds a new release through GitHub Actions. Updates install over the old version.

## Setup on the device

1. Open **Kindle Cover** and tap **Grant all-files access**.
2. Tap **Open accessibility settings** and turn on **Kindle Cover**.
3. Under **Screensaver image to replace**, pick the image Boox is currently using.
4. In Boox settings, keep that image selected as your screensaver.
5. In the Boox **App Management / Freeze** settings, make sure Kindle Cover isn't frozen or restricted in the background, or Boox may shut off the accessibility service.

Then open a book in Kindle and tap the middle of the page once so the title bar shows. The log in the app will show what it detected.

## If the title isn't detected

The Kindle app's view ids aren't documented, so the built-in guess may miss.

1. Turn on **Debug: record Kindle screens**.
2. Open a book in Kindle and tap to show the title bar.
3. Look at `Download/kindlecover-dump.txt` (for example `tail -n 80 /sdcard/Download/kindlecover-dump.txt` in a terminal). Each line shows `[view_id] ViewType text="..."`.
4. Find the line holding the book title and put its view id (or a regex) in **Title view-id regex**.

You can also type a title into **Set a cover manually** at any time.

## Notes

- The signing key in `keystore/` is a throwaway debug key committed on purpose so CI builds can update each other. Don't reuse it for anything else.
- The app only watches `com.amazon.kindle`, and the only data leaving the device is the title and author sent to the cover lookup APIs.
