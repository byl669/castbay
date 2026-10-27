# Test builds for users

A fix for something seen on one person's device is better tried by that person before it becomes a
release: every public version is one more thing everybody updates to. A test build is a **GitHub
pre-release** built and signed by the same workflow as a release.

- It is not "Latest": the website's download button (`releases/latest`), the website's `latest.json`
  and F-Droid (which only follows `vX.Y.Z` tags) do not see it.
- The app's update check ignores it (pre-releases and tags with a suffix are skipped in
  `UpdateChecker.newest`), so nobody is offered it, unless they turned on **Settings > General >
  Receive Test Versions** (off by default): then About offers the newest test version, with
  Download and install (SHA-256 checked, Android's installer confirms), and the release that follows it.
- It is signed with the release key, so it installs over the release app and keeps its settings.
- The app says `1.8.7-beta.1` in About, the diagnostics and problem reports, so a report from a test
  build cannot be taken for the release.

## How

1. On the fix's branch set `versionName` to the next version (`1.8.7`) and raise `versionCode` by one
   (126). Do **not** add the changelog files (`web/src/content/releases/…`): a test build needs none,
   and the website would list the version.
2. Run the smoke test on the branch: `gh workflow run smoke.yml --ref BRANCH`.
3. Tag the branch's commit and push the tag over SSH (the workflow file rules):
   `git tag v1.8.7-beta.1 && git push git@github.com:weenas/castbay.git v1.8.7-beta.1`.
   The tag's part before the first `-` must equal `versionName`; the part after becomes the suffix.
4. The release workflow publishes a pre-release with `CastBay-1.8.7-beta.1.apk`, its SHA-256 and the
   certificate fingerprint. Ask the tester to turn on Receive Test Versions (Settings > General), then
   About > Download and install; the first time Android asks to allow installing from CastBay (a system
   setting only they can give). Or give them the pre-release link to install by hand. Ask them to try
   the case they reported and to upload a diagnostics report (its number appears in the issue).
5. Another round is `v1.8.7-beta.2` (same `versionCode`: Android lets an equal code install over).
6. When it is confirmed, make the release as usual (changelog files, merge, tag `v1.8.7`). 1.8.7 is
   newer than 1.8.7-beta.1, so the tester's app offers it through the normal update check (the release
   has the same `versionCode`; Android installs an equal code over). Delete the pre-releases afterwards
   if they are no longer wanted (`gh release delete v1.8.7-beta.1 --cleanup-tag`).

## Not covered

- The test versions list is read from GitHub (api.github.com), not through the website's relay: a
  tester where GitHub cannot be reached needs the APK and its SHA-256 sent another way.
- The privacy policy (web/src/pages/privacy.md and zh/privacy.md, "Update check") must say, in the release
  that ships the switch, that with it on the app also asks GitHub's release list for test versions.
- Debug builds can point the list at a test server (`debug_releases_url` in the `updates` preferences),
  which is how the flow was tried on the Sony: found, downloaded, SHA-256 checked, Android's installer
  opened (it refused: unknown sources not yet allowed for the app, as for any first update).
