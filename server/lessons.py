"""Turn findings that keep showing up into **lessons** and feed them back to
the tuner.

An analysis finding is an observation about one snapshot of the data.  A
*lesson* is the conclusion drawn from an observation that has persisted across
several analyses: not just "this pattern is true right now", but "this pattern
keeps being true as new attempts arrive".

Lifecycle
---------
1.  ``LessonManager.ingest(findings)`` runs inside every analyze step.
2.  A finding whose ``rule`` is not ``None`` and whose strength clears the
    promotion threshold is counted.  The same stable finding id counted in
    ``LESSON_STREAK`` consecutive analyses becomes a lesson.
3.  A lesson whose underlying finding falls below the retention threshold in
    ``RETIRE_STREAK`` consecutive analyses is retired.
4.  The user can dismiss a lesson by hand; dismissed lessons stay out of the
    active set.
5.  The tuner calls :meth:`LessonManager.bonus_for` when it scores arms, so
    active lessons push the suggestion without forcing it.  Exploration is
    preserved; the bonus is proportional to confidence.

Every lesson keeps a link to the finding that justified it, so the Lessons
page shows the *why* and not only the *what*.
"""

import json
import math
import time


# Promotion: a rule needs to clear this strength and be seen this many times.
LESSON_STRENGTH = 3.5
LESSON_STREAK = 3

# Retention: drop lessons whose underlying finding has faded for this long.
RETIRE_STRENGTH = 2.0
RETIRE_STREAK = 2

# Maximum UCB bonus a single lesson can add when the tuner evaluates arms,
# in matched edges. A couple of edges of simulated lift is already meaningful
# next to typical effect sizes; we scale by the lesson's confidence.
BONUS_SCALE = 2.5


class LessonManager:
    def __init__(self, db):
        self.db = db

    # ------------------------------------------------------------- ingest

    def ingest(self, findings):
        """Look at a freshly produced set of findings and update the lesson DB.

        Returns a dict summarising what changed (used for the analyze
        response).
        """
        now = time.time()
        # Two different findings can endorse the same rule (e.g. the main
        # setting effect AND the restart analysis both saying "prefer
        # restartPolicy=none"). Keep the one with the strongest evidence so
        # a single lesson is promoted once rather than twice.
        finding_map = {}
        for f in findings:
            if not f.get('rule'):
                continue
            key = _rule_key(f['rule'])
            if key is None:
                continue
            existing = finding_map.get(key)
            if existing is None or existing['strength'] < f['strength']:
                finding_map[key] = f
        current = {row['id']: row for row in self.db.all_lessons()}

        added, retired_now, still_active = [], [], []

        # 1. bump streaks for findings strong enough to be lesson candidates.
        # finding_map is keyed by the already-computed rule_key.
        for lesson_id, finding in finding_map.items():
            if finding['strength'] < LESSON_STRENGTH:
                continue
            rule = finding['rule']
            current_lesson = current.get(lesson_id)
            if current_lesson is None:
                # first time we see this rule: start watching it
                self.db.upsert_lesson({
                    'id': lesson_id,
                    'title': _title_for(rule, finding),
                    'paragraph': _paragraph_for(rule, finding),
                    'rule': rule,
                    'status': 'watching',
                    'streak': 1,
                    'misses': 0,
                    'strength': finding['strength'],
                    'support': finding['support'],
                    'based_on': finding['id'],
                    'updated_at': now,
                    'accepted_at': None,
                    'retired_at': None,
                    'dismissed': 0,
                })
                continue
            # already on file: increment streak and possibly promote
            new_streak = int(current_lesson.get('streak') or 0) + 1
            new_status = current_lesson.get('status') or 'watching'
            accepted_at = current_lesson.get('accepted_at')
            was_active = (new_status == 'active')
            if (not was_active
                    and not current_lesson.get('dismissed')
                    and new_streak >= LESSON_STREAK):
                new_status = 'active'
                accepted_at = now
                added.append(lesson_id)
            self.db.upsert_lesson({
                'id': lesson_id,
                'title': _title_for(rule, finding),
                'paragraph': _paragraph_for(rule, finding),
                'rule': rule,
                'status': new_status,
                'streak': new_streak,
                'misses': 0,
                'strength': finding['strength'],
                'support': finding['support'],
                'based_on': finding['id'],
                'updated_at': now,
                'accepted_at': accepted_at,
                'retired_at': current_lesson.get('retired_at'),
                'dismissed': current_lesson.get('dismissed') or 0,
            })
            if new_status == 'active':
                still_active.append(lesson_id)

        # 2. lessons whose finding did NOT appear strong enough this time
        reappeared = set(
            _rule_key(f['rule']) for f in findings
            if f.get('rule') and f['strength'] >= RETIRE_STRENGTH
        )
        reappeared.discard(None)
        for lesson_id, lesson in current.items():
            if lesson_id in reappeared:
                continue
            misses = int(lesson.get('misses') or 0) + 1
            status = lesson.get('status') or 'watching'
            retired_at = lesson.get('retired_at')
            if status == 'active' and misses >= RETIRE_STREAK:
                status = 'retired'
                retired_at = now
                retired_now.append(lesson_id)
            elif status == 'watching' and misses >= RETIRE_STREAK:
                # drop entirely: watching lessons that fade were never promised
                self.db.delete_lesson(lesson_id)
                continue
            self.db.update_lesson_streak(
                lesson_id, misses=misses, status=status,
                retired_at=retired_at, updated_at=now)

        active_now = sum(
            1 for row in self.db.all_lessons()
            if row.get('status') == 'active' and not row.get('dismissed'))
        return {
            'added': added,
            'retired': retired_now,
            'activeCount': active_now,
        }

    # ---------------------------------------------------- tuner integration

    def active_rules(self):
        """Return a list of {setting, value, confidence} for active lessons."""
        out = []
        for row in self.db.all_lessons():
            if row.get('status') != 'active' or row.get('dismissed'):
                continue
            rule = row.get('rule')
            if not rule or rule.get('kind') != 'prefer_value':
                continue
            out.append({
                'setting': rule['setting'],
                'value': rule['value'],
                'confidence': float(rule.get('confidence') or 0.5),
                'lessonId': row['id'],
            })
        return out

    def bonus_for(self, setting_key, arm_value):
        """UCB score bonus the tuner should add if this arm matches a lesson."""
        bonus = 0.0
        for rule in self.active_rules():
            if rule['setting'] != setting_key:
                continue
            if _values_equal(rule['value'], arm_value):
                bonus = max(bonus, BONUS_SCALE * rule['confidence'])
        return bonus

    # ------------------------------------------------------------- admin

    def dismiss(self, lesson_id):
        """User chose to ignore this lesson."""
        self.db.update_lesson_streak(
            lesson_id, dismissed=1, status='retired',
            retired_at=time.time(), updated_at=time.time())

    def restore(self, lesson_id):
        """Undo a dismissal."""
        self.db.update_lesson_streak(
            lesson_id, dismissed=0, status='watching',
            misses=0, updated_at=time.time())

    def listing(self):
        """Shape the stored lessons for the Lessons page."""
        out = []
        for row in self.db.all_lessons():
            out.append({
                'id': row['id'],
                'title': row['title'],
                'paragraph': row['paragraph'],
                'rule': row.get('rule'),
                'status': row.get('status'),
                'dismissed': bool(row.get('dismissed')),
                'streak': row.get('streak'),
                'misses': row.get('misses'),
                'strength': row.get('strength'),
                'support': row.get('support'),
                'basedOn': row.get('based_on'),
                'acceptedAt': row.get('accepted_at'),
                'retiredAt': row.get('retired_at'),
                'updatedAt': row.get('updated_at'),
            })
        # active first, then watching, then retired
        order = {'active': 0, 'watching': 1, 'retired': 2}
        out.sort(key=lambda r: (order.get(r['status'], 3),
                                -(r['strength'] or 0)))
        return out


# --------------------------------------------------------------- helpers

def _rule_key(rule):
    """Stable id for a rule (used as the lesson id)."""
    if not rule:
        return None
    kind = rule.get('kind')
    if kind == 'prefer_value':
        return 'prefer_%s_%s' % (rule.get('setting'),
                                 _value_tag(rule.get('value')))
    return None


def _value_tag(value):
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return str(value).replace('/', '_').replace(' ', '_')


def _values_equal(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a) == bool(b)
    return str(a) == str(b)


def _title_for(rule, finding):
    if rule.get('kind') == 'prefer_value':
        # use the finding's own label if available, else fall back
        parts = finding['title'].split('"')
        if len(parts) >= 2:
            return 'Prefer %s' % parts[1]
    return 'Lesson: ' + finding['title']


def _paragraph_for(rule, finding):
    suffix = (
        ' The tuner will gently bias its suggestions toward this choice '
        'while keeping enough exploration to notice if the pattern stops '
        'holding.')
    return finding['paragraph'] + suffix
