"""Reference fixture reducer for client implementers; backend state remains authoritative."""
from copy import deepcopy
class PlanProjection:
    def __init__(self,snapshot):self.replace(snapshot)
    def replace(self,snapshot):
        self.instance=snapshot['serverInstanceId'];self.sequence=snapshot['latestSequence'];self.seen=set();self.plans={p['taskId']:deepcopy(p) for p in snapshot.get('plans',[])}
    def apply(self,event):
        if event['sequence']==0:return 'CONTROL'
        if event['eventId'] in self.seen:return 'DUPLICATE'
        if event['sequence']<=self.sequence:return 'STALE'
        if event['sequence']!=self.sequence+1:return 'GAP'
        if event['type'].startswith('task.plan.'):
            plan=event['payload']['plan'];existing=self.plans.get(plan['taskId'])
            if existing is None or plan['revision']>existing['revision']:self.plans[plan['taskId']]=deepcopy(plan)
        elif event['type']=='task.history.deleted':self.plans.pop(event['taskId'],None)
        self.sequence=event['sequence'];self.seen.add(event['eventId']);return 'APPLIED'
