import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {renderProfile, renderRanch} from '../../web/src/ranch-view.js';
const fixture = () => JSON.parse(readFileSync(new URL('./fixtures/profile-agent.json', import.meta.url), 'utf8'));

test('QA: reopening serialized legacy state retains its profile and real source text', () => {
  const f = fixture();
  f.state.people[0].profile = f.legacyProfile;
  const saved = JSON.parse(JSON.stringify(f.state));
  const html = renderRanch(saved, {page:'person', selected:'person-lan', tab:'profile'});
  assert.ok(html.includes(f.legacyProfile.summary));
  assert.ok(html.includes('我一般周末愿意散步。'));
  assert.ok(!html.includes('我每个周末都参加长跑。'));
});

test('QA: missing old source is disclosed instead of replaced by another person or book', () => {
  const f = fixture();
  f.state.people[0].materials = [];
  const html = renderProfile(f.legacyProfile, f.state.people[0], f.state.self);
  assert.ok(html.includes('来源已不可用'));
  assert.ok(!html.includes('我每个周末都参加长跑。'));
});

test('QA: imported source markup remains inert in legacy rendering', () => {
  const f = fixture();
  f.state.people[0].materials[0].text = '<img src=x onerror="qa()">';
  const html = renderProfile(f.legacyProfile, f.state.people[0], f.state.self);
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;img'));
});
