import { test } from "node:test";
import assert from "node:assert/strict";
import {
  bumpVersion,
  formatVersion,
  noteItems,
  objectsToPrune,
  parseVersion,
  versionCode,
} from "./release-version.mjs";

test("a version is exactly MAJOR.MINOR.PATCH", () => {
  assert.deepEqual(parseVersion("1.4.2"), { major: 1, minor: 4, patch: 2 });
  for (const bad of ["1.4", "v1.4.2", "1.4.2-beta", "1.1000.0", "1.0.1000", "", null]) {
    assert.equal(parseVersion(bad), null, String(bad));
  }
});

test("bumping resets everything to the right of the bumped part", () => {
  const v = { major: 1, minor: 4, patch: 2 };
  assert.equal(formatVersion(bumpVersion(v, "patch")), "1.4.3");
  assert.equal(formatVersion(bumpVersion(v, "minor")), "1.5.0");
  assert.equal(formatVersion(bumpVersion(v, "major")), "2.0.0");
  assert.throws(() => bumpVersion(v, "micro"));
});

test("versionCode orders like the version", () => {
  assert.equal(versionCode({ major: 1, minor: 2, patch: 3 }), 1_002_003);
  assert.ok(versionCode({ major: 1, minor: 10, patch: 0 }) > versionCode({ major: 1, minor: 9, patch: 999 }));
  assert.ok(versionCode({ major: 2, minor: 0, patch: 0 }) > versionCode({ major: 1, minor: 999, patch: 999 }));
  assert.equal(versionCode({ major: 0, minor: 0, patch: 0 }), 1);
});

test("notes become one bullet per line, whatever bullet was typed", () => {
  assert.deepEqual(noteItems("- one\n* two\n• three\n\n  four  \n"), ["one", "two", "three", "four"]);
  assert.deepEqual(noteItems("-5 zł is still text"), ["-5 zł is still text"]);
  assert.deepEqual(noteItems(""), []);
});

test("the bucket keeps the two newest releases and nothing it did not upload is touched", () => {
  const rows = [51, 57, 52, 55].map((code) => ({ version_code: code, object_key: `monyx-${code}.apk` }));
  const keys = ["monyx-51.apk", "monyx-52.apk", "monyx-55.apk", "monyx-57.apk", "notes.txt"];
  assert.deepEqual(objectsToPrune(rows, keys), ["monyx-51.apk", "monyx-52.apk"]);
  assert.deepEqual(objectsToPrune([], ["monyx-1.apk"]), ["monyx-1.apk"]);
});
