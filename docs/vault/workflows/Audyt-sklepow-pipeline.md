---
id: jarvis-store-audit-pipeline-v1
type: workflow
title: Audyt sklepów — pipeline danych
status: active
version: 2
tags: [audyt, grafik, sklepy, Biedronka, planowanie]
---

# Audyt sklepów — pipeline danych

Przed wykonaniem zadania odczytaj ten dokument w całości, wszystkie części. Zawartość sekcji 15–28 poniżej została przeniesiona dosłownie z dostarczonego promptu. Nazwy operacji w procedurze są logiczne; używaj rzeczywistych schematów narzędzi Core.

Dokument zależny: Work/Scheduling/StoreAuditScheduleWorkflow.md. Odczytaj go w całości na etapie LOAD PLANNING KNOWLEDGE, przed geokodowaniem i planowaniem. Nie używaj przykładów z procedur jako danych użytkownika. W razie konfliktu reguł zatrzymaj zależny etap i wyjaśnij konkretną sprzeczność.

# ============================================================

# 15. STORE AUDIT — REQUIRED PIPELINE

# ============================================================

For a new Store Audit scheduling request use this order:

STORE AUDIT REQUEST
-> INSPECT ALL CURRENT INPUT
-> EXTRACT ALL STORE RECORDS
-> NORMALIZE RECORDS
-> CREATE CANONICAL storeDataset
-> CONFIRM DATASET WAS ACCEPTED
-> VERIFY DATASET AGAINST SOURCE
-> VERIFY_DATASET
-> ASK ABOUT SCHEDULING PREFERENCES (days, distribution) UNLESS ALREADY STATED
-> SET_PREFERENCES
-> LOAD Work/Scheduling/StoreAuditScheduleWorkflow.md
-> GEOCODE_DATASET
-> storeDataset.PLAN_SCHEDULE (Core groups the stores into days by the workflow rules)
-> PRESENT THE PRELIMINARY PLAN AND ITS DECISIONS TO THE USER
-> storeDataset.ADJUST_PLAN for every change the user asks for (repeat as needed)
-> storeDataset.ACCEPT_PLAN once the user accepts
-> PRESENT FINAL TABLE TO USER
-> (only if the user asks) calendar.EXPORT_SCHEDULE - Google Tasks, one approval window

Every arrow represents a required workflow stage.

Do not proceed to geographic planning before the canonical dataset exists.

Do not present a finished schedule before `storeDataset.ACCEPT_PLAN`
has been successfully accepted by Core.

The "ASK ABOUT SCHEDULING PREFERENCES" step and the "PRESENT THE PRELIMINARY
PLAN" step are the legitimate points in this pipeline where you stop and wait
for the user's answer instead of calling another tool - unlike every other
arrow above, they are not technical steps you push through silently. Skip the
preferences question only when the user's own message already stated
unambiguous preferences. See the canonical document
(`Work/Scheduling/StoreAuditScheduleWorkflow.md`) for the exact defaults and
the three distribution strategies.

# ============================================================

# 16. STORE AUDIT — INPUT MATERIAL

# ============================================================

Store Audit input may be supplied as:

* screenshots,
* photographs,
* tables,
* text,
* lists,
* structured files,
* or several attachments forming one dataset.

When store locations are supplied together with a scheduling request,
the supplied material IS the dataset for this task.

Inspect ALL relevant current-message attachments.

If several screenshots show different parts of one table, treat them
as one combined source dataset.

Assume the supplied dataset is complete unless Damian explicitly says
otherwise.

Do not ask permission to:

* read the attachments,
* extract the addresses,
* process all supplied rows,
* create the dataset,
* verify the dataset,
* use geolocation,
* optimize the schedule,
* or continue to another normal workflow stage.

# ============================================================

# 17. STORE AUDIT — RECORD EXTRACTION

# ============================================================

Before using any geographic capability, extract every visible store row.

Exactly one source row representing one store must become exactly one
candidate store record.

Do not merge separate stores merely because:

* they use the same network,
* they are in the same city,
* they have similar addresses,
* or they are geographically close.

Do not silently omit records.

Each candidate record submitted to `storeDataset` must use this structure:

{
"network": "<store network>",
"city": "<city or town>",
"street": "<street name>",
"buildingNumber": "<building number>",
"postalCode": "<postal code>",
"fullAddress": "<street + building number + postal code + city>",
"sourceAttachmentIndex": <1-based position in the CURRENT MESSAGE ATTACHMENTS list>,
"sourceRow": <source row number or stable source position>
}

Field names are exact.

Do not rename them.

Do not use translated field names.

Do not invent additional fields when submitting candidate records.

# ============================================================

# 18. STORE AUDIT — FIELD RULES

# ============================================================

`fullAddress` is required for every submitted store record.

Whenever street, building number, postal code and city are available,
construct `fullAddress` deterministically as:

street + buildingNumber + ", " + postalCode + " " + city

Example:

Source data:

Network: Biedronka
City: Garwolin
Street: Korczaka
Building number: 7
Postal code: 08-400

Correct candidate:

{
"network": "Biedronka",
"city": "Garwolin",
"street": "Korczaka",
"buildingNumber": "7",
"postalCode": "08-400",
"fullAddress": "Korczaka 7, 08-400 Garwolin",
"sourceAttachmentIndex": 1,
"sourceRow": 1
}

Do not leave `fullAddress` blank when its components are available.

For current-message attachments:

`sourceAttachmentIndex`
identifies WHICH numbered current-message attachment the record came
from - the same numbering shown in the CURRENT MESSAGE ATTACHMENTS list
(Image 1 = 1, Image 2 = 2, ...).

Never guess this number.

Never reference an attachment from a previous message or an earlier
turn - only this message's own attachments are valid.

Core resolves this index to the real internal attachment id itself -
you never need to know, copy, or construct that id.

`sourceAttachmentId` (the old per-record field) still exists only as a
fallback for the rare case of an explicit user-typed list with no
attachments at all - when this message has image attachments, always
use `sourceAttachmentIndex` instead; never invent a value for either
field.

`sourceRow` identifies the row or stable position of that store in the
source attachment.

Different stores from the same attachment must use different sourceRow
values.

If the user supplied an explicit typed list instead of attachments,
follow the tool's text-input provenance rules.

# ============================================================

# 19. STORE AUDIT — DATASET CREATION

# ============================================================

After extraction and normalization, save the records into the canonical
`storeDataset`.

Do not proceed directly from extracted addresses to GeoLocation.

For approximately 10 or fewer records, prefer:

storeDataset.CREATE_DATASET

Provide:

* sourceImageCount,
* sourceAttachmentIds,
* complete `records` array.

For a larger dataset, prefer incremental creation:

1. storeDataset.START_DATASET
2. storeDataset.APPEND_RECORDS
3. repeat APPEND_RECORDS until every extracted record has been submitted
4. storeDataset.FINALIZE_DATASET

Use small reliable batches for large datasets.

A typical batch may contain approximately 5-8 records.

Do not call START_DATASET more than once for the same dataset.

Do not finalize until every source record has been submitted.

# ============================================================

# 20. STORE AUDIT — DATASET CREATION EXAMPLE

# ============================================================

Example input containing three stores:

Biedronka | Garwolin | Korczaka 7 | 08-400
Biedronka | Garwolin | Targowa 1 | 08-400
Żabka | Garwolin | Kościuszki 14 | 08-400

A correct CREATE_DATASET request conceptually contains:

sourceImageCount: 1

expectedRecordCount: 3

records:
[
{
"network": "Biedronka",
"city": "Garwolin",
"street": "Korczaka",
"buildingNumber": "7",
"postalCode": "08-400",
"fullAddress": "Korczaka 7, 08-400 Garwolin",
"sourceAttachmentIndex": 1,
"sourceRow": 1
},
{
"network": "Biedronka",
"city": "Garwolin",
"street": "Targowa",
"buildingNumber": "1",
"postalCode": "08-400",
"fullAddress": "Targowa 1, 08-400 Garwolin",
"sourceAttachmentIndex": 1,
"sourceRow": 2
},
{
"network": "Żabka",
"city": "Garwolin",
"street": "Kościuszki",
"buildingNumber": "14",
"postalCode": "08-400",
"fullAddress": "Kościuszki 14, 08-400 Garwolin",
"sourceAttachmentIndex": 1,
"sourceRow": 3
}
]

This example describes the required data shape.

`sourceAttachmentIndex: 1` means every record came from current-message
image 1 - use the real position of whichever image each record actually
came from.

# ============================================================

# 21. STORE AUDIT — DATASET ACCEPTANCE CHECK

# ============================================================

Creating a dataset is not complete merely because a tool call was attempted.

Inspect the ToolResult returned by Core.

Continue only when Core confirms that the dataset was accepted.

Check especially:

* datasetId exists,
* accepted record count is plausible,
* rejected records are understood,
* duplicate count is understood,
* dataset contains the expected source records.

The expected count comes from the actual source material.

If the source contains N visible stores, the canonical dataset should
normally contain N stores.

Do not silently continue when stores were rejected.

If a record was rejected because of a correctable formatting or
transcription problem:

1. return to the source,
2. correct the candidate,
3. perform the appropriate dataset operation,
4. verify the result.

Never fabricate missing stores merely to make the counts match.

# ============================================================

# 22. STORE AUDIT — SECOND PASS AND VERIFY_DATASET

# ============================================================

After the canonical dataset has been successfully created, perform the
mandatory second source verification.

Use the canonical records returned by Core.

Reinspect all relevant source material from the beginning.

For every canonical record verify:

* network,
* city,
* street,
* building number,
* postal code,
* full address,
* correspondence with the correct source row.

Check globally that:

* no source store is missing,
* no source store was accidentally duplicated,
* adjacent rows were not mixed,
* city names belong to the correct street,
* postal codes belong to the correct store.

After this visual/source verification, call:

storeDataset.VERIFY_DATASET

Reference each record by `recordIndex` - its 1-based position in the
canonical dataset, exactly as numbered in the records Core showed you
(record #1 = 1, record #2 = 2, ...). Core resolves this to the real
internal record id itself - you never need to know or copy that id's
format. An out-of-range `recordIndex` rejects the whole call outright
with the valid range - never guess or clamp one.

For a correct record submit status:

VERIFIED

For a reliably corrected record submit:

CORRECTED

and provide supported corrected fields.

Do not consider PASS 2 complete merely because the model internally
re-read the image.

PASS 2 is complete only when the corresponding VERIFY_DATASET operation
has been accepted by Core.

# ============================================================

# 23. STORE AUDIT — LOAD PLANNING KNOWLEDGE

# ============================================================

Only after the canonical dataset has been created and verified, load:

Work/Scheduling/StoreAuditScheduleWorkflow.md

This document is the authoritative source for the BUSINESS RULES used
to construct Damian's audit schedule. Core applies them itself in
storeDataset.PLAN_SCHEDULE; read the document so you can explain the plan
and handle the user's questions about it.

It defines rules such as:

* route starting point,
* audit duration,
* daily workload guidance,
* geographic grouping,
* minimizing repeat journeys,
* handling distant regions,
* borderline workload decisions,
* optimization priorities,
* and preliminary schedule presentation.

Do not use the workflow document as the source of store records.

Do not create stores from examples contained in the workflow.

The current request's canonical `storeDataset` is the only authoritative
store list for the current schedule.

# ============================================================

# 24. STORE AUDIT — GEOLOCATION

# ============================================================

After:

1. dataset creation,
2. dataset acceptance check,
3. source verification,
4. VERIFY_DATASET,
5. loading StoreAuditScheduleWorkflow.md,

perform geographic resolution.

Use the canonical dataset operation:

location.GEOCODE_DATASET

Do not re-create the store list manually for geocoding.

Do not batch-geocode a separate model-generated address list when a
canonical storeDataset exists.

Geolocation results must remain associated with canonical record ids.

If a location cannot be resolved reliably:

1. identify the affected recordId,
2. return to its source row,
3. verify the original transcription,
4. apply a supported correction through the dataset workflow if needed,
5. retry geolocation.

Do not invent coordinates.

# ============================================================

# 25. STORE AUDIT — SCHEDULE PLANNING

# ============================================================

Before geolocation/planning, resolve scheduling preferences (which days,
how audits are spread across the month) with the user - see section 15's
pipeline and the canonical document for the exact defaults and the three
distribution strategies (beginning of month / end of month / even). Record
the resolved preferences through:

storeDataset.SET_PREFERENCES

`year` defaults to the current system year when omitted - never hardcode
or guess a year. Rejected if the resulting month has already passed.

Once the dataset is geolocated, call:

storeDataset.PLAN_SCHEDULE

Core builds the whole preliminary plan itself, following
Work/Scheduling/StoreAuditScheduleWorkflow.md: closed trips from the fixed
start point, the farthest region first, daily limits, every store exactly
once, real dates from the agreed preferences, optimized visit order, the
full closed-route distance and time of each day and a Google Maps route
link per day. Do not group stores, compute dates or call
location.OPTIMIZE_ROUTE yourself.

The result is a PRELIMINARY plan - nothing is saved yet. Present it to the
user day by day (section 13 of the canonical document): date, region, the
stores in visit order with network and address, audit time, travel time,
total work time, kilometers and the route link. Ask every entry of
`decisions` exactly as given (A merge / B keep separate) and say every
`warnings` entry (e.g. not enough preferred days). Then wait for the user.

Every change the user asks for goes through:

storeDataset.ADJUST_PLAN

one change per call: `moveStoreIndex` + `toDay`, `mergeDays`, `splitDay`,
`day` + `date`, `acceptDecision` (option A) / `dismissDecision` (option B),
or `biedronkaPerDay` / `shortPerDay` for more or less work per day (this
re-plans everything). Show the updated plan after each change.

When the user accepts the plan, call:

storeDataset.ACCEPT_PLAN

Core submits exactly the plan the user saw as the schedule (the same
validation as SUBMIT_SCHEDULE: every record exactly once, real dates in the
window, no past dates). Open decisions count as option B.

`storeDataset.SUBMIT_SCHEDULE` still exists for a schedule the user typed
themselves; do not use it to build plans.

# ============================================================

# 26. STORE AUDIT — FINAL USER OUTPUT

# ============================================================

Only after `storeDataset.ACCEPT_PLAN` has been successfully accepted
may the schedule be presented as a complete, confirmed Store Audit plan -
never before, and never if geocoding or routing failed for any store.

Present the schedule as a simple table, one row per store:

| Data | Dzień tygodnia | Sieć | Adres |
| ---- | -------------- | ---- | ----- |

Do not add a visit-order/sequence column. Pull dates, chains, and
addresses only from the accepted plan - never reconstruct or guess them.

Below the table, include a summary: number of working days, total number
of audits, total kilometers, and estimated total work time (travel + time
between stops + audit time + return trip, for every day combined). A
per-day summary (audits, full route distance, travel time, audit time,
total time, route link) is optional but welcome.

Show the actual schedule before lengthy explanation.

The normal stopping point is the completed, accepted schedule for
Damian's review.

Offer to put the plan into Google Tasks. Only when the user wants it, call
calendar.EXPORT_SCHEDULE: one task per store on its day, in route order,
with the Google Maps route link in the first task of each day. The user
approves the whole plan once in a window on the PC - never call it without
the user asking, and never add the tasks one by one.

# ============================================================

# 27. STORE AUDIT — FAILURE AND RECOVERY

# ============================================================

Do not abandon the entire workflow because one intermediate operation
failed.

Dataset creation failure:
-> inspect rejected records and provenance
-> correct reliable errors
-> retry appropriately

Dataset verification failure:
-> GET_DATASET if necessary (only when the dataset genuinely changed
   since your last GET_DATASET call - Core blocks a repeated call
   against an unchanged dataset)
-> use the canonical recordIndex values shown in the current records
-> recheck source
-> retry VERIFY_DATASET

Geolocation failure:
-> recheck the affected original source row
-> correct reliable transcription errors
-> retry geolocation

Planning failure (PLAN_SCHEDULE / ADJUST_PLAN):
-> read the exact message (store without location, date not allowed,
   start point not found, routing unavailable)
-> fix what it names (geocode, choose an agreed day, ask the user)
-> retry the same planning operation

Acceptance failure (ACCEPT_PLAN):
-> read the validation message (missing/duplicate store, date outside the
   window or in the past)
-> correct it with ADJUST_PLAN
-> retry ACCEPT_PLAN

Ask Damian only when essential information genuinely cannot be recovered
from the supplied source or available capabilities.

# ============================================================

# 28. STORE AUDIT — NON-NEGOTIABLE INVARIANTS

# ============================================================

For every Store Audit scheduling task:

RAW USER DATA
is not
CANONICAL DATASET.

Extracted model reasoning
is not
CANONICAL DATASET.

A successful storeDataset operation creates the canonical dataset.

The canonical storeDataset is the single source of truth after creation.

Never geocode a large alternative store list outside that dataset.

Never reconstruct the dataset from memory after it exists.

Never use examples from Knowledge as real stores.

Never claim dataset creation succeeded without a successful ToolResult.

Never claim source verification succeeded without VERIFY_DATASET.

Never claim the schedule is complete without successful ACCEPT_PLAN.

The required lifecycle is:

SOURCE
-> DATASET
-> VERIFY
-> PREFERENCES
-> WORKFLOW
-> GEOLOCATION
-> PLAN_SCHEDULE
-> (ADJUST_PLAN)
-> ACCEPT_PLAN
-> TABLE
-> (EXPORT_SCHEDULE on request)

Core enforces this order itself, deterministically - it is not only a
prose instruction to remember. An operation attempted at the wrong stage
(e.g. GEOCODE_DATASET before VERIFY_DATASET, or PLAN_SCHEDULE before
geolocation) is rejected immediately with the current stage and the
exact next required action - fix the call based on that, do not retry
the same wrong operation.

GET_DATASET returns the exact same content when called again against a
dataset that has not changed since your last GET_DATASET call - Core
recognizes this and returns a short reminder instead of repeating the
full dataset. Only call GET_DATASET again after a real mutation
(APPEND_RECORDS, VERIFY_DATASET, geolocation, ACCEPT_PLAN) or when
you have never fetched this dataset's current state yet.
