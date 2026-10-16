// node --test platform/test — модель сцены на демо-наборах
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {buildScene, routeTo, branchEnd, pointAt, segX, inRing, dnRow, kDepth} from '../js/model.js';

const demo = n => JSON.parse(readFileSync(new URL(`../demo/${n}`, import.meta.url), 'utf8'));

test('геометрия и таблицы', () => {
  assert.deepEqual(segX([0, 0], [2, 0], [1, -1], [1, 1]).slice(1), [1, 0]);
  assert.equal(segX([0, 0], [1, 0], [0, 1], [1, 1]), null);
  assert.ok(inRing(0.5, 0.5, [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]));
  assert.ok(!inRing(2, 2, [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]));
  assert.equal(dnRow(300).h, 0.45);
  assert.equal(dnRow(350).dn, 400);
  assert.ok(Math.abs(kDepth(4.21) - 1.121) < 1e-9);
  assert.equal(kDepth(2), 1);
});

test('small-1: два варианта, все здания подключены, трасса до здания', async () => {
  const sc = await buildScene(demo('small-1.in.geojson'), demo('small-1.out.geojson'));
  assert.equal(sc.variants.length, 2);
  assert.equal(sc.oks.length, 17);
  const v = sc.variants[0];
  assert.equal(v.id, '1');
  assert.equal(v.connected.size, 17);
  const r = routeTo(v, v.segs[0].to);
  assert.ok(r && r.len > 0 && r.segs.length >= 1);
  const end = branchEnd(v, r.segs[0]);
  assert.ok(routeTo(v, end));
  const p = pointAt(r, r.len / 2);
  assert.ok(Number.isFinite(p.x) && p.d > 0);
});

test('spb-kupchino: глубины, пересечения и неподключённые', async () => {
  const sc = await buildScene(demo('spb-kupchino.in.geojson'), demo('spb-kupchino.out.geojson'));
  assert.ok(sc.hasDepth);
  const v = sc.variants[0];
  assert.equal(v.segs.length, 258);
  assert.equal(sc.oks.length - v.connected.size, v.summary.unconnected_oks_ids.length);
  assert.ok(v.crossings.length > 0);
  for (const c of v.crossings) if (c.type !== 'segment') assert.ok(Number.isFinite(c.gap));
});
