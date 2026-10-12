// A D1-shaped wrapper over Node's built-in node:sqlite, with every migration
// in ../migrations applied in order. Just the calls the Worker uses:
// prepare().bind().first/all/run and batch() (one transaction; SELECTs and
// RETURNING statements give { results }, everything else { meta.changes }).
import { readFileSync, readdirSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const dir = new URL('../migrations/', import.meta.url);

export function d1() {
  const db = new DatabaseSync(':memory:');
  for (const f of readdirSync(dir).filter(f => f.endsWith('.sql')).sort()) db.exec(readFileSync(new URL(f, dir), 'utf8'));
  const returnsRows = sql => /^\s*(SELECT|WITH)\b/i.test(sql) || /\bRETURNING\b/i.test(sql);
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    first: col => { const row = db.prepare(sql).get(...args); return row ? (col ? row[col] : { ...row }) : null; },
    all: () => ({ results: db.prepare(sql).all(...args).map(r => ({ ...r })), meta: {} }),
    run: () => { const r = db.prepare(sql).run(...args); return { results: [], meta: { changes: Number(r.changes) } }; },
    exec() { return returnsRows(sql) ? this.all() : this.run(); },
  });
  return {
    raw: db,
    prepare: sql => stmt(sql),
    batch: list => { db.exec('BEGIN'); try { const out = list.map(s => s.exec()); db.exec('COMMIT'); return out; } catch (e) { db.exec('ROLLBACK'); throw e; } },
  };
}
