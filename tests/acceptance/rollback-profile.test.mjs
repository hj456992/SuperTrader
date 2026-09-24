import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const oldCommit = 'b410f0f321bae43a921cda70f6375277434d2f7c';
const root = new URL('../../', import.meta.url);
const source = execFileSync('git', ['show', `${oldCommit}:web/src/ranch-view.js`], {cwd:root, encoding:'utf8'});
const oldView = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('QA: exact old renderer reads new optional fields without mutating them; new method detail is unavailable', () => {
  const f = JSON.parse(readFileSync(new URL('./fixtures/profile-agent.json', import.meta.url), 'utf8'));
  const p = f.state.people[0];
  p.profile = {...f.legacyProfile, runId:'qa-new-run', version:1, basedOnRevision:7,
    knowledgeStatus:'used', knowledge:[{id:'K-qa-method-0',documentId:'qa-method',title:'QA_NEW_METHOD_TITLE',text:'QA_NEW_METHOD_TEXT'}]};
  Object.assign(p.profile.facets[0], {knowledgeIds:['K-qa-method-0'],counterEvidenceIds:['M-lan-counter'],scope:'少量材料',confidenceReason:'有限证据'});
  p.materials[0].spokenAt = '2024-02-03T10:11:12Z';
  p.analyses = [structuredClone(p.profile)];
  f.state.job = {id:'qa-new-run',status:'interrupted',phase:'finished'};
  const before = structuredClone(f.state);
  const html = oldView.renderRanch(f.state, {page:'person',selected:p.id,tab:'profile'});
  assert.ok(html.includes(p.profile.summary));
  assert.ok(html.includes('我一般周末愿意散步。'));
  assert.ok(!html.includes('QA_NEW_METHOD_TITLE'));
  assert.ok(!html.includes('QA_NEW_METHOD_TEXT'));
  assert.deepEqual(f.state,before);
});
