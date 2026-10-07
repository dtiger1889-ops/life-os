# Capture at the moment of relief (the walk-out pattern)

A design pattern for the phone app, for any domain where a person finishes something and the
system needs a record of it: they just walked out of an appointment, hung up a call, or paid
at a counter. That moment is when the facts are freshest and when the person is least willing
to fill in a form. They are tired, standing, holding the phone in one hand, and often one
event produces two linked records at once (the event itself, and a payment or follow-up that
belongs to it). If capture takes more than one gesture there, it happens later from memory or
a statement, or never.

## The pattern

1. **One screen, one primary action.** The capture sits at the top of the domain's editing
   surface, above every other section. A picker for the thing just left, sorted by most
   recent use, so the usual choice is already first. Everything the system already knows is
   prefilled (the usual cost for that place, today's date). The optional linked record is a
   toggle on the same screen, never a second screen.
2. **Confirm instantly, from the phone's own database.** The confirmation never waits on the
   network. Its copy names the relief instead of the mechanics, shows briefly, and goes away
   on its own: "Filed. You don't have to remember this anymore."
3. **Write both records locally in one transaction**, each with its outbox row, the same way
   every local edit works (`PROTOCOL.md`, client expectations). The linked record's outbox
   row starts **held**, because it needs its parent's server id and that id does not exist
   yet.
4. **Sync in up to two rounds.** Push everything that is not held, pull, find the parent's
   real row, then release the held rows waiting on it: write the real parent id into their
   payload and clear the hold. If anything was released, run push and pull once more in the
   same sync run, so the linked record lands now instead of on the next scheduled sync. Stop
   after the second round.
5. **Put the reference details next to the capture.** What a person needs standing at a desk
   (phone number with tap-to-dial, hours, the usual cost) goes in a read-only list on the same
   surface, so the screen that files the record also answers the question asked at the counter.

## Why the linked record is held

The push acknowledgement says whether each mutation applied; it does not return the id the
server assigned to a created row (`PROTOCOL.md`, push). So the phone learns its new parent's
real id only from the next pull. Sending the linked record with the phone's temporary parent
id would either be rejected or attach it to the wrong row. Holding it costs nothing offline,
and online the second round closes the gap within the same sync.

## Finding the parent after the pull

The phone matches its local parent to the pulled row by a natural key: fields that together
identify one capture, such as the place and the date. Pick a key that cannot repeat between
two captures the person could make before a sync. If it can repeat, add a client-generated
reference column to the parent table, return it in pulls, and match on that instead.

## Offline

Nothing changes for the person. The confirmation still shows at once. Both outbox rows wait;
when the server is next reachable, the parent drains first and the linked record follows it in
the same run.

## Adding it to the app template

The template's outbox has no hold yet. The pieces, all additive:
- a nullable `dependsOnTempId` column on the outbox entity (a Room migration that only adds
  the column);
- the sync worker leaves rows with a non-null `dependsOnTempId` out of every push;
- two outbox queries: find the rows held for a given temporary id, and release one (rewrite
  its payload and clear the hold);
- after each pull, the worker reconciles new parents by natural key, releases their held
  rows, and runs the second round when anything was released;
- a repository function that writes the parent, the linked record and both outbox rows in one
  transaction.

The server needs nothing new: by the time the linked record is pushed, it carries a real
parent id like any other create.

## What testing it showed

- Run against a live server on an emulator: a capture with the linked record produced the
  parent row and then the linked row carrying the parent's real id, inside one sync run.
- With the emulator's network switched off, the capture confirmed immediately with zero rows
  on the server, then drained in order once the network came back.
- Test captures are real rows. Clean them up with tombstone deletes through the normal sync
  path, then reload the dashboard to confirm they are gone.
- One build broke on a comment, not on code: a doc comment that mentioned file name patterns
  like `*Row` after a slash opened a nested block comment, because Kotlin block comments nest.
  Reword the comment rather than chase the compiler error.
