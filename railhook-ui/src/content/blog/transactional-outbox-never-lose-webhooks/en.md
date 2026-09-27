---
title: The transactional outbox, or how Railhook never loses an event it has accepted
lead: Your service gets a 201, and the pod that sent it is OOM-killed four milliseconds later. This is how Railhook still delivers that event, how close it gets to exactly-once, and the one step no sender can take on its own.
description: "The transactional outbox pattern with Postgres and Spring Boot: why dual writes lose events, and how Railhook never loses an accepted webhook."
date: 2026-09-20
author: Vadym Kykalo
tags: [outbox, postgres, reliability, spring-boot]
sourcesCheckedOn: 2026-09-19
---

Your checkout service sends `order.paid` to Railhook and gets `201 Created` back. Four milliseconds
later the API pod that answered is OOM-killed. The event must still reach all five endpoints
subscribed to it, possibly over the next 31 hours, and your service has already thrown its copy away.

This post is about how close Railhook gets to delivering that event exactly once, which is almost
all the way, and about the last step, which no sender can take alone. The position it defends:
**a delivery guarantee can only start inside a database transaction, and it can only end inside
the receiver's.** Everything in between is built to assume a message may arrive twice, and then
to make sure that almost none do.

The vocabulary is the codebase's own. An **Event** is what your system announced. A **Delivery** is
the obligation to get one Event to one endpoint. An **Attempt** is one HTTP request towards that.

## Why can't anyone deliver exactly once to your endpoint?

Exactly-once delivery would mean your endpoint acts on every event once and only once. No sender
can promise that over HTTP, Railhook included, and the reason is not effort. It takes four steps.

**The side effect is yours.** When your endpoint marks an order paid, it writes to your database,
in your transaction. Everything Railhook knows about a Delivery lives in Railhook's Postgres. The
one fact that decides whether a resend is a duplicate, *did the receiver act on it?*, is committed
somewhere Railhook cannot read.

**There is no transaction that spans both.** Inside one system, the answer to "two writes must
agree" is a transaction. Across systems it is a two-phase commit: a coordinator both sides trust,
and a prepare step both sides implement. A webhook is one POST to a stranger's server, with no
prepare, no coordinator and nothing to roll back.

**The last message can always be lost.** Your endpoint commits, returns `200`, and the connection
drops before the response reaches the worker. This is the Two Generals problem, described by
[Akkoyunlu, Ekanadham and Huber in 1975](https://doi.org/10.1145/800213.806523) and given its name by
[Jim Gray in 1978](https://doi.org/10.1007/3-540-08755-9_9): two parties talking over a channel that
can lose messages can never both be sure they agree, because whatever message settles it can
itself be lost. Acknowledging the acknowledgement only moves the doubt one message later.

**A timeout says nothing.** After the Delivery's timeout (30 seconds by default) the worker has
no answer. The request may never have arrived. It may have arrived and still be running. It may
have committed and its response died on the way back. To the sender, all three are the same
event: silence.

:::figure lost-ack

So every sender chooses one of two failures. Never resend, and some events are never processed:
that is **at-most-once**. Resend until acknowledged, and some events are processed twice: that
is **at-least-once**. There is no third button.

:::figure delivery-semantics

Railhook resends, because an event you never got is worse than one you got twice and can detect.
Even the silence is narrowed: once the status line of a `2xx` has arrived, the Attempt counts as
delivered whether or not the body follows. That is invariant 6 in `AttemptRunner`, "Failing to read
a response is never failing to deliver", and the comment beside it records why: a receiver that
answered `2xx` and then dawdled over the body "used to collect the whole ladder — one delivery,
seven arrivals".

**Takeaway:** exactly-once delivery to a server you do not control is not a missing feature, it is
an impossibility result. What can be built is an exactly-once *effect*, and the rest of this post
is how Railhook gets as close to it as a sender can.

## Why can't the API just write to Postgres and publish to a broker?

Because those are two systems with no transaction spanning them, and every ordering of the two
writes has a hole.

**Commit, then publish.** Insert the Event, commit, answer `201`, publish. A crash between the commit
and the publish (a deploy, an OOM kill, a node going away) leaves the Event in the database, the
client told it was accepted, and no worker that will ever hear of it. Nothing errors. The first
symptom is a customer asking where it went.

**Publish, then commit.** Now the failure runs the other way: the publish succeeds, the commit fails,
and a worker receives a message about a row that does not exist. The client got a `500` and retries,
and you have a second message for a second row. Publishing *inside* the transaction, before the
commit, is the same ordering with better manners. The broker does not roll back when Postgres does.

:::figure dual-write

**Takeaway:** at enough volume a crash lands between any two calls you have. Design as if it
already did.

## What does the outbox change?

It turns the announcement from a network call into a row, and a row can share a transaction with
the Event. [microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)
puts it as storing the message "in the database as part of the transaction that updates the
business entities", with a separate process sending it on to the broker. Railhook has no broker,
so the Delivery row itself is the message: there is no outbox table beside it and nothing to relay.

In Railhook, the whole ingest runs in one `TransactionTemplate`:

```java
response = transactionTemplate.execute(status ->
        doIngestEvent(projectId, request, idempotencyKey, pendingSequenceAssignment,
                organizationToCharge));
```

and inside it, after the Event is saved, one Delivery per matching Subscription is written,
`PENDING`, with a `next_retry_at` that says when it is due:

```java
List<Delivery> savedDeliveries = deliveryRepository.saveAll(decision.deliveries());
```

(`railhook-api/.../service/EventIngestService.java`)

Either all of it commits and the client gets `201`, or none of it exists. There is no state in which
the Event is stored and its Deliveries are not.

The client's own POST is a dual write too, seen from its side: after a timeout it cannot know
whether the Event was stored. That is what `Idempotency-Key` is for. `events` has a unique index on
`(project_id, idempotency_key)`, so a retried POST gets back the Event it already created. Two
concurrent retries that both miss the lookup collide on the index, and the loser returns the
winner's Event. Incoming webhooks get the same treatment keyed on the provider's own id
(`X-GitHub-Delivery`, `X-Shopify-Webhook-Id`, Stripe's `evt_…`), with a unique index on
`(incoming_source_id, provider_event_id)`.

Not every second write earns an outbox. The monthly quota counter lives in Redis and is charged
*after* the commit, best-effort on purpose: the code's own comment says failing to charge is better
than failing an ingest that has already been accepted.

**Takeaway:** an outbox is for writes that must never disagree. Be explicit about which ones are
allowed to.

## How does a worker find the row?

Each worker polls Postgres for due rows. One statement takes at most five rows per endpoint,
walking endpoints in id order from where the last poll stopped, so one endpoint's backlog cannot
fill the batch, and claims them in the same statement:

```sql
UPDATE deliveries d SET status = 'PROCESSING', claim_token = gen_random_uuid(),
    next_retry_at = :claimExpiresAt, last_attempt_at = :now, updated_at = :now,
    version = d.version + 1
FROM picked WHERE d.id = picked.id
RETURNING d.*
```

(`railhook-worker/.../domain/repository/DeliveryRepository.java`)

`picked` selects its rows `FOR UPDATE SKIP LOCKED`, which Postgres documents as a way "to avoid lock
contention with multiple consumers accessing a queue-like table"
([PostgreSQL, *SELECT*](https://www.postgresql.org/docs/current/sql-select.html)). Two workers polling
at the same moment never get the same row: the second skips what the first has locked. A worker
claims only as many rows as it has free threads, so a claimed row does not sit waiting in memory.

:::figure outbox-pipeline

What happens when things break:

- **A worker dies holding rows.** They stay `PROCESSING`, due again 300 seconds after the claim.
  Once that passes, the next poll claims them under a new token. The Event is late, not lost.
- **Every worker is down.** The rows wait in Postgres, and the first worker to start claims them.

**Takeaway:** Postgres keeps the obligation, and the obligation is the queue. Nothing has to be
announced, so nothing can be lost on the way.

## Claims and fences: why don't two workers send the same Attempt?

That poll is the **Claim**. A row comes back to one worker only: the lock decides who gets it, and
the new `claim_token` records it. Every write the Attempt makes afterwards is conditional on that
token still being in the row.

Retries go through the same gate. A failed Attempt puts the row back to `PENDING` with a new
`next_retry_at`, and the same poll claims it when it is due. There is no second path to a Delivery.

The `claim_token` is also a fence. A `PROCESSING` row whose claim has timed out can be claimed again:
in the query's own words, "its holder is presumed lost, and the new token fences it". A worker that
stalled and wakes up writes against a token the row no longer carries, and its write lands on
nothing. The fence cannot recall a request a stalled worker already put on the wire; it stops that
request's outcome from counting.

Everything after the Claim is the **Attempt Runner**, one class for both directions. Its javadoc
lists six invariants, and each "was once broken in one direction".
Two of them are duplicate-delivery rules:

> No DB or Redis work inside the reactive chain. A write there can trip the HTTP timeout and run the
> failure path over a SUCCESS already written.

> No successor Attempt unless `AttemptStore#finalise` reports it wrote.

The first stops a slow database write from turning a delivered webhook into a "failed" one that
gets retried. The second is the fence applied to retries: an Attempt that lost its Claim may fail,
but it may not queue the next one.

**Takeaway:** the row is the truth. Every write that matters is conditional on still owning the
row.

## How close does Railhook get?

Here is every place a duplicate can enter, in the order an event travels, and what closes it.
Each close is a unique index or a conditional `UPDATE` in Postgres, not an assumption about timing.

1. **Your POST is retried after a timeout.** Closed by `Idempotency-Key`, unique per project: the
   retry gets back the Event it already created.
2. **A provider resends its webhook.** Closed by the provider's own event id, unique per Source.
3. **The API dies between storing the Event and its Deliveries.** Closed by the transaction: they
   commit together, so there is no between.
4. **Two workers poll at the same moment.** Closed by `SKIP LOCKED`: a locked row is skipped, so
   each row is claimed once.
5. **A worker stalls, its claim times out and another worker claims the row.** The fence records
   one outcome and queues one successor, whichever worker wakes up first.
6. **An Attempt reached your endpoint and its outcome never made it back into Railhook's
   Postgres.** The response was lost on the wire, or the worker holding it died or stalled before
   writing it down. These are the same problem one hop apart, and the first section is why it
   stays open.

Every duplicate that starts inside Railhook is stopped inside Railhook. What reaches your endpoint
twice is only ever window 6: the same Delivery, carrying the same `webhook-id`, and that is exactly
what the id is for. The last step is one line on your side, in the same transaction as the work:

```sql
INSERT INTO processed_webhooks (webhook_id) VALUES ($1) ON CONFLICT DO NOTHING;
```

If it inserted nothing, you have done this one before: answer `2xx` and stop. A whole receiver is
further down.

**Takeaway:** five windows closed in Postgres, one that the Two Generals keep open, and one unique
index on your side that closes it.

## How long does Railhook keep trying?

A `408`, `429`, any `5xx`, a timeout or a connection error puts the Delivery back in Postgres with a
`next_retry_at`. Any other `4xx` or a `3xx` goes straight to Failed Messages: another Attempt will not
change that answer, but a person fixing a token or a URL will. The ladders are declared once:

```java
/** Outgoing: 1m, 5m, 15m, 1h, 6h, 24h. */
public static final String OUTGOING_DELAYS = "60,300,900,3600,21600,86400";

public static final int OUTGOING_MAX_ATTEMPTS = 7;

/** Incoming: 1m, 5m, 15m, 1h, 6h. */
public static final String INCOMING_DELAYS = "60,300,900,3600,21600";

public static final int INCOMING_MAX_ATTEMPTS = 5;
```

(`railhook-common/.../retry/RetryLadderDefaults.java`)

Outgoing: seven Attempts and six waits, about 31 hours and 21 minutes from first to last, each wait
jittered to between 50% and 150% so a thousand Deliveries that failed together do not return
together.

:::figure retry-ladder

Incoming: five Attempts, so the waits used by default are 1m, 5m, 15m and 1h, about 81 minutes in
all. The 6h rung is there for a Destination that raises its attempt count. The difference is
deliberate, and the class says so: holding your own Event for a day is a reasonable promise, while
for somebody else's webhook "a shorter give-up is the better one". The provider has retries of its
own.

A refusal before the request is built (the circuit breaker, a concurrency or rate limit) is a
**Deferral**: the Claim is released and no Attempt is spent, so an endpoint throttled for an hour
does not come out of it with its ladder used up. Behind both ladders sits a hard cap, 96 hours
outgoing and 24 incoming, after which anything outstanding goes to Failed Messages.

**Takeaway:** two ladders on purpose. Retrying someone else's webhook for a day helps nobody.

## Does a failed Delivery hold up the ones behind it?

With ordering on, for a while. Ordering is opt-in per Subscription and outgoing only. Each ordered
Delivery gets an endpoint-scoped **Sequence Number**, assigned *after* the ingest commit so a
rollback cannot burn one, and sent as `X-Sequence-Number`. A Delivery whose predecessors have not
resolved is parked in the **Ordering Buffer**, which is a Deferral: Claim released, token cleared,
no Attempt spent. When the predecessor succeeds or is abandoned, the cursor moves and whatever was
waiting is made due at once.

:::figure ordering-hold

The design choice is what happens when the predecessor does *not* recover. A strict wall would stop
the endpoint for as long as the ladder runs, up to 31 hours for one bad Event. Railhook yields: once
a parked Delivery has waited longer than `ORDERING_GAP_TIMEOUT_SECONDS` (60 by default, measured
from when it was first parked), it goes without its predecessor, and
`webhook_ordering_gap_timeout_total` counts it. If you need order through an outage, raise the
timeout and accept that one poisoned Event holds the endpoint, or carry a version in the payload and
check it on arrival.

**Takeaway:** by default, ordering survives a quick retry and gives way to an outage. That is a
choice, and it is yours to change.

## What does the receiver have to do?

Deduplicate, in the same transaction as the work. The
[Standard Webhooks specification](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)
defines `webhook-id` as an identifier that "remains the same no matter how many times a webhook that
has failed is retried". In Railhook it is the Delivery's id. `webhook-timestamp` is fresh on every
Attempt, and both are covered by the signature, over `id.timestamp.body`. So verify the signature,
then:

```javascript
// A receiver, sketched. `db` is your own Postgres client.
await db.tx(async (tx) => {
  const inserted = await tx.result(
    'INSERT INTO processed_webhooks (webhook_id) VALUES ($1) ON CONFLICT DO NOTHING',
    [req.header('webhook-id')],
  );
  if (inserted.rowCount === 0) return; // seen it: answer 2xx, do nothing
  await applyEvent(tx, req.body);
});
res.sendStatus(204);
```

The dedupe row and the side effect commit together, which is the outbox's lesson applied at the far
end: record the id in one place and act in another, and you have rebuilt the dual write this post
started with. Answer `2xx` to a duplicate too, or you invite the retry you are trying to absorb.

One deliberate exception: a **Replay** (the Time Machine in the dashboard) builds a *new* Delivery
from a stored Event, with a new `webhook-id`, so it gets past your dedupe. That is what a replay is
for. To recognise the same Event across replays, use `X-Event-Id`, which does not change.

**Takeaway:** one unique index on your side turns at-least-once into an exactly-once effect.

## What does polling cost, compared with CDC?

Railhook's workers find work by polling Postgres. The alternative is change data capture: reading
new rows from Postgres's write-ahead log through
[logical decoding](https://www.postgresql.org/docs/current/logicaldecoding-explanation.html), usually
with [Debezium's outbox event router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html).
Here is what polling costs in our implementation.

**Latency.** A poll that fills its batch polls again at once; one that does not sleeps 200 ms. A new
Delivery can wait that long before a worker sees it. CDC would bring this close to commit time.

**Database load.** Each worker runs the claim query every 200 ms whether or not anything is due. A
partial index on the due rows keeps an empty poll cheap. Tailing the WAL does no work while nothing
is written.

CDC does not remove duplicates either: Debezium states that it "provides at-least-once delivery
guarantees"
([Debezium, *Exactly once delivery*](https://debezium.io/documentation/reference/stable/configuration/eos.html)).

| | Accepted Event can be lost | Can send twice | What it adds |
|---|---|---|---|
| Commit, then publish | yes, on a crash between the two | no | nothing |
| Publish, then commit | no, but announces rolled-back rows | yes | nothing |
| Outbox + CDC (Debezium) | no | yes | a connector, replication slots |
| Delivery row as the queue (Railhook) | no | yes | a poll and an index |

So why a poller? Railhook is meant to install with one command on one machine, and the claim query
needs nothing but Postgres. CDC means replication slots to operate, and a stalled slot holds WAL on
disk until someone notices. If Postgres is your only store, the outbox is already a queue: workers
claim rows with `SKIP LOCKED` and no broker is needed. The correctness comes from the transaction.

**Takeaway:** a poller costs up to 200 ms of latency and a steady query per worker. In return the
queue is the same row as the obligation, committed in the same transaction.

## Read the code

[The docs](/docs/) cover [retries](/docs/outgoing/retries/), [ordering](/docs/outgoing/ordering/)
and [signatures](/docs/outgoing/signatures/) from the operator's side. The sequence diagrams are in
[`docs/ARCHITECTURE.md`](https://github.com/vadymkykalo/railhook/blob/main/docs/ARCHITECTURE.md),
and every class quoted here is in [the repository](https://github.com/vadymkykalo/railhook),
MIT-licensed, with its invariants in the javadoc rather than on a slide.
