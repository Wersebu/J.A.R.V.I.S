import json,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]))
from companion_plan_reference import PlanProjection
class PlanFixtureTest(unittest.TestCase):
    def setUp(self):self.fixture=json.loads((Path(__file__).parents[2]/'docs/companion/fixtures/plan-replay.json').read_text());self.projection=PlanProjection(self.fixture['snapshot'])
    def test_duplicate_and_stale(self):
        self.assertEqual(['APPLIED','DUPLICATE','STALE'],[self.projection.apply(e) for e in self.fixture['duplicateAndStale']]);self.assertEqual(2,next(iter(self.projection.plans.values()))['revision'])
    def test_gap_requires_atomic_snapshot(self):
        self.assertEqual('GAP',self.projection.apply(self.fixture['outOfOrder'][0]));self.projection.replace(self.fixture['authoritativeResync']);self.assertEqual('STALE',self.projection.apply(self.fixture['outOfOrder'][1]));self.assertEqual(3,next(iter(self.projection.plans.values()))['revision'])
if __name__=='__main__':unittest.main()
