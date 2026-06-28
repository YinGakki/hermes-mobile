// Bionic Bypass — monkey-patch Node.js APIs that crash on Android's
// Bionic libc. Injected via NODE_OPTIONS=--require bionic-bypass.js.
//
// Based on openclaw-termux's lib/bionic-bypass.js (MIT License, Mithun Gowda B).
// Adapted for hermes-web-ui on Hermes Agent Android.
//
// Problem: Android uses Bionic libc (not glibc). Node.js's
// os.networkInterfaces() calls getifaddrs() which may crash or return
// garbage on Bionic. This monkey-patch wraps it in a try/catch and
// returns a minimal loopback interface on failure.
//
// Usage: Set NODE_OPTIONS="--require /path/to/bionic-bypass.js" in
// the environment before starting hermes-web-ui.

'use strict';

const os = require('os');

// Save original function
const originalNetworkInterfaces = os.networkInterfaces;

// Monkey-patch os.networkInterfaces
os.networkInterfaces = function () {
  try {
    const result = originalNetworkInterfaces.call(os);
    // Validate result looks sane (has at least 'lo')
    if (result && typeof result === 'object') {
      return result;
    }
    throw new Error('networkInterfaces returned invalid result');
  } catch (err) {
    // Fallback: return minimal loopback interface
    // This prevents crashes when code tries to enumerate network
    // interfaces (e.g. for binding to 0.0.0.0 or detecting LAN IP).
    return {
      lo: [
        {
          address: '127.0.0.1',
          netmask: '255.0.0.0',
          family: 'IPv4',
          mac: '00:00:00:00:00:00',
          internal: true,
          cidr: '127.0.0.1/8',
        },
        {
          address: '::1',
          netmask: 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff',
          family: 'IPv6',
          mac: '00:00:00:00:00:00',
          internal: true,
          cidr: '::1/128',
        },
      ],
    };
  }
};

// Also patch os.hostname() which may fail on some Android builds
const originalHostname = os.hostname;
os.hostname = function () {
  try {
    return originalHostname.call(os) || 'localhost';
  } catch (err) {
    return 'localhost';
  }
};

// Patch process.versions to include a bionic flag so downstream code
// can detect we're running on Android's Bionic libc.
try {
  if (!process.versions.bionic) {
    Object.defineProperty(process.versions, 'bionic', {
      value: true,
      writable: false,
      enumerable: true,
      configurable: false,
    });
  }
} catch (err) {
  // If we can't set it, don't crash — just log.
  console.error('[bionic-bypass] could not set process.versions.bionic:', err.message);
}

console.log('[bionic-bypass] Android Bionic libc patches applied');
