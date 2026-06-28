// Bionic Bypass — monkey-patch Node.js APIs that crash on Android's
// Bionic libc. Injected via NODE_OPTIONS=--require bionic-bypass.js.
//
// Based on openclaw-termux's lib/bionic-bypass.js + proot-compat.js
// (MIT License, Mithun Gowda B). Adapted for hermes-web-ui on Hermes
// Agent Android.
//
// Problem: Android uses Bionic libc (not glibc). Many Node.js APIs
// call syscalls that either crash, return ENOSYS, or return garbage
// on Bionic. This monkey-patch wraps each one in a try/catch and
// returns a safe fallback.
//
// Usage: Set NODE_OPTIONS="--require /path/to/bionic-bypass.js" in
// the environment before starting hermes-web-ui.

'use strict';

const os = require('os');
const fs = require('fs');
const cp = require('child_process');

// ── os.networkInterfaces ─────────────────────────────────────────────────
// getifaddrs() may crash or return garbage on Bionic. Fall back to
// a minimal loopback interface.

const originalNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = function () {
  try {
    const result = originalNetworkInterfaces.call(os);
    if (result && typeof result === 'object') return result;
    throw new Error('networkInterfaces returned invalid result');
  } catch (err) {
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

// ── os.hostname ─────────────────────────────────────────────────────────
// gethostname() may fail on some Android builds.

const originalHostname = os.hostname;
os.hostname = function () {
  try {
    return originalHostname.call(os) || 'localhost';
  } catch (err) {
    return 'localhost';
  }
};

// ── os.cpus ──────────────────────────────────────────────────────────────
// /proc/cpuinfo parsing may fail or return empty on some Android devices.
// Return a minimal single-CPU entry so libraries that check .length > 0 work.

const originalCpus = os.cpus;
os.cpus = function () {
  try {
    const result = originalCpus.call(os);
    if (Array.isArray(result) && result.length > 0) return result;
    throw new Error('cpus returned empty');
  } catch (err) {
    return [
      {
        model: 'Android CPU',
        speed: 0,
        times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 },
      },
    ];
  }
};

// ── os.totalmem / os.freemem ─────────────────────────────────────────────
// sysconf(_SC_PHYS_PAGES) may return 0 or fail on some Android builds.

const originalTotalmem = os.totalmem;
os.totalmem = function () {
  try {
    const result = originalTotalmem.call(os);
    if (result > 0) return result;
    throw new Error('totalmem returned 0');
  } catch (err) {
    // Default to 4GB — enough for any sanity check
    return 4 * 1024 * 1024 * 1024;
  }
};

const originalFreemem = os.freemem;
os.freemem = function () {
  try {
    const result = originalFreemem.call(os);
    if (result >= 0) return result;
    throw new Error('freemem returned negative');
  } catch (err) {
    return 1 * 1024 * 1024 * 1024; // 1GB free
  }
};

// ── os.userInfo ─────────────────────────────────────────────────────────
// getpwuid_r may fail on Android (no /etc/passwd by default).

const originalUserInfo = os.userInfo;
os.userInfo = function (opts) {
  try {
    const result = originalUserInfo.call(os, opts);
    if (result && result.username) return result;
    throw new Error('userInfo returned empty');
  } catch (err) {
    return {
      uid: 0,
      gid: 0,
      username: 'root',
      homedir: process.env.HOME || '/data',
      shell: '/bin/sh',
    };
  }
};

// ── process.cwd ──────────────────────────────────────────────────────────
// On some Android setups, getcwd() returns ENOSYS. Fall back to env HOME.

const originalCwd = process.cwd;
let cwdCache = null;
process.cwd = function () {
  if (cwdCache) return cwdCache;
  try {
    cwdCache = originalCwd.call(process);
    if (cwdCache) return cwdCache;
    throw new Error('cwd returned empty');
  } catch (err) {
    cwdCache = process.env.HOME || process.env.PREFIX || '/';
    return cwdCache;
  }
};

// ── fs.watch / fs.watchFile ───────────────────────────────────────────────
// inotify may not work inside proot or on some Android filesystems.
// Return a noop watcher so file-watching libraries don't crash.

const originalWatch = fs.watch;
fs.watch = function (filename, options, listener) {
  try {
    const watcher = originalWatch.call(fs, filename, options, listener);
    if (watcher) return watcher;
    throw new Error('watch returned null');
  } catch (err) {
    // Return a minimal EventEmitter-like object that does nothing
    return {
      close: function () {},
      on: function () { return this; },
      once: function () { return this; },
      emit: function () { return false; },
      addListener: function () { return this; },
      removeListener: function () { return this; },
      removeAllListeners: function () { return this; },
    };
  }
};

const originalWatchFile = fs.watchFile;
fs.watchFile = function (filename, options, listener) {
  try {
    originalWatchFile.call(fs, filename, options, listener);
  } catch (err) {
    // Silently ignore — file watching is non-critical
  }
};

fs.unwatchFile = function (filename, listener) {
  try {
    fs.unwatchFile.call(fs, filename, listener);
  } catch (err) {
    // Silently ignore
  }
};

// ── fs.chmod / fs.chown ──────────────────────────────────────────────────
// These may return ENOSYS or EPERM on Android. Wrap to tolerate.

const originalChmod = fs.chmod;
fs.chmod = function (path, mode, callback) {
  if (typeof callback === 'function') {
    originalChmod.call(fs, path, mode, function (err) {
      if (err && (err.code === 'ENOSYS' || err.code === 'EPERM')) {
        callback(null);
      } else {
        callback(err);
      }
    });
  } else {
    try {
      return originalChmod.call(fs, path, mode);
    } catch (err) {
      if (err.code !== 'ENOSYS' && err.code !== 'EPERM') throw err;
    }
  }
};

const originalChown = fs.chown;
fs.chown = function (path, uid, gid, callback) {
  if (typeof callback === 'function') {
    originalChown.call(fs, path, uid, gid, function (err) {
      if (err && (err.code === 'ENOSYS' || err.code === 'EPERM')) {
        callback(null);
      } else {
        callback(err);
      }
    });
  } else {
    try {
      return originalChown.call(fs, path, uid, gid);
    } catch (err) {
      if (err.code !== 'ENOSYS' && err.code !== 'EPERM') throw err;
    }
  }
};

// ── child_process.spawn / exec ───────────────────────────────────────────
// fork()/exec() may fail inside proot or on certain Android versions.
// Wrap to provide better error messages instead of silent crashes.

const originalSpawn = cp.spawn;
cp.spawn = function (command, args, options) {
  try {
    const child = originalSpawn.call(cp, command, args, options);
    // Add error handler to prevent uncaught 'error' event
    if (child && !child._bypassErrorHandlerAdded) {
      child.on('error', function (err) {
        // Log but don't crash — caller should have their own handler
        console.error('[bionic-bypass] child_process.spawn error:', err.message);
      });
      child._bypassErrorHandlerAdded = true;
    }
    return child;
  } catch (err) {
    // Return a fake child process that emits 'error'
    const EventEmitter = require('events');
    const fake = new EventEmitter();
    fake.pid = null;
    fake.stdout = { on: function () {}, pipe: function () {} };
    fake.stderr = { on: function () {}, pipe: function () {} };
    fake.stdin = { on: function () {}, write: function () {}, end: function () {} };
    fake.kill = function () {};
    process.nextTick(function () {
      fake.emit('error', new Error('spawn failed: ' + err.message));
    });
    return fake;
  }
};

// ── process.versions.bionic flag ──────────────────────────────────────────
// Let downstream code detect we're on Bionic.

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
  console.error('[bionic-bypass] could not set process.versions.bionic:', err.message);
}

console.log('[bionic-bypass] Android Bionic libc patches applied (networkInterfaces, hostname, cpus, totalmem, freemem, userInfo, cwd, fs.watch, fs.chmod, child_process)');
