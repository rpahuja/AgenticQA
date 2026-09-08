'use strict';
/**
 * Zero-dependency JSON persistence with atomic writes.
 * Used instead of a database engine so the collector can run on the
 * Node.js runtime that ships inside VS Code — nothing to install.
 */
const fs = require('fs');
const path = require('path');

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

/**
 * Write a file atomically: write to a temp file, then rename over the target.
 * This keeps JSON files consistent even if the process dies mid-write.
 */
function atomicWriteSync(file, text) {
  ensureDir(path.dirname(file));
  const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, text, 'utf8');
  try {
    fs.renameSync(tmp, file);
  } catch (e) {
    // Windows can refuse to rename over an existing file in rare cases.
    try { fs.unlinkSync(file); } catch (_) { /* ignore */ }
    fs.renameSync(tmp, file);
  }
}

function appendLineSync(file, text) {
  ensureDir(path.dirname(file));
  fs.appendFileSync(file, `${text}\n`, 'utf8');
}

class JsonStore {
  constructor(dir) {
    this.dir = dir;
    ensureDir(dir);
  }

  read(name, fallback) {
    try {
      return JSON.parse(fs.readFileSync(path.join(this.dir, name), 'utf8'));
    } catch (_) {
      return fallback;
    }
  }

  write(name, value) {
    atomicWriteSync(path.join(this.dir, name), JSON.stringify(value, null, 2));
  }
}

module.exports = { ensureDir, atomicWriteSync, appendLineSync, JsonStore };
