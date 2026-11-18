# Privacy

Songbook collects nothing about you and sends nothing anywhere of its own accord.

It goes online for two things. One is to fetch a song's page whose address you shared with it or typed
into it; an address or a file shared from another app is acted on only after you say yes to it. That one page is fetched, saying plainly that the request is from the app Songbook; no
pictures or scripts from it are fetched, and nothing about you or your songs is sent with the
request. The site sees a request for its page from an app, and may refuse it; the app then says
so, and the sheet can be pasted in instead. It does not try to get round the refusal. Everything
is asked over https only: an address that starts with http is tried as https, and if the site
does not answer that way the app says so and nothing is sent in plain text. Android is told the
same, so the app is not able to send plain http at all.

The other is Nextcloud, and only once you have signed in to one in Settings: then your songs are
synced with your own Nextcloud, one ChordPro file each in the folder you choose, and go nowhere
else. Sign-in is typed: the server's address, your login name and an app password made in
Nextcloud for this phone. Or, instead, on the server's own page in your browser, which gives this
phone an app password of its own. Either way you can revoke it from the server's security
settings. Before you sign in, and after
you sign out, nothing goes there.

Until you sign in to a Nextcloud, a sheet you type, paste, or share to Songbook from another app
stays on the phone and is never sent anywhere.

It keeps, in its own private storage on the phone: your songs, one small file each (with how far
you have moved each one's chords and its scroll speed) and your setlists, and its settings: which instrument the
chord boxes are drawn for, the size of the sheet, whether the screen stays on, whether an open
song turns with the phone, whether dark mode is on, and which tab was open last; and, while you are signed in, the Nextcloud address, your login
name, the folder your songs go in, the app password (sealed with a key held in the phone's keystore, not kept as plain text)
and a note of what was last synced; and, while a sign-in through the browser is waited on, the
addresses and token the server gave for it and the address you typed (sealed the same way), for at
most twenty minutes. None of it is
backed up by the app, and uninstalling it removes all of it. Settings can save every song to a
file you choose, and bring songs in from one.

When Home is installed, the app reads Home's dark mode switch, a yes or no, so it is light or
dark with the rest of the phone, and sends Home nothing.

It hands something to another app only when you press for it: a song as text or a file to where
you send it, the page a song came from to your browser. The About dialog's llama opens a web
address in your browser, and only when you press it.

## Permissions

- **Internet** — to fetch the song page you send it, and to sync with your Nextcloud once you
  have signed in to one; for nothing else.
- One more is not one you are asked for: Android's support library adds it to every app built
  with it (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), so that the app's own internal messages
  can reach only the app itself.

<!-- shared-about privacy section -->
## Who we are

Songbook is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. What you put in Songbook stays on your phone. The only things that go out are the requests described above: a request for the one song page whose address you give it, to that site, carrying nothing about you or your songs; and, once you sign in to a Nextcloud of your own, your songs and their sync requests, to that server alone. The developer receives nothing and holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Songbook is sold or shared.

Since Songbook keeps what it stores only on your phone, you can exercise most of these rights yourself: delete a song in the app, save every song to a file in Settings, sign out of Nextcloud, clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Songbook connects to the sites you ask it to read. They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.

If you point Songbook at a server of your own, what that server keeps is yours to manage.
<!-- /shared-about privacy section -->
