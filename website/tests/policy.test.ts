import test from 'node:test';
import assert from 'node:assert/strict';
import { apps, evaluate, type Verdict } from '../src/lib/model.ts';
// Synthetic fixtures for the requested scenarios, never live service assessments.
const expected: Verdict[][] = [
  ['approve','approve','approve','approve','approve'],
  ['approve','approve','abstain','approve','age16'],
  ['abstain','approve','abstain','disapprove','abstain'],
  ['revoke','approve','abstain','abstain','abstain'],
  ['approve','disapprove','abstain','abstain','abstain'],
  ['approve','approve','abstain','disapprove','disapprove'],
];
const presetApps = apps.filter(app => app.id !== 'custom');
test('all six requested scenario matrices are preserved', () => assert.deepEqual(presetApps.map(app => [...app.verdicts]), expected));
test('all active notaries must approve for a silent install', () => {
  assert.equal(evaluate(['approve']), 'allow');
  assert.equal(evaluate(['approve','approve']), 'allow');
  assert.equal(evaluate(['approve','abstain']), 'review');
  assert.equal(evaluate(['approve','age16']), 'review');
});
test('unanimous disapproval or revocation blocks, but dissent or abstention asks', () => {
  assert.equal(evaluate(['revoke']), 'block');
  assert.equal(evaluate(['revoke','disapprove']), 'block');
  assert.equal(evaluate(['disapprove','approve']), 'review');
  assert.equal(evaluate(['disapprove','abstain']), 'review');
});
test('no notaries and all abstentions never silently approve or block', () => {
  assert.equal(evaluate([]), 'review');
  assert.equal(evaluate(['abstain','abstain']), 'review');
});
test('all 192 app and active-notary combinations obey unanimity', () => {
  for (const app of presetApps) for (let mask = 0; mask < 32; mask++) {
    const votes = app.verdicts.filter((_, i) => mask & (1 << i));
    const result = evaluate(votes);
    if (result === 'allow') assert.ok(votes.length && votes.every(v => v === 'approve'));
    else if (result === 'block') assert.ok(votes.length && votes.every(v => v === 'revoke' || v === 'disapprove'));
    else assert.ok(!votes.length || (votes.some(v => v !== 'approve') && votes.some(v => v !== 'revoke' && v !== 'disapprove')));
  }
});
