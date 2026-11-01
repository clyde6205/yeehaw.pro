import { createHash } from "node:crypto";

export const REWARD_POLICY = Object.freeze({
  version: 1,
  dailyPointCap: 10,
  pointsPerVerifiedSession: 1,
  eligibleEvents: Object.freeze(["verified_reading_session"])
});

export function makeIdempotencyKey({ userId, eventType, eventId }) {
  if (typeof userId !== "string" || !/^[a-zA-Z0-9:_-]{8,128}$/.test(userId)) {
    throw new TypeError("A valid authenticated user identifier is required.");
  }
  if (!REWARD_POLICY.eligibleEvents.includes(eventType)) {
    throw new TypeError("This event type is not eligible for rewards.");
  }
  if (typeof eventId !== "string" || !/^[a-zA-Z0-9:_-]{8,128}$/.test(eventId)) {
    throw new TypeError("A valid server-issued event identifier is required.");
  }
  return createHash("sha256")
    .update([userId, eventType, eventId].join("\\0"))
    .digest("hex");
}

/**
 * Pure policy decision. Persistence must enforce idempotency and the daily cap
 * transactionally; this function is not an authorization mechanism.
 */
export function decideReward({ eventType, alreadyGranted, pointsGrantedToday = 0 }) {
  if (!REWARD_POLICY.eligibleEvents.includes(eventType)) {
    return { eligible: false, reason: "event_not_eligible", points: 0 };
  }
  if (alreadyGranted) {
    return { eligible: false, reason: "duplicate_event", points: 0 };
  }
  if (!Number.isSafeInteger(pointsGrantedToday) || pointsGrantedToday < 0) {
    throw new TypeError("Daily points total must be a non-negative safe integer.");
  }
  if (pointsGrantedToday >= REWARD_POLICY.dailyPointCap) {
    return { eligible: false, reason: "daily_cap_reached", points: 0 };
  }
  return {
    eligible: true,
    reason: "eligible",
    points: Math.min(
      REWARD_POLICY.pointsPerVerifiedSession,
      REWARD_POLICY.dailyPointCap - pointsGrantedToday
    )
  };
}
