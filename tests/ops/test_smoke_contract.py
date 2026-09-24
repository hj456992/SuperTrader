"""Regression checks for the smoke probe's false-positive/negative reporting."""
import copy
import unittest

from profile_http_smoke import FIXTURE, Probe, SmokeFailure


class SmokeContractTest(unittest.TestCase):
    def profile_probe(self, target):
        person = {'id': 'person-1', 'name': FIXTURE['person']['name'], 'materials': [],
                  'notes': 'A fictional user report', 'profile': None}
        before = {'revision': 4, 'self': {'about': 'A fictional self-description', 'materials': []},
                  'people': [person]}
        after = copy.deepcopy(before)
        after['revision'] = 5
        after['job'] = {'status': 'done', 'step': 2, 'events': [{'phase': 'knowledge'}, {'phase': 'reasoning'}]}
        node = after['self'] if target == 'self' else after['people'][0]
        node['profile'] = {'runId': 'run-1', 'basedOnRevision': 4, 'knowledgeStatus': 'empty_library',
                           'summary': 'Fictional summary', 'knowledge': [], 'uncertainties': ['Limited'],
                           'facets': [{'kind': 'inferred', 'evidenceIds': ['self-description' if target == 'self' else 'person-notes']}]}
        probe = object.__new__(Probe)
        probe.checks = []
        probe.state = lambda: before
        probe.command = lambda *args, **kwargs: {'id': 'run-1'}
        probe.wait = lambda *args: after
        return probe

    def test_valid_self_description_is_not_rejected(self):
        self.assertEqual(self.profile_probe('self').profile('self', 'empty_library')['status'], 'done')

    def test_valid_person_report_is_not_rejected(self):
        self.assertEqual(self.profile_probe('person').profile('person', 'empty_library')['status'], 'done')

    def test_own_saved_profile_can_precede_done_job_publication(self):
        saving = {'revision': 17, 'job': {'id': 'run-1', 'status': 'running', 'phase': 'saving',
                  'kind': 'profile', 'targetId': 'person-1'},
                  'people': [{'id': 'person-1', 'profile': {'runId': 'run-1', 'basedOnRevision': 16}}]}
        done = copy.deepcopy(saving)
        done['job']['status'] = 'done'
        probe = object.__new__(Probe)
        probe.checks = []
        states = iter([saving, done])
        probe.state = lambda: next(states)
        self.assertEqual(probe.wait('run-1', 16)['job']['status'], 'done')

    def test_unrelated_saved_profile_does_not_allow_revision_change(self):
        state = {'revision': 17, 'job': {'id': 'run-1', 'status': 'running', 'phase': 'saving',
                 'kind': 'profile', 'targetId': 'person-1'},
                 'people': [{'id': 'person-1', 'profile': {'runId': 'another-run', 'basedOnRevision': 16}}]}
        probe = object.__new__(Probe)
        probe.checks = []
        probe.state = lambda: state
        with self.assertRaises(SmokeFailure):
            probe.wait('run-1', 16)

    def test_book_removal_without_old_references_is_not_coverage(self):
        state = {'library': [{'id': 'book-1'}], 'self': {'profile': None, 'analyses': []},
                 'people': [{'id': 'person-1', 'name': FIXTURE['person']['name'], 'profile': None, 'analyses': []}]}
        after = copy.deepcopy(state)
        after['library'] = []
        probe = object.__new__(Probe)
        probe.checks = []
        probe.state = lambda: state
        probe.command = lambda *args, **kwargs: after
        result = probe.delete_book()
        self.assertEqual(result.get('currentRevocation'), 'not_covered')
        self.assertEqual(result.get('historyRevocation'), 'not_covered')
        self.assertNotIn('deleted-book-current-history-revoked', probe.checks)


if __name__ == '__main__':
    unittest.main()
