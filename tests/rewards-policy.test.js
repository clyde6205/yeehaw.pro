import test from "node:test";
import assert from "node:assert/strict";
import { decideReward, makeIdempotencyKey, REWARD_POLICY } from "../api/_rewards/policy.js";

test("reward policy only awards points for verified reading sessions", () => {
  assert.deepEqual(
    decideReward({ eventType: "verified_reading_session", pointsGrantedToday: 0 }),
    { eligible: true, reason: "eligible", points: 1 }
  );
  assert.equal(decideReward({ eventType: "page_view" }).reason, "event_not_eligible");
});

test("duplicate events and daily cap never award additional points", () => {
  assert.equal(decideReward({ eventType: "verified_reading_session", alreadyGranted: true }).reason, "duplicate_event");
  assert.equal(decideReward({ eventType: "verified_reading_session", pointsGrantedToday: 10 }).reason, "daily_cap_reached");
  assert.equal(REWARD_POLICY.dailyPointCap, 10);
});

test("idempotency key is deterministic and scoped to user, event type and event id", () => {
  const input = { userId: "user_12345678", eventType: "verified_reading_session", eventId: "session_12345678" };
  assert.equal(makeIdempotencyKey(input), makeIdempotencyKey(input));
  assert.notEqual(makeIdempotencyKey(input), makeIdempotencyKey({ ...input, userId: "user_87654321" }));
});

test("malformed identities and unsupported event types are rejected", () => {
  assert.throws(() => makeIdempotencyKey({ userId: "x", eventType: "verified_reading_session", eventId: "session_12345678" }));
  assert.throws(() => makeIdempotencyKey({ userId: "user_12345678", eventType: "page_view", eventId: "session_12345678" }));
  assert.throws(() => decideReward({ eventType: "verified_reading_session", pointsGrantedToday: -1 }));
});
