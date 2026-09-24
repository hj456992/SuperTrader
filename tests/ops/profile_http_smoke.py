"""Explicit real-model HTTP probe for the TL-owned fictional instance at 48749.

Does not launch a server, provision/delete schemas, or access source integrations.
Only run after TL authorizes the expected integration SHA and ops owns the server PID.
"""
import argparse
import base64
import copy
import http.cookiejar
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json.loads((Path(__file__).parent / 'fixtures/profile-real-smoke.json').read_text())
BASE = 'http://127.0.0.1:48749'


class SmokeFailure(Exception):
    pass


class Probe:
    def __init__(self):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.checks = []
        with self.client.open(BASE + '/', timeout=5) as response:
            self.require(response.status == 200, 'homepage')

    def require(self, condition, label):
        if not condition:
            raise SmokeFailure(label)
        self.checks.append(label)

    def request(self, route, body=None):
        headers = {'Origin': BASE, 'X-Garden-Request': '1', 'Content-Type': 'application/json'}
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        try:
            with self.client.open(urllib.request.Request(BASE + route, data=data, headers=headers), timeout=50) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise SmokeFailure('HTTP ' + str(error.code) + ' ' + route) from None

    def state(self):
        return self.request('/api/ranch-state')

    def command(self, action, **body):
        if action != 'cancel':
            body['revision'] = self.state()['revision']
        return self.request('/api/ranch-' + action, body)

    def person(self, state):
        matches = [person for person in state['people'] if person['name'] == FIXTURE['person']['name']]
        self.require(len(matches) == 1 and len(state['people']) == 1, 'fictional-person-only')
        return matches[0]

    def setup(self):
        state = self.state()
        self.require(state['revision'] == 0 and not state['people'] and not state['library']
                     and not state['self'].get('profile'), 'fresh-isolated-state')
        self.command('self-update', **FIXTURE['self'])
        state = self.command('person-create', **FIXTURE['person'])
        pid = self.person(state)['id']
        for source in FIXTURE['materials']:
            material = dict(source)
            target = material.pop('target')
            self.command('material-add', id='self' if target == 'self' else pid, **material)
        self.require(len(self.state()['self']['materials']) == 2, 'self-fixture-imported')
        self.require(len(self.person(self.state())['materials']) == 4, 'person-fixture-imported')

    def upload(self):
        self.require(not self.state()['library'], 'empty-library-before-upload')
        book = FIXTURE['book']
        self.command('knowledge-upload', filename=book['filename'], title=book['title'],
                     dataBase64=base64.b64encode(book['text'].encode()).decode())
        self.require(len(self.state()['library']) == 1, 'fictional-book-imported')

    def wait(self, run_id, revision, limit=150):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            state = self.state()
            job = state['job']
            self.require(job['id'] == run_id, 'same-run')
            if job['status'] not in ('running', 'cancelling'):
                return state
            if state['revision'] == revision:
                self.require(True, 'progress-does-not-change-revision')
            else:
                target = (state.get('self', {}) if job.get('targetId') == 'self' else
                          next((p for p in state.get('people', []) if p.get('id') == job.get('targetId')), {}))
                saved = target.get('profile') or {}
                self.require(job['status'] == 'running' and job.get('phase') == 'saving'
                             and job.get('kind') == 'profile' and state['revision'] == revision + 1
                             and saved.get('runId') == run_id and saved.get('basedOnRevision') == revision,
                             'saving-transition-must-belong-to-this-run')
            time.sleep(0.6)
        raise SmokeFailure('job-timeout')

    def profile(self, target, knowledge_status):
        before = self.state()
        pid = 'self' if target == 'self' else self.person(before)['id']
        job = self.command('analyze', id=pid)
        after = self.wait(job['id'], before['revision'])
        self.require(after['job']['status'] == 'done', 'profile-done')
        profile = (after['self'] if pid == 'self' else self.person(after))['profile']
        self.require(profile['runId'] == job['id'], 'saved-run-matches')
        self.require(profile['basedOnRevision'] == before['revision'], 'profile-input-revision')
        self.require(after['revision'] == before['revision'] + 1, 'single-save-revision')
        self.require(profile['knowledgeStatus'] == knowledge_status, 'knowledge-status-' + knowledge_status)
        self.require(profile.get('facets') and profile.get('summary'), 'nonempty-profile')
        own = {m['id'] for m in self.person(after)['materials'] if m['speaker'] == ('me' if pid == 'self' else 'them')}
        if pid == 'self':
            own.update(m['id'] for m in after['self']['materials'] if m['speaker'] == 'me')
            if any(after['self'].get(key, '').strip() for key in ('about', 'style', 'boundaries')):
                own.add('self-description')
        elif self.person(after).get('notes', '').strip():
            own.add('person-notes')
        known = {piece['id'] for piece in profile.get('knowledge', [])}
        for facet in profile['facets']:
            evidence = set(facet.get('evidenceIds', []))
            self.require(bool(evidence) and evidence <= own, 'subject-citations')
            self.require(set(facet.get('counterEvidenceIds', [])) <= own, 'counter-citations')
            self.require(set(facet.get('knowledgeIds', [])) <= known, 'knowledge-citations')
        for piece in profile.get('knowledge', []):
            self.require(piece['text'] in FIXTURE['book']['text'] and piece['title'] == FIXTURE['book']['title'], 'actual-fictional-book-text')
        events = after['job'].get('events', [])
        # Events do not include step; ordering proves a later real pre-step callback.
        phases = [event.get('phase') for event in events]
        self.require('knowledge' in phases and 'reasoning' in phases[phases.index('knowledge') + 1:], 'tool-observation-followed-by-model-step')
        self.require(2 <= after['job']['step'] <= 6 and len(events) <= 32, 'bounded-real-progress')
        if knowledge_status == 'empty_library':
            self.require(not profile.get('knowledge') and bool(profile['uncertainties']), 'empty-library-limitation')
        return {'target': target, 'status': 'done', 'step': after['job']['step'],
                'facets': len(profile['facets']), 'bookReferences': len(known)}

    def strategy(self):
        state = self.state()
        person = self.person(state)
        profile = copy.deepcopy(person['profile'])
        changed = self.command('person-update', id=person['id'], goal=FIXTURE['changedGoal'])
        self.require(self.person(changed)['profile'] == profile, 'goal-preserves-profile')
        before = self.state()
        job = self.command('strategy', id=person['id'], situation=FIXTURE['strategySituation'])
        after = self.wait(job['id'], before['revision'], 250)
        self.require(after['job']['status'] == 'done', 'strategy-done')
        strategy = self.person(after)['strategies'][-1]
        own = {m['id'] for m in person['materials'] if m['speaker'] == 'them'}
        self.require(bool(own & set(strategy['evidenceIds'])), 'strategy-target-evidence')
        self.require(bool(strategy['steps']) and bool(strategy['overview']), 'strategy-output')

    def cancel(self):
        before = self.state()
        person = self.person(before)
        job = self.command('analyze', id=person['id'])
        wrong = self.command('cancel', runId='wrong-fictional-run-id')
        self.require(wrong['job']['status'] == 'running', 'wrong-run-cancel-ignored')
        self.command('cancel', runId=job['id'])
        self.command('cancel', runId=job['id'])
        after = self.wait(job['id'], before['revision'])
        self.require(after['job']['status'] == 'cancelled', 'cancelled')
        self.require(after['revision'] == before['revision'] and self.person(after)['profile'] == person['profile'], 'cancel-prevents-save')

    def reopen(self):
        before = self.state()
        other = Probe()
        after = other.state()
        self.require(after['self'].get('profile') == before['self'].get('profile')
                     and self.person(after).get('profile') == self.person(before).get('profile'), 'new-browser-session-keeps-profiles')

    def delete_book(self):
        before = self.state()
        self.require(len(before['library']) == 1, 'one-fictional-book')
        book_id = before['library'][0]['id']
        targets = [before['self'], self.person(before)]
        old_refs = sum(piece.get('documentId') == book_id for target in targets
                       if target.get('profile') for piece in target['profile'].get('knowledge', []))
        history_refs = sum(piece.get('documentId') == book_id for target in targets
                           for profile in target.get('analyses', []) for piece in profile.get('knowledge', []))
        after = self.command('knowledge-delete', id=book_id)
        self.require(not after['library'], 'book-removed')
        for target in [after['self'], self.person(after)]:
            for profile in [target.get('profile'), *target.get('analyses', [])]:
                if profile:
                    self.require(all(piece['documentId'] != book_id for piece in profile.get('knowledge', [])), 'no-deleted-book-references-remain')
            for strategy in target.get('strategies', []):
                self.require(strategy.get('stale'), 'strategy-marked-stale')
        if old_refs > 0 and history_refs > 0:
            self.checks.append('deleted-book-current-history-revoked')
        return {'previousBookReferences': old_refs, 'previousHistoryReferences': history_refs,
                'currentRevocation': 'covered' if old_refs > 0 else 'not_covered',
                'historyRevocation': 'covered' if history_refs > 0 else 'not_covered'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--confirm-isolated', action='store_true', required=True)
    parser.add_argument('--phase', choices=['setup', 'self-empty', 'upload', 'profiles', 'strategy', 'cancel', 'reopen', 'delete-book'], required=True)
    args = parser.parse_args()
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT.parent / 'tl', text=True).strip()
    if actual != args.expected_sha:
        parser.error('Integration SHA does not match TL authorization.')
    probe = Probe()
    result = None
    if args.phase == 'self-empty':
        result = probe.profile('self', 'empty_library')
    elif args.phase == 'profiles':
        result = [probe.profile('person', 'used'), probe.profile('self', 'used')]
    else:
        result = getattr(probe, args.phase.replace('-', '_'))()
    print(json.dumps({'phase': args.phase, 'passedChecks': sorted(set(probe.checks)), 'result': result}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (SmokeFailure, OSError, KeyError, ValueError) as error:
        # Never emit raw transport/model contents or configuration exceptions.
        label = str(error) if isinstance(error, SmokeFailure) else type(error).__name__
        print(json.dumps({'smoke': 'FAIL', 'check': label}))
        raise SystemExit(1)
