import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sparse_mesh import Config, simulate  # noqa: E402


def fixed(points):
    """Positions that never move: phone i stays at points[i]."""
    return lambda t, i: points[i]


class SparseMeshTest(unittest.TestCase):
    def base(self, **kw):
        defaults = dict(n=2, groups=1, hours=0.5, p_drop=0.0, discovery_s=0, p_internet=0.0, p_lost=0.0, seed=1)
        defaults.update(kw)
        return Config(**defaults)

    def test_two_phones_in_range_always_reachable(self):
        r = simulate(self.base(), positions=fixed([(10, 10), (10, 10)]))
        self.assertEqual(r["pair_reachable_share"]["0-1"], 1.0)

    def test_two_phones_out_of_range_never_reachable(self):
        r = simulate(self.base(), positions=fixed([(0, 0), (100, 0)]))
        self.assertEqual(r["pair_reachable_share"]["0-1"], 0.0)

    def test_relay_makes_a_to_c_reachable_via_b(self):
        r = simulate(self.base(n=3), positions=fixed([(0, 0), (15, 0), (30, 0)]))
        self.assertEqual(r["pair_reachable_share"]["0-2"], 1.0)

    def test_hops_beyond_ttl_are_not_reachable(self):
        chain = [(i * 15, 0) for i in range(4)]
        r = simulate(self.base(n=4, ttl=2), positions=fixed(chain))
        self.assertEqual(r["pair_reachable_share"]["0-2"], 1.0)
        self.assertEqual(r["pair_reachable_share"]["0-3"], 0.0)

    def test_store_and_forward_delivers_after_reunion(self):
        def pos(t, i):
            if i == 0:
                return (0, 0)
            return (200, 0) if t < 600 else (5, 0)

        r = simulate(self.base(), positions=pos, help_events=[(0, 0)])
        self.assertEqual(r["help"]["count"], 1)
        delay = r["help"]["delays_s"][0]
        self.assertGreaterEqual(delay, 600)
        self.assertLessEqual(delay, 610)
        self.assertEqual(r["help"]["within_120s"], 0.0)
        self.assertEqual(r["help"]["within_600s"], 0.0)

    def test_internet_bridges_distant_phones(self):
        r = simulate(self.base(p_internet=1.0), positions=fixed([(0, 0), (250, 150)]))
        self.assertEqual(r["pair_reachable_share"]["0-1"], 1.0)

    def test_location_age_is_small_when_always_connected(self):
        r = simulate(self.base(), positions=fixed([(0, 0), (5, 0)]))
        self.assertLessEqual(r["location_age_s"]["p90"], 120)

    def test_same_seed_same_result(self):
        cfg = Config(n=6, hours=1, seed=7)
        self.assertEqual(simulate(cfg), simulate(cfg))

    def test_different_seed_changes_result(self):
        self.assertNotEqual(simulate(Config(n=6, hours=1, seed=7)), simulate(Config(n=6, hours=1, seed=8)))


if __name__ == "__main__":
    unittest.main()
