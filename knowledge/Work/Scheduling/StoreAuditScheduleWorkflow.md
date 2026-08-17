# STORE AUDIT SCHEDULE WORKFLOW

## 0. STATUS OF THIS DOCUMENT

This document is an INSTRUCTION SOURCE. It defines the PROCEDURE for
building a store-audit visit schedule.

It is NEVER a DATA SOURCE. Any address, store name or record shown in this
document as an example is illustrative only, clearly marked as an example,
and MUST NOT be treated as a store the user actually wants visited.

The user's actual data always comes from the current message's attachments
(screenshots, photos, tables) or an explicit typed list - never from this
file.

---

## 1. WORKFLOW STAGES

Follow these stages in order. Do not skip a stage. Do not guess which
stage you are in - the `storeDataset` tool's responses always tell you the
dataset's current stage (`EXTRACTED`, `LOCKED`, `GEOLOCATED`), so check
`storeDataset.GET_DATASET` if you are unsure instead of assuming.

```
READ_INPUT        - read every relevant attachment in full
EXTRACT           - build the record list, submit via CREATE_DATASET
VERIFY            - second pass: verify the locked list, submit via VERIFY_DATASET
GEOLOCATE         - resolve coordinates via GEOCODE_DATASET, by record id
RECHECK_FAILED    - retry only the records that failed geolocation
OPTIMIZE          - group/order stores using the geolocated coordinates
PRELIMINARY       - present the preliminary schedule table to the user
WAIT_USER_APPROVAL
```

---

## 2. READ_INPUT

Read the ENTIRE content of every attachment relevant to this task before
extracting anything. Do not start extraction after reading only part of a
table or only one of several images.

If several screenshots/images are supplied, treat them together as parts
of ONE dataset unless the user explicitly says otherwise.

---

## 3. EXTRACT

For every visible store row, record:

* network (e.g. Biedronka, Stokrotka, Żabka),
* city,
* street,
* building number,
* postal code,
* full address (as one string),
* the id of the attachment it came from,
* its row/position within that attachment.

ONE VISIBLE STORE ROW = ONE RECORD. Never merge two rows into one record
and never split one row into two records.

Do not omit a row because it looks difficult or uncertain - extract it and
let verification/geolocation flag it if it truly cannot be resolved.

Do not invent a row that is not visible.

Once extraction is complete, submit the full list in a single
`storeDataset.CREATE_DATASET` call, with `sourceAttachmentIds` listing every
attachment id you read from and `sourceImageCount` set to how many image
attachments you read. Every record's `sourceAttachmentId` must be one of
those declared ids - a record without a valid one is rejected by Core.

This call locks the canonical record count. From this point on, the
dataset's size does not change on its own.

**Example of the expected shape (illustrative only - not real data):**

```
network: "Biedronka"
city: "Miasto Testowe"
street: "Ulica Testowa"
buildingNumber: "1"
postalCode: "00-001"
fullAddress: "Ulica Testowa 1, 00-001 Miasto Testowe"
sourceAttachmentId: "<the real attachment id you read this from>"
sourceRow: 1
```

---

## 4. VERIFY

Perform a second visual pass over the same attachments, but this pass
VERIFIES the already-locked dataset - it does not produce a new
independent list.

For each record already in the dataset, confirm it matches the image, or
report a correction (e.g. a misread postal code) tied to that exact
record id via `storeDataset.VERIFY_DATASET`.

Do not invent new records during verification. Do not omit existing
records from your verification pass merely because you are re-checking a
subset - Core keeps every record that is not explicitly reported as
incorrect.

If your second pass genuinely disagrees with the locked count (e.g. you
now count a very different number of rows than what was locked), do not
silently submit a mismatched list. Call `storeDataset.GET_DATASET` first,
re-read the attachments against that exact list, and report specific
corrections instead of a new count.

---

## 5. GEOLOCATE

Once the dataset is locked, call `location.GEOCODE_DATASET` with the
dataset id and the list of `{recordId, fullAddress}` pairs - in as few
batch calls as practical, not one call per store.

Core updates each record's coordinates and geolocation status in place.
This can never add or remove a record from the dataset, regardless of how
many results come back.

---

## 6. RECHECK_FAILED

If some records come back unresolved/ambiguous, retry only those specific
record ids (e.g. with a fuller address, or after asking the user for the
missing detail). Do not resubmit the whole dataset and do not create a new
record for a retried address - the same record id gets updated again.

---

## 7. OPTIMIZE

Default starting point for every route, unless the user specifies another
one: **Nowa Wola, 05-500, Polska**.

Approximate visit durations:

* Biedronka: 90-120 minutes per location,
* Stokrotka / Żabka / similar short audits: 5-10 minutes per location.

Standard daily guideline (not a hard limit):

* up to 4 Biedronka locations per day,
* up to 7 short-audit locations per day.

Minimize unnecessary return trips to the same distant region across
multiple days - a small, deliberate excess over the daily guideline that
avoids a second long trip is usually better than a strictly limit-abiding
plan that requires driving back to the same area again. When the trade-off
is genuinely borderline, generate the preliminary schedule anyway, mark
the borderline day, explain the trade-off, and let the user decide - do
not block the whole schedule on this decision.

Use `location.OPTIMIZE_ROUTE` (or `location.ROUTE_MATRIX` plus your own
grouping) on the already-geolocated coordinates. This step only orders or
groups the existing records - it can never create a new store.

---

## 8. PRELIMINARY

Present the result as a table Damian can review immediately:

| Dzień | Kolejność wizyt | Biedronka | Inne | Audyty | Trasa / dystans | Uwagi |
|------|------------------|-----------|------|--------|-----------------|-------|
| 1 | ... | 4 | 0 | ... | ... | ... |
| 2 | ... | 3 | 2 | ... | ... | ... |

After the table, mention only what materially helps evaluate the plan
(borderline days, unresolved locations). Do not bury the table under a long
description of how it was produced.

---

## 9. WAIT_USER_APPROVAL

Only after the preliminary table is presented should you continue to a
final/confirmed schedule or any external calendar action. Do not ask for
confirmation before every intermediate stage above - reaching the
preliminary table is the natural checkpoint.

---

## 10. WHAT NEVER HAPPENS IN THIS WORKFLOW

* GeoLocation never creates a new store record - it only updates existing
  ones by id.
* Route optimization never creates a new store record - it only orders the
  records it is given.
* A record without a valid current-message attachment id as its source is
  never added to the dataset.
* Examples shown in this document are never added to the dataset.
* The dataset's record count never changes silently between stages.
