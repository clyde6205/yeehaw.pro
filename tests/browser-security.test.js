import test from "node:test";
import assert from "node:assert/strict";
import { isPublicAddress, validateTargetUrl } from "../api/_browser/security.js";

test("blocks loopback, private, link-local and mapped private addresses", () => {
  for (const ip of ["127.0.0.1","10.1.2.3","172.16.0.1","192.168.1.2","169.254.169.254","::1","fc00::1","fe80::1","::ffff:127.0.0.1"]) {
    assert.equal(isPublicAddress(ip), false, ip);
  }
});

test("accepts public unicast addresses", () => {
  assert.equal(isPublicAddress("1.1.1.1"), true);
  assert.equal(isPublicAddress("2606:4700:4700::1111"), true);
});

test("rejects non-web schemes, credentials, local hosts and nonstandard ports", () => {
  for (const url of ["file:///etc/passwd","ftp://example.com/","https://u:p@example.com/","http://localhost/","http://service.localhost/","https://example.com:8443/"]) {
    assert.throws(() => validateTargetUrl(url), undefined, url);
  }
});

test("normalizes fragments and accepts ordinary web URLs", () => {
  assert.equal(validateTargetUrl("https://example.com/path#frag").toString(), "https://example.com/path");
});

test("blocks HTTPS redirect downgrade", () => {
  assert.throws(() => validateTargetUrl("http://example.com","https://public.example/"), /HTTPS-to-HTTP/);
});
