# Security policy

## Scope

This repository holds clean-room replacement apps for an aftermarket car head
unit. It runs no server and stores no user accounts.

In scope:

- An app here that leaks data off the unit, or reaches beyond the permissions it
  declares.
- A build or deploy script that writes somewhere it does not claim to.
- Committed secrets (keystore material, credentials, device identifiers).

Vulnerabilities in the **vendor's** firmware, apps or cloud services are out of
scope here — report those to the vendor.

## Reporting

Email **sasha@ripostelabs.xyz** with what you found, how to reproduce it, and the
affected commit. Expect an acknowledgement within a few days; this is a personal
project, not a staffed product.

Please do not open a public issue for something that puts other owners' units at
risk before there has been a chance to fix it.

## Not a vulnerability

- **APKs are signed with a throwaway debug key.** `debug.keystore` is git-ignored
  and minted per build host. These are side-loaded apps for a rooted unit, not
  Play distribution. Verify signatures yourself if it matters to you.
- **Apps read the launcher's theme provider.** It is a read-only content provider
  exposing colour values, declared `grantUriPermissions="false"`.
- **Some apps request network access.** The weather, news and currency apps fetch
  from public endpoints; each sets its own User-Agent and sends no device data.
