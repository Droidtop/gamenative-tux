# Changelog

All notable changes this fork makes are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Upstream's own
changes arrive through the upstream sync and are recorded in utkarshdalal/GameNative's history; the
fork does not cut versioned releases of its own, so its changes are recorded
under Unreleased.

## [Unreleased]

### Added

- The native-Linux launch path this fork exists for (2026-08-22): a modular Linux/proot execution backend, ELF launch wired into `setupXEnvironment`, depot and launch-entry detection, and the depot download as an opt-in.
- Native Linux games run inside Valve's Steam Runtimes (scout, soldier, sniper) (2026-08-30).
- Arbitrary game folders adopted in place, as user scan roots (2026-08-30).
- Epic and GOG updates through the same download path as install (2026-08-26).
- A push-triggered CI workflow, because the inherited one only ran on pull requests (2026-08-22).
- Prefix preparation callable from outside the game screen (2026-09-10); achievement directories watched on API 26 as well as 29 (2026-09-11).

### Changed

- Workflow actions are pinned by commit and the build token is read-only (2026-09-24).
- The preferences are read once before the first write on startup (2026-09-25).
- The upstream sync is dispatch only (2026-10-09): the fork has diverged and the daily merge conflicted on every run.
- The application's startup is split so the Hilt application annotation and the bootstrap live apart (2026-08-31).

### Fixed

- A startup crash risk on devices without Google Play Services (2026-08-26).
- Tar entries that would land outside the extraction destination are refused (2026-09-02).

### Removed

- Google Play Integrity, and the signed-release workflows of upstream that depended on its secrets, consolidated into the fork's own CI (2026-08-30).
