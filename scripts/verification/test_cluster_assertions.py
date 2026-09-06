import unittest
from cluster_assertions import session_identity, scheduler_cycles

class ClusterAssertionsTest(unittest.TestCase):
    def test_redirect_and_login_are_not_authenticated(self):
        page = '<main x-data="onboardingWizard(done)"><span>reader@example.test</span></main>'
        self.assertTrue(session_identity(200, page, 'reader@example.test'))
        self.assertFalse(session_identity(302, page, 'reader@example.test'))
        self.assertFalse(session_identity(200, '<input id="login-email">', 'reader@example.test'))
        self.assertFalse(session_identity(200, page, 'other@example.test'))

    def line(self, second):
        return f'2026-09-06 12:{second // 60:02d}:{second % 60:02d}.010 INFO [Monitor] Continuous monitoring cycle START'

    def test_ownership_may_change_between_observed_cycles(self):
        result = scheduler_cycles({'A': self.line(0), 'B': self.line(80)})
        self.assertEqual(len(result), 2)

    def test_duplicates_within_cycle_fail_even_on_same_owner(self):
        for logs in ({'A': self.line(0), 'B': self.line(1)+'\n'+self.line(80)},
                     {'A': self.line(0)+'\n'+self.line(1)+'\n'+self.line(80)}):
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                scheduler_cycles(logs)

    def test_no_activity_or_unparseable_evidence_fails(self):
        with self.assertRaisesRegex(ValueError, 'Only'):
            scheduler_cycles({'A': '', 'B': ''})
        with self.assertRaisesRegex(ValueError, 'timestamp'):
            scheduler_cycles({'A': '[Monitor] Continuous monitoring cycle START'})

if __name__ == '__main__':
    unittest.main()
