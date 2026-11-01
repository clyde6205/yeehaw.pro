# Yehaw Utility and Rewards Architecture

## Product boundary

Yehaw Lite must remain useful without a wallet, token, exchange listing, or paid transaction. The first rewards instrument is **off-chain Yehaw Points**, not a cryptocurrency and not a promise of future token value. Points are an engagement/reward record with published rules; they are not redeemable for cash unless a later approved program explicitly provides that feature.

The points policy currently permits one point per verified reading session, with a hard cap of 10 points per user per UTC day. Ordinary page views, refreshes, background tabs, and automated traffic are not eligible. A production grant must be issued by a trusted server after authentication and abuse checks; the pure policy helper is not an authorization boundary.

## Architecture added

- `api/_rewards/policy.js`: deterministic eligibility, daily cap, and idempotency-key logic.
- `db/migrations/001_rewards_foundation.sql`: append-only reward events, wallet links, and chain transaction records with uniqueness constraints.
- `tests/rewards-policy.test.js`: policy and replay-prevention unit tests.

The database migration is intentionally not auto-applied. Run it through a controlled migration process after reviewing the production database and backup.

## Solana and Base strategy

Support both networks at the wallet-adapter/interface level, but select **one canonical issuance network** before token deployment. Do not independently mint two unrelated tokens with the same ticker. A cross-chain representation would require a separately audited bridge or canonical lock/mint design and additional operational controls.

- **Solana:** issue a standard SPL Token; evaluate Token-2022 extensions only when a documented requirement justifies their added wallet/exchange compatibility risk.
- **Base:** issue a standard ERC-20 with well-reviewed, pinned OpenZeppelin contracts and verified source. Avoid custom transfer taxes, rebasing, hidden owner powers, or upgradeability unless an independently reviewed threat model demands them.
- Keep chain-specific signing and RPC calls behind adapters. Wallets sign user-authorized messages/transactions; Yehaw never collects seed phrases or private keys.
- Use testnets first. Mainnet deployment requires independent contract audit, key-management plan, incident response, multisig governance, verified token metadata, and legal review in each target market.

## Suggested staged rollout

1. Launch the browser with no token dependency.
2. Launch authenticated, off-chain points with fraud controls, published terms, privacy disclosures, rate limits, and a support/appeals process.
3. Add wallet ownership challenges using short-lived, single-use nonces. Verify signatures server-side for Solana and EVM separately. Never trust a wallet address supplied without signature verification.
4. Publish token utility, supply, allocation, vesting, emissions, treasury controls, redemption rules, and risk disclosures before any token sale or distribution.
5. Deploy to one selected testnet, run integration tests, and complete an independent audit.
6. Decide whether a token is actually needed. If approved, deploy one canonical token and make claims idempotent, rate-limited, observable, and reversible only through a documented incident procedure.

## Hard launch gates

- No guaranteed earnings, investment-return language, or implied token price.
- No token minting, sale, exchange listing, or public claim endpoint in this milestone.
- No custodial wallets or seed-phrase collection.
- KYC/AML, sanctions, consumer-protection, tax, privacy, advertising, and digital-asset legal review where applicable.
- Threat model and independent security review for rewards abuse, wallet-linking, contract permissions, treasury, and key custody.
- Regional RPC availability, fees, transaction failure handling, and user-friendly non-crypto fallback.

## Configuration and deployment

The migration is in `db/migrations/001_rewards_foundation.sql`. It is not automatically executed by Vercel. Apply it with a reviewed migration tool against a backed-up database. Do not put RPC private keys, treasury keys, or signing secrets in client-side code or public environment variables.
