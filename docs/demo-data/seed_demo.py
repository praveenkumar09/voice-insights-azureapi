#!/usr/bin/env python3
"""
Generates realistic DEMO data for the Voice Insights admin dashboard as plain SQL.

Everything it creates is tagged so it can be found and removed in one go:
  customer_profiles.id      = 'demo-p-NNNN'
  recommendation_runs.run_id = 'demo-r-NNNN'
  users.id                  = 'demo-u-N'   (email ends @demo.aia.sg; the password hash cannot match any password)
cleanup.sql removes exactly those rows.

Deterministic (fixed seed) so a re-run produces the same data.
"""
import json
import math
import random
import sys
from datetime import datetime, timedelta, timezone

OUT = sys.argv[1] if len(sys.argv) > 1 else 'seed.sql'
HERE = __file__.rsplit('/', 1)[0]
rnd = random.Random(20261003)
SG = timezone(timedelta(hours=8))
NOW = datetime.now(SG)
S = 'voice_insights'

N_CONV = 172
DAYS = 75

# ── Reference material taken from a real run (product-document excerpts) ─────────────────────────────────
REF = json.load(open(f'{HERE}/ref_ragValidation.json'))
CITES = {}
for c in REF['citations']:
    CITES.setdefault(c['productName'], []).append(c)
SG_PROD, PA_PROD = 'AIA Secure Guard Term', 'AIA Pro Achiever Invest'

# ── Advisors ────────────────────────────────────────────────────────────────────────────────────────────────
ADVISORS = [  # id, name, share, buying bias, flag multiplier
    ('demo-u-1', 'Sarah Tan', 0.25, 8, 0.6),
    ('demo-u-2', 'Daniel Lim', 0.21, 3, 0.7),
    ('demo-u-3', 'Aisha Rahman', 0.18, 6, 0.5),
    ('demo-u-4', 'Marcus Wong', 0.14, -2, 1.4),
    ('demo-u-5', 'Kevin Ong', 0.12, -7, 2.4),
    ('demo-u-6', 'Jessica Goh', 0.10, 1, 0.8),
]
for a in ADVISORS:
    pass


def email(name):
    return name.lower().replace(' ', '.') + '@demo.aia.sg'


# ── People ──────────────────────────────────────────────────────────────────────────────────────────────────
SURN = ['Tan', 'Lim', 'Lee', 'Ng', 'Wong', 'Goh', 'Chua', 'Teo', 'Ong', 'Koh', 'Chen', 'Liu', 'Yeo', 'Sim', 'Nair', 'Pillai', 'Kumar', 'Singh',
        'Raj', 'Menon', 'Krishnan', 'Rahman', 'Hassan', 'Ismail', 'Abdullah', 'Yusof', 'Salleh', 'Fernandez', 'De Souza', 'Chong']
M_FIRST = ['Wei Ming', 'Kok Wai', 'Jun Jie', 'Ravi', 'Arjun', 'Faizal', 'Daniel', 'Ivan', 'Haziq', 'Benjamin', 'Samuel', 'Vikram', 'Zhi Hao', 'Marcus',
           'Ryan', 'Kenneth', 'Aaron', 'Suresh', 'Irfan', 'Jonathan', 'Eugene', 'Darren', 'Nathan', 'Syafiq']
F_FIRST = ['Mei Ling', 'Jia Hui', 'Priya', 'Aisha', 'Siti', 'Hui Min', 'Sarah', 'Kavitha', 'Xin Yi', 'Rachel', 'Nurul', 'Shu Fen', 'Deepa', 'Amanda',
           'Michelle', 'Wen Ting', 'Farah', 'Lakshmi', 'Joanne', 'Grace', 'Natalie', 'Sophia', 'Ying Ying', 'Anita']
KID_M = ['Ethan', 'Noah', 'Aiden', 'Lucas', 'Ryan', 'Isaac', 'Caleb', 'Adam', 'Ian', 'Zayd', 'Arjun', 'Jayden']
KID_F = ['Chloe', 'Emma', 'Mia', 'Olivia', 'Ava', 'Zara', 'Anya', 'Sophie', 'Maya', 'Nur', 'Isabelle', 'Hannah']

NEEDS = ['Family protection', 'Critical illness', 'Education savings', 'Retirement', 'Income protection', 'Legacy planning', 'Medical', 'Wealth accumulation']

# ── Archetypes ──────────────────────────────────────────────────────────────────────────────────────────────
ARCH = [
    dict(key='young_family', w=0.26, persona=('Growing Family Protector', 'Growing Family'), age=(30, 40), spouse=True, kids=(1, 3),
         occ=['civil engineer', 'school teacher', 'marketing manager', 'software engineer', 'nurse', 'accountant', 'project manager', 'pharmacist'],
         income=(70, 170), budget=(350, 800), home=(500, 1100), risk='High', top_sg=0.62,
         needs=['Family protection', 'Education savings', 'Critical illness', 'Income protection'],
         worries=[('Mortgage payments', 'our mortgage worries me, and if something happened to me my family would struggle', 'Spouse'),
                  ('Family income if ill', 'I worry about what happens to the family if I cannot work', 'Spouse')],
         dreams=[("Children's university", "I want to put money aside for the children's university, ideally overseas", 'Child')],
         existing='the hospital plan from my company only', next_q=['Whether the spouse works', 'Existing life or critical illness cover', 'Target university budget'],
         objection='I am not sure I need this much cover while the children are young'),
    dict(key='business_owner', w=0.17, persona=('Established Business Protector', 'Established Family'), age=(36, 52), spouse=True, kids=(0, 3),
         occ=['small business owner', 'restaurant owner', 'logistics company owner', 'freelance designer', 'clinic owner', 'contractor', 'retail shop owner'],
         income=(90, 260), budget=(450, 1200), home=(600, 1500), risk='High', top_sg=0.7,
         needs=['Income protection', 'Family protection', 'Critical illness', 'Legacy planning'],
         worries=[('Business continuity', 'if something happened to me the business would collapse', 'Self'),
                  ('Irregular income', 'my income goes up and down with the business', 'Self')],
         dreams=[('Secure the family home', 'I want the family home to be safe whatever happens', 'Spouse')],
         existing='a small term policy through the bank', next_q=['Key-person arrangements', 'Business loans outstanding', 'Income over the last three years'],
         objection='Cash flow is tight, can I start smaller and increase later'),
    dict(key='pre_retiree', w=0.15, persona=('Pre-Retirement Planner', 'Pre-Retirement'), age=(50, 62), spouse=True, kids=(1, 3),
         occ=['senior manager', 'civil servant', 'bank vice president', 'teacher', 'engineer', 'consultant', 'sales director'],
         income=(110, 240), budget=(600, 1800), home=(0, 400), risk='Moderate', top_sg=0.22,
         needs=['Retirement', 'Wealth accumulation', 'Legacy planning', 'Medical'],
         worries=[('Running out of money', 'I worry our savings will not last through retirement', 'Spouse'),
                  ('Medical costs in old age', 'medical bills in our later years concern me', 'Self')],
         dreams=[('Retire at 62', 'we would like to retire by sixty two and travel', 'Spouse')],
         existing='a hospital plan and some CPF savings', next_q=['CPF LIFE plans', 'Existing retirement income', 'Legacy wishes for the children'],
         objection='Investment-linked plans feel risky this close to retirement'),
    dict(key='young_pro', w=0.17, persona=('Young Professional Builder', 'Early Career'), age=(25, 32), spouse=False, kids=(0, 0),
         occ=['software developer', 'consultant', 'junior doctor', 'marketing executive', 'analyst', 'designer', 'lawyer'],
         income=(55, 120), budget=(200, 550), home=(0, 0), risk='Moderate', top_sg=0.4,
         needs=['Critical illness', 'Wealth accumulation', 'Medical', 'Income protection'],
         worries=[('Getting seriously ill early', 'a friend my age was diagnosed with cancer and it shook me', 'Self'),
                  ('Starting savings late', 'I feel I have not started saving seriously yet', 'Self')],
         dreams=[('Buy a first home', 'I would like to buy a flat in the next few years', 'Self')],
         existing='only the hospital plan from my employer', next_q=['Home purchase timeline', 'Savings rate so far', 'Family medical history'],
         objection='I am young and healthy, maybe I can wait a couple of years'),
    dict(key='new_parent', w=0.14, persona=('New Parent Protector', 'New Family'), age=(27, 36), spouse=True, kids=(1, 2),
         occ=['nurse', 'architect', 'sales manager', 'teacher', 'freelance photographer', 'engineer', 'operations manager'],
         income=(60, 140), budget=(300, 650), home=(400, 900), risk='High', top_sg=0.68,
         needs=['Family protection', 'Education savings', 'Medical', 'Income protection'],
         worries=[('Providing for the baby', 'we have a new baby and I want to know she is provided for', 'Child'),
                  ('Losing an income', 'with one income stopping we would not manage the mortgage', 'Spouse')],
         dreams=[("Baby's education", 'we want her education funded from the start', 'Child')],
         existing='a hospital plan through my employer, no life cover', next_q=['Maternity and medical coverage', 'Spouse income stability', 'Childcare costs'],
         objection='We just had a baby and spending is already stretched'),
    dict(key='sandwich', w=0.11, persona=('Sandwich-Generation Carer', 'Mid-Life'), age=(40, 52), spouse=True, kids=(1, 2),
         occ=['operations director', 'principal', 'hospital administrator', 'accountant', 'IT manager', 'civil servant'],
         income=(100, 210), budget=(500, 1100), home=(200, 800), risk='High', top_sg=0.5,
         needs=['Medical', 'Family protection', 'Retirement', 'Critical illness'],
         worries=[("Parents' medical bills", "my parents' medical bills are becoming a real burden", 'Parent'),
                  ('Supporting two generations', 'I support my parents and my own children at the same time', 'Self')],
         dreams=[("Children's education", 'I still want to fund the children through university', 'Child')],
         existing='a hospital plan and a small whole-life policy', next_q=["Parents' existing cover", 'Caregiving costs', 'Retirement timeline'],
         objection='I am already stretched supporting my parents'),
]

FLAGS = [
    ('high', 'This plan is guaranteed to give you 8% returns every year.', 'Do not promise guaranteed returns; explain risks and variability.'),
    ('high', 'You will definitely be approved, there is no underwriting to worry about.', 'Never imply guaranteed acceptance; underwriting decides.'),
    ('medium', 'You should really cancel your existing policy and switch to this.', 'Recommending a replacement needs a documented comparison of both policies.'),
    ('medium', 'This is a limited offer, you need to decide today.', 'Avoid pressure; give the customer time to decide.'),
    ('medium', 'It is basically the same as a savings account but better.', 'Do not compare an investment-linked plan to a savings account without explaining risk.'),
]

SUMMARY_TIPS = {
    'Family protection': 'Provides financial protection for the family and helps cover the home loan if something unexpected happens.',
    'Education savings': "Helps build savings for the children's education alongside life and critical illness cover.",
    'Critical illness': 'Critical illness cover means the family is also supported if a serious illness is diagnosed.',
    'Income protection': 'Replaces part of the income so everyday commitments continue if earnings stop.',
    'Retirement': 'Builds long-term wealth with protection, supporting plans for retirement.',
    'Legacy planning': 'Provides a lump sum for dependents and helps pass wealth on in a planned way.',
    'Medical': 'Complements the hospital plan so large medical bills do not erode savings.',
    'Wealth accumulation': 'Long-term investing inside a protection plan, with flexible fund choices.',
}


# ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────
def dq(s, tag='q'):
    s = str(s)
    assert f'${tag}$' not in s
    return f'${tag}${s}${tag}$'


def js(o):
    return dq(json.dumps(o, ensure_ascii=False), 'j')


def ts(d):
    return "'" + d.isoformat() + "'::timestamptz"


def pick(seq):
    return seq[rnd.randrange(len(seq))]


def wpick(items, weights):
    return rnd.choices(items, weights=weights, k=1)[0]


def words_to_num(n):
    return f'{n:,}'


# ── When conversations happen ───────────────────────────────────────────────────────────────────────────────
def sample_time(mode):
    wts = []
    for d in range(DAYS):
        day = (NOW - timedelta(days=d)).date()
        wd = day.weekday()
        base = 1.0 + 1.5 * (1 - d / DAYS)  # more activity lately: the product is catching on
        base *= {5: 0.35, 6: 0.15}.get(wd, 1.0)
        wts.append(base)
    d = wpick(range(DAYS), wts)
    day = (NOW - timedelta(days=d)).date()
    if mode == 'LIVE':
        hour = wpick([9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20], [2, 6, 9, 4, 3, 7, 9, 8, 6, 5, 7, 3])
    else:
        hour = wpick([8, 17, 18, 19, 20, 21, 22], [2, 4, 8, 9, 9, 6, 2])
    t = datetime(day.year, day.month, day.day, hour, rnd.randrange(60), rnd.randrange(60), tzinfo=SG)
    if t > NOW - timedelta(minutes=45):
        t = NOW - timedelta(minutes=rnd.randrange(60, 600))
    return t


# ── One conversation ────────────────────────────────────────────────────────────────────────────────────────
def build_person(arch):
    male = rnd.random() < 0.5
    first = pick(M_FIRST if male else F_FIRST)
    surn = pick(SURN)
    spouse = pick(F_FIRST if male else M_FIRST) if arch['spouse'] else None
    nkids = rnd.randint(*arch['kids']) if arch['kids'][1] else 0
    kids = []
    for i in range(nkids):
        km = rnd.random() < 0.5
        kids.append((pick(KID_M if km else KID_F), 'Son' if km else 'Daughter', rnd.randint(1, 15)))
    return dict(first=first, surn=surn, male=male, spouse=spouse, kids=kids,
                age=rnd.randint(*arch['age']), occ=pick(arch['occ']), income=rnd.randint(*arch['income']) * 1000,
                budget=int(round(rnd.randint(*arch['budget']) / 50) * 50),
                home=int(round(rnd.randint(*arch['home']) / 50) * 50000) if arch['home'][1] else 0)


def level_of(score):
    return 'Hot' if score >= 70 else 'Warm' if score >= 35 else 'Cold'


def make_conversation(i, created, mode, advisor, arch, p):
    name = f"{p['first']} {p['surn']}"
    pid = f'demo-p-{i:04d}'
    spouse_rel = 'Wife' if p['male'] else 'Husband'
    # people
    people = []
    if p['spouse']:
        people.append({'relation': spouse_rel, 'name': p['spouse'], 'said': f"{'my wife' if p['male'] else 'my husband'} {p['spouse']}"})
    for (kn, rel, age) in p['kids'][:3]:
        people.append({'relation': rel, 'name': kn, 'said': f"{kn} who is {age}"})
    if arch['key'] == 'sandwich':
        people.append({'relation': 'Parent', 'name': None, 'said': 'my parents'})

    def rel_for(tag):
        if tag == 'Spouse':
            return spouse_rel if p['spouse'] else 'Self'
        if tag == 'Child':
            return p['kids'][0][1] if p['kids'] else 'Self'
        return tag

    # concerns
    dreams, worries = [], []
    prods = [SG_PROD, PA_PROD] if rnd.random() < arch['top_sg'] else [PA_PROD, SG_PROD]
    for (label, said, who) in arch['dreams']:
        idea = {'product': prods[0] if 'ducation' in label or 'Retire' in label else PA_PROD if rnd.random() < 0.5 else SG_PROD,
                'fit': rnd.randint(48, 88), 'evidence': CITES[SG_PROD][0]['excerpt']}
        dreams.append({'label': label, 'forRelation': rel_for(who), 'said': said, 'idea': idea})
    for (label, said, who) in arch['worries']:
        prod = SG_PROD if arch['key'] != 'pre_retiree' else PA_PROD
        worries.append({'label': label, 'forRelation': rel_for(who), 'said': said,
                        'idea': {'product': prod, 'fit': rnd.randint(52, 94), 'evidence': CITES[prod][0]['excerpt']}})
    worries = worries[: rnd.randint(1, len(worries))]

    # signals
    live_mode = mode == 'LIVE'
    base = {'LIVE': (0.28, 0.42), 'DEBRIEF': (0.12, 0.38)}[mode]
    r = rnd.random()
    bias = advisor[3]
    if r < base[0]:
        score = rnd.randint(70, 94)
    elif r < base[0] + base[1]:
        score = rnd.randint(35, 69)
    else:
        score = rnd.randint(6, 34)
    score = max(2, min(97, score + int(bias * 0.6)))
    sr = rnd.random()
    sent = rnd.randint(25, 82) if sr < 0.56 else rnd.randint(-24, 24) if sr < 0.93 else rnd.randint(-68, -26)
    sent_label = 'Positive' if sent >= 25 else 'Negative' if sent <= -25 else 'Neutral'
    emotion = pick({'Positive': ['Hopeful', 'Confident', 'Enthusiastic'], 'Neutral': ['Cautious', 'Thoughtful', 'Curious'], 'Negative': ['Anxious', 'Frustrated', 'Doubtful']}[sent_label])

    # needs
    primary = arch['needs'][:]
    extra = [n for n in NEEDS if n not in primary]
    chosen = primary[: rnd.randint(2, 4)] + rnd.sample(extra, rnd.randint(0, 2))
    needs = sorted([{'label': n, 'strength': rnd.randint(55, 95) if n in primary else rnd.randint(30, 58)} for n in chosen], key=lambda x: -x['strength'])

    # compliance flags (some advisors need more coaching than others)
    flags = []
    if rnd.random() < 0.13 * advisor[4]:
        for _ in range(rnd.choice([1, 1, 1, 2])):
            f = pick(FLAGS)
            flags.append({'severity': f[0], 'statement': f[1], 'advice': f[2]})

    # transcript
    kids_txt = ''
    if p['kids']:
        kids_txt = ' and we have ' + ' and '.join(f"{k[0]} who is {k[2]}" for k in p['kids'][:3])
    spouse_txt = f", {'my wife' if p['male'] else 'my husband'} {p['spouse']}" if p['spouse'] else ''
    if live_mode:
        sents = [f"I'm {name}, I'm {p['age']} and I work as a {p['occ']}.",
                 f"I live with{spouse_txt.replace(', ', ' ', 1) if spouse_txt else ' my family'}{kids_txt}." if p['spouse'] or p['kids'] else 'I live on my own at the moment.']
        sents += [w[1][0].upper() + w[1][1:] + '.' for w in arch['worries'][: len(worries) or 1]]
        sents += [d[1][0].upper() + d[1][1:] + '.' for d in arch['dreams']]
        sents.append(f"Right now I only have {arch['existing']}.")
        sents.append(f"I earn around ${p['income']:,} a year and could set aside about ${p['budget']} a month.")
        if score >= 70:
            sents.append('Can you send me the numbers? I would like to start soon.')
        elif score >= 35:
            sents.append('What does the premium look like for something like this?')
        else:
            sents.append('I am just looking around for now.')
    else:
        g = 'he' if p['male'] else 'she'
        pos = 'his' if p['male'] else 'her'
        sents = [f"I just met {name}. {g.capitalize()} is {p['age']} and works as a {p['occ']}."]
        if p['spouse'] or p['kids']:
            sents.append(f"{g.capitalize()} is married to {p['spouse']}{(' and has ' + ' and '.join(k[0] + ' who is ' + str(k[2]) for k in p['kids'][:3])) if p['kids'] else ''}.")
        sents += [f"{g.capitalize()} said that {w[1]}." for w in arch['worries'][: len(worries) or 1]]
        sents += [f"{g.capitalize()} also said that {d[1]}." for d in arch['dreams']]
        sents.append(f"{g.capitalize()} currently has {arch['existing']}.")
        sents.append(f"{pos.capitalize()} income is about ${p['income']:,} a year and {g} could put aside around ${p['budget']} a month.")
    transcript = ' '.join(sents)

    goals = [d['label'] for d in dreams] + [w['label'] for w in worries]
    if live_mode:
        history = []
        n_pts = rnd.randint(5, 10)
        for k in range(n_pts):
            f = (k + 1) / n_pts
            history.append({'sentiment': int(sent * f + rnd.randint(-6, 6) * (1 - f)), 'buying': int(score * f + rnd.randint(-5, 5) * (1 - f))})
    else:
        history = [{'sentiment': sent, 'buying': score}]

    insights = {
        'needs': needs,
        'sentiment': {'score': sent, 'label': sent_label, 'emotion': emotion},
        'buyingSignal': {'score': score, 'level': level_of(score), 'signals': [
            'asked what the premium would look like' if score >= 35 else 'is looking around for now',
            f"can set aside about ${p['budget']} a month", 'wants to discuss with the family first' if score < 70 else 'asked to receive the numbers']},
        'nextQuestions': arch['next_q'][:3],
        'complianceFlags': flags,
        'productMatches': [
            {'productName': prods[0], 'fitScore': rnd.randint(64, 93), 'evidence': CITES[prods[0]][0]['excerpt'], 'source': 'product_summary.pdf'},
            {'productName': prods[1], 'fitScore': rnd.randint(40, 78), 'evidence': CITES[prods[1]][0]['excerpt'], 'source': 'product_summary.pdf'}],
        'lifeMap': {'people': people, 'dreams': dreams, 'worries': worries},
    }
    ep = created.timestamp()
    profile = {
        'id': pid, 'agentUserId': advisor[0], 'status': 'FINALIZED', 'customerName': name, 'age': p['age'], 'occupation': p['occ'],
        'incomeBand': f"${p['income']:,} a year", 'dependents': len(p['kids']) + (1 if arch['key'] == 'sandwich' else 0),
        'existingPolicies': [arch['existing'].replace('only ', '')], 'goalsAndConcerns': goals,
        'budgetNotes': f"Can set aside about ${p['budget']} a month.", 'notes': None,
        'captureMode': mode, 'rawTranscript': transcript,
        'liveInsights': {'latest': insights, 'history': history},
        'createdAt': ep, 'updatedAt': ep + rnd.randint(40, 200),
    }
    return dict(pid=pid, profile=profile, transcript=transcript, created=created, name=name, p=p, arch=arch, prods=prods, flags=flags,
                needs=needs, score=score, sent=sent, advisor=advisor, goals=goals, people=people, dreams=dreams, worries=worries, mode=mode)


# ── Recommendation run: all 11 agent outputs ────────────────────────────────────────────────────────────────
def make_run(c, started, dur, compliant, single):
    arch, p, name = c['arch'], c['p'], c['name']
    first = p['first']
    prods = c['prods'][:1] if single else c['prods']
    top = prods[0]
    budget = p['budget']
    needs_cat = {
        'Family protection': 'Family protection – Term Life', 'Education savings': 'Education savings – Term Life for education funding',
        'Critical illness': 'Critical illness', 'Income protection': 'Income protection – Term Life with TPD', 'Retirement': 'Retirement planning – investment-linked plan',
        'Legacy planning': 'Legacy planning', 'Medical': 'Medical cover', 'Wealth accumulation': 'Wealth accumulation – investment-linked plan'}
    top_needs = [n['label'] for n in c['needs'][:3]]
    gaps = [f'No life insurance to protect {"the family" if p["kids"] or p["spouse"] else "dependants"}' if 'Family protection' in top_needs or 'Income protection' in top_needs else 'Limited protection beyond the hospital plan',
            'No critical illness cover' if 'Critical illness' in top_needs else 'No dedicated long-term savings plan',
            ('No dedicated education savings' if 'Education savings' in top_needs else 'No documented retirement income plan')]
    risk_factors = [arch['existing'].capitalize().replace('Only ', 'Only ') , 'No life insurance in force' if arch['key'] != 'pre_retiree' else 'Retirement income not yet planned',
                    ('Home loan of about $%s' % f"{p['home']:,}") if p['home'] else 'Earning years still ahead']
    risk = {'riskFactors': risk_factors, 'riskLevel': arch['risk'], 'rationale': f"{first} has {arch['existing']} and obligations that a loss of income or a serious illness would put under strain."}
    need = {'protectionGaps': gaps, 'recommendedCategories': [needs_cat[n] for n in top_needs if n in needs_cat],
            'matchedProductNames': prods, 'rationale': f"{first}'s stated priorities ({', '.join(top_needs).lower()}) point to {' and '.join(prods)}."}
    lo = max(150, int(round(budget * 0.65 / 50) * 50))
    afford = {'estimatedBudgetBand': 'Comfortable' if budget >= 400 else 'Moderate', 'affordablePremiumRange': f'SGD {lo}-{budget}/month',
              'rationale': f"{first} said {('he' if p['male'] else 'she')} can set aside about ${budget} a month, which covers the premiums for the shortlisted plan(s)."}
    narrative = f"{first}, given your goals around {' and '.join(top_needs[:2]).lower()}, a combination of protection and savings fits your situation and budget."
    merge = {'needs': need, 'risks': risk, 'affordability': afford, 'combinedNarrative': narrative}
    persona = {'personaLabel': arch['persona'][0], 'lifeStage': arch['persona'][1],
               'characteristics': [f"Age {p['age']}, {p['occ']}", arch['existing'].capitalize(), f"Budget of about ${budget} a month"], 'rationale': f"{first} fits the {arch['persona'][1].lower()} segment."}
    reasons = {SG_PROD: ['Directly addresses the need for life cover to protect dependants and any home loan', 'Affordable premiums allow a high sum assured within the stated budget',
                         'Simple, pure protection with no investment risk', 'Can be paired with a critical illness rider for broader cover'],
               PA_PROD: ['Combines life and critical illness cover with long-term investing', 'Supports education or retirement saving through its investment component',
                         'Flexible fund choices for long-term growth', 'Minimum premium (SGD 300/month) fits within the stated budget' if budget >= 300 else 'Entry premium is close to the stated budget']}
    concerns = {SG_PROD: ['No savings or investment component', 'No built-in critical illness cover unless a rider is added'],
                PA_PROD: ['Investment risk: account value is not guaranteed and may fall below premiums paid', 'Provides less pure protection per dollar than a term plan']}
    scores = []
    for k, pr in enumerate(prods):
        sc = rnd.randint(84, 96) if k == 0 else rnd.randint(60, 86)
        scores.append({'productName': pr, 'score': sc, 'matchReasons': reasons[pr], 'concerns': concerns[pr]})
    scoring = {'scores': scores, 'methodology': 'Scores were weighted 50% on need match, 30% on risk coverage and 20% on affordability fit.'}
    ranked = sorted(scores, key=lambda x: -x['score'])
    shortlist = {'shortlistedProducts': [s['productName'] for s in ranked],
                 'rationale': f"Top {len(ranked)} of {len(ranked)} scored products, ranked by fit score: " + '; '.join(f"{s['productName']} ({s['score']}/100)" for s in ranked)}
    cites = []
    for pr in shortlist['shortlistedProducts']:
        for ct in CITES[pr][:3]:
            cites.append(ct)
    rag = {'citations': cites, 'allClaimsSupported': True,
           'notes': 'The evidence supports recommending ' + ' and '.join(shortlist['shortlistedProducts']) + f" for {first}'s stated needs and budget."}
    checks = [{'check': 'Affordability', 'passed': True, 'note': f"The shortlist fits within {first}'s stated budget of ${budget}/month."},
              {'check': 'Eligibility', 'passed': True, 'note': f"{first}'s age and occupation are eligible for the shortlisted product(s)."},
              {'check': 'Need match', 'passed': True, 'note': 'The shortlist addresses the identified protection gaps.'}]
    issues = []
    if not compliant:
        bad = rnd.choice([0, 2])
        checks[bad]['passed'] = False
        checks[bad]['note'] = ("The minimum premium is above the stated monthly budget." if bad == 0 else 'The shortlist leaves a stated need (education savings) unaddressed.')
        issues = [checks[bad]['note']]
    compliance = {'compliant': compliant, 'checks': checks, 'issues': issues,
                  'rationale': 'The recommendations are compliant.' if compliant else 'One check did not pass; the advisor should address it before proposing.'}
    tips = [SUMMARY_TIPS[n] for n in top_needs]
    summary = {'customerFacingSummary': narrative + ' ' + ' '.join(f"We recommend {pr}." for pr in shortlist['shortlistedProducts'][:2]),
               'keyTalkingPoints': tips[:3] + [f"This fits within your ${budget} a month budget."]}
    md = report_md(c, merge, persona, scoring, shortlist, rag, compliance, summary)
    sales = {'title': 'AIA Singapore — Advisory Sales Report', 'reportMarkdown': md, 'proposal': None, 'story': None}
    return dict(need=need, risk=risk, affordability=afford, merge=merge, persona=persona, productScoring=scoring, productShortlist=shortlist,
                ragValidation=rag, complianceCheck=compliance, summary=summary, salesReport=sales)


def report_md(c, merge, persona, scoring, shortlist, rag, compliance, summary):
    p, name = c['p'], c['name']
    ins = c['profile']['liveInsights']['latest']
    L = [f"# AIA Singapore — Advisory Sales Report", '', f"*Generated {c['created'].strftime('%-d %b %Y, %-I:%M %p')} SGT*", '', '## Customer',
         f'- **Name:** {name}', f"- **Age:** {p['age']}", f"- **Occupation:** {p['occ']}", f"- **Dependents:** {len(p['kids'])}",
         f"- **Persona:** {persona['personaLabel']} ({persona['lifeStage']})", '', '## Conversation Signals',
         f"- **Sentiment at end of call:** {ins['sentiment']['label']} ({ins['sentiment']['emotion']})",
         f"- **Buying signal:** {ins['buyingSignal']['level']} — {ins['buyingSignal']['score']}/100"]
    L += [f'  - {s}' for s in ins['buyingSignal']['signals']]
    L += ['', '## Executive Summary', summary['customerFacingSummary'], '', '## Recommended Products']
    for k, sc in enumerate(sorted(scoring['scores'], key=lambda x: -x['score']), 1):
        L.append(f"{k}. **{sc['productName']}** — fit score {sc['score']}/100")
        L += [f'   - Why it fits: {r}' for r in sc['matchReasons']] + [f'   - Consider: {r}' for r in sc['concerns']]
    L += ['', shortlist['rationale'], '', '## Needs, Risk & Affordability', '**Protection gaps:** ' + ', '.join(merge['needs']['protectionGaps']), '',
          f"**Risk level:** {merge['risks']['riskLevel']} — " + ', '.join(merge['risks']['riskFactors']), '',
          f"**Affordability:** {merge['affordability']['estimatedBudgetBand']} ({merge['affordability']['affordablePremiumRange']})", '', '## Supporting Evidence']
    L += [f"- *{ct['productName']} — {ct['docCategory']} ({ct['sourceFile']})*: {ct['excerpt']}" for ct in rag['citations']]
    L += ['', '## Talking Points for the Advisor'] + [f'- {t}' for t in summary['keyTalkingPoints']]
    L += ['', '## Compliance Review', f"**Overall:** {'COMPLIANT' if compliance['compliant'] else 'REVIEW NEEDED'}", '']
    L += [f"- {'✓' if ck['passed'] else '✗'} **{ck['check']}** — {ck['note']}" for ck in compliance['checks']]
    if c['flags']:
        L += ['', '## Advisor Conduct Flags (for review only)'] + [f"- **{f['severity'].upper()}** “{f['statement']}” — {f['advice']}" for f in c['flags']]
    return '\n'.join(L)


# ── Advice pack ─────────────────────────────────────────────────────────────────────────────────────────────
def make_pack(c, run, generated, reviewed_by, reviewed_at):
    p, name, arch = c['p'], c['name'], c['arch']
    first = p['first']
    prof = c['profile']
    tr = c['transcript']

    def fld(key, label, value, source, quote=None):
        return {'key': key, 'label': label, 'value': value if value else '', 'source': source if value else 'missing', 'quote': quote if value else None}

    quote = lambda s: s if s in tr else (s.split('.')[0] if s.split('.')[0] in tr else None)
    health_known = rnd.random() < 0.45
    sections = [
        {'title': 'Personal details', 'fields': [
            fld('name', 'Full name', name, 'profile'), fld('age', 'Age', str(p['age']), 'profile'), fld('occupation', 'Occupation', p['occ'], 'profile'),
            fld('maritalStatus', 'Marital status', 'married' if p['spouse'] else '', 'customer', quote(f"{p['spouse']}") if p['spouse'] else None),
            fld('residency', 'Residency status', '', 'missing'), fld('smoker', 'Smoker status', 'non-smoker' if rnd.random() < 0.3 else '', 'customer', 'I do not smoke')]},
        {'title': 'Family & dependants', 'fields': [
            fld('family', 'Family members', '; '.join(f"{x['name'] or ''}{' — ' if x['name'] else ''}{x['relation']}" for x in c['people']), 'customer', ' · '.join(x['said'] for x in c['people'])),
            fld('dependants', 'Number of dependants', str(prof['dependents']) if prof['dependents'] else '', 'profile'),
            fld('childrenAges', "Children's ages", ', '.join(str(k[2]) for k in p['kids']), 'customer', f"{p['kids'][0][0]} who is {p['kids'][0][2]}" if p['kids'] else None)]},
        {'title': 'Income & budget', 'fields': [
            fld('income', 'Income', prof['incomeBand'], 'profile'), fld('employmentType', 'Employment type', 'self-employed' if arch['key'] == 'business_owner' else 'employed', 'customer', f"work as a {p['occ']}"),
            fld('incomeStability', 'Income stability', 'fluctuates with the business' if arch['key'] == 'business_owner' else '', 'customer', 'my income goes up and down with the business'),
            fld('monthlyExpenses', 'Monthly expenses', '', 'missing'), fld('budget', 'Budget for protection', prof['budgetNotes'], 'profile')]},
        {'title': 'Assets & liabilities', 'fields': [
            fld('savings', 'Savings', '', 'missing'), fld('investments', 'Investments', '', 'missing'), fld('property', 'Property', 'home owner' if p['home'] else '', 'customer', 'our mortgage worries me'),
            fld('liabilities', 'Loans & liabilities', f"home loan of about ${p['home']:,}" if p['home'] else '', 'customer', 'our mortgage worries me'), fld('retirementSavings', 'Retirement savings (e.g. CPF)', 'some CPF savings' if arch['key'] == 'pre_retiree' else '', 'customer', 'some CPF savings')]},
        {'title': 'Existing coverage', 'fields': [fld('existingPolicies', 'Existing policies', prof['existingPolicies'][0], 'profile')]},
        {'title': 'Goals & concerns', 'fields': [
            fld('goals', 'Goals & concerns', '; '.join(c['goals']), 'profile'), fld('retirementAge', 'Target retirement age', '62' if arch['key'] == 'pre_retiree' else '', 'customer', 'retire by sixty two'),
            fld('educationGoal', 'Education goals', "children's education" if 'Education savings' in [n['label'] for n in c['needs']] else '', 'customer', "children's university")]},
        {'title': 'Health & risk profile', 'fields': [
            fld('health', 'Health conditions', 'generally healthy' if health_known else '', 'customer', 'generally healthy'),
            fld('familyHealthHistory', 'Family health history', '', 'missing'), fld('riskAppetite', 'Risk appetite', 'moderate' if arch['key'] in ('pre_retiree',) else '', 'customer', 'moderate')]},
    ]
    # fields whose quote is not in the transcript are shown as missing — as the real service would do
    for sec in sections:
        for f in sec['fields']:
            if f['source'] == 'customer' and not (f['quote'] and all(part.strip() in tr for part in f['quote'].split(' · '))):
                f.update({'value': '', 'source': 'missing', 'quote': None})
    missing = [f['label'] for sec in sections for f in sec['fields'] if f['source'] == 'missing']

    prods = run['productShortlist']['shortlistedProducts']
    items = []
    for pr in prods:
        sc = next(s for s in run['productScoring']['scores'] if s['productName'] == pr)
        items.append({'productName': pr, 'fitScore': sc['score'],
                      'need': ('To protect the family and cover the home loan if the unexpected happens.' if pr == SG_PROD else 'To build long-term savings while keeping life and critical illness protection.'),
                      'rationale': f"{pr} addresses {first}'s stated priorities ({', '.join(n['label'].lower() for n in c['needs'][:2])}) and fits the budget of about ${p['budget']} a month.",
                      'customerQuotes': [d['said'] for d in (c['dreams'] + c['worries']) if d['said'] in tr][:2],
                      'evidence': [{'source': ct['sourceFile'], 'excerpt': ct['excerpt']} for ct in CITES[pr][:3]],
                      'existingCoverNote': f"Existing cover: {prof['existingPolicies'][0]}.", 'risksToDisclose': sc['concerns'],
                      'matchReasons': sc['matchReasons'], 'concerns': sc['concerns']})
    record = {'needsSummary': f"{first} is {p['age']}, works as a {p['occ']} and is focused on {', '.join(n['label'].lower() for n in c['needs'][:3])}. {arch['existing'].capitalize()}.",
              'items': items, 'checks': run['complianceCheck']['checks'],
              'conductFlags': c['flags'],
              'disclosures': ['Draft prepared from the conversation and the recommendation analysis — to be reviewed and confirmed by the advisor before use.',
                              'Product details come from the AIA product documents available to the system; confirm against the latest product summary and benefit illustration before quoting.',
                              'Illustrative layout only — not an approved AIA or regulatory form.'],
              'compliant': run['complianceCheck']['compliant']}
    goal_txt = ' and '.join(n['label'].lower() for n in c['needs'][:2])
    follow = {'whatsapp': f"Hi {first}, thanks for taking the time today. I understand {goal_txt} matter most to you and your family. I'll prepare a couple of options that fit your ${p['budget']}/month budget and send them over shortly. Let me know if you have any questions. [Your name]",
              'emailSubject': f'Following up on your {goal_txt.split(" and ")[0]} plans',
              'emailBody': f"Hi {first},\n\nThank you for meeting with me. You told me that {goal_txt} are your priorities, and I'll prepare some options that fit within your budget.\n\nIf you have any questions or would like to adjust anything, just reply here.\n\nWarm regards,\n[Your name]"}
    due = lambda d: (generated + timedelta(days=d)).date().isoformat()
    tasks = [{'title': f'Send tailored illustration for {prods[0]}', 'reason': 'The customer asked to see the numbers.', 'priority': 'high', 'dueInDays': 2, 'dueDate': due(2), 'done': False},
             {'title': 'Collect income and expense details', 'reason': 'Needed to size the cover.', 'priority': 'high', 'dueInDays': 3, 'dueDate': due(3), 'done': False},
             {'title': 'Confirm health and smoker status', 'reason': 'Required for underwriting.', 'priority': 'medium', 'dueInDays': 5, 'dueDate': due(5), 'done': False},
             {'title': 'Schedule a follow-up meeting', 'reason': 'Review the options together.', 'priority': 'medium', 'dueInDays': 7, 'dueDate': due(7), 'done': False}]
    crm = {'caseNote': '\n'.join([f"Summary: Met with {name}, {p['age']}, {p['occ']}, to discuss {goal_txt}.", f"Needs identified: {', '.join(n['label'] for n in c['needs'][:3])}.",
                                  f"Products discussed: {', '.join(prods)}.", f"Customer sentiment and buying signal: {c['profile']['liveInsights']['latest']['sentiment']['label']}; {level_of(c['score'])} ({c['score']}/100).",
                                  'Concerns or objections: ' + (arch['objection'] + '.' if rnd.random() < 0.6 else 'none raised.'), 'Next steps: send illustration, collect missing details, book a follow-up.']),
           'tasks': tasks}
    meeting = {'objective': f"Confirm {first}'s priorities and agree a plan that fits ${p['budget']} a month.",
               'questionsToAsk': [f'Can you confirm your {missing[0].lower()}?' if missing else 'Is there anything we missed?'] + arch['next_q'] + ['What would make you comfortable going ahead?'],
               'gapsToFill': missing[:10],
               'likelyObjections': [{'objection': arch['objection'], 'response': 'Acknowledge the concern, then show how the plan can start at a comfortable level and grow with their situation.'},
                                    {'objection': 'I want to compare with other options first', 'response': 'Welcome the comparison and offer a side-by-side of benefits and costs.'}],
               'talkingPoints': run['summary']['keyTalkingPoints'][:3]}
    review = {'reviewedBy': reviewed_by, 'reviewedAt': reviewed_at.isoformat()} if reviewed_by else None
    return {'version': 1 if not review else rnd.choice([1, 1, 2]), 'generatedAt': generated.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z'), 'tone': 'warm',
            'factFind': sections, 'recordOfAdvice': record, 'followUp': follow, 'crm': crm, 'nextMeeting': meeting, 'review': review, 'failedSections': []}


# ── Build everything ────────────────────────────────────────────────────────────────────────────────────────
sql = ['BEGIN;', f"-- demo data generated {NOW.isoformat()}"]
for (uid, name, share, bias, fm) in ADVISORS:
    sql.append(f"INSERT INTO {S}.users (id, email, password_hash, created_at) VALUES ('{uid}', '{email(name)}', '!demo-account-cannot-sign-in', {ts(NOW - timedelta(days=DAYS + 5))}) ON CONFLICT (id) DO NOTHING;")

stats = dict(conv=0, analysed=0, runs=0, packs=0, reviewed=0, hot=0, live=0)
convs = []
for i in range(1, N_CONV + 1):
    mode = 'LIVE' if rnd.random() < 0.52 else 'DEBRIEF'
    created = sample_time(mode)
    advisor = wpick(ADVISORS, [a[2] for a in ADVISORS])
    arch = wpick(ARCH, [a['w'] for a in ARCH])
    convs.append((i, created, mode, advisor, arch))
convs.sort(key=lambda x: x[1])

for (i, created, mode, advisor, arch) in convs:
    stats['conv'] += 1
    if rnd.random() < 0.11:  # a session that never produced a transcript
        pid = f'demo-p-{i:04d}'
        prof = {'id': pid, 'agentUserId': advisor[0], 'status': 'IN_PROGRESS', 'captureMode': mode, 'createdAt': created.timestamp(), 'updatedAt': created.timestamp() + 20,
                'existingPolicies': [], 'goalsAndConcerns': []}
        sql.append(f"INSERT INTO {S}.customer_profiles (id, agent_user_id, status, profile_json, raw_transcript, created_at, updated_at) VALUES ('{pid}', '{advisor[0]}', 'IN_PROGRESS', {js(prof)}::jsonb, NULL, {ts(created)}, {ts(created + timedelta(seconds=20))});")
        continue
    p = build_person(arch)
    c = make_conversation(i, created, mode, advisor, arch, p)
    stats['analysed'] += 1
    stats['live'] += mode == 'LIVE'
    stats['hot'] += level_of(c['score']) == 'Hot'
    sql.append(f"INSERT INTO {S}.customer_profiles (id, agent_user_id, status, profile_json, raw_transcript, created_at, updated_at) VALUES ('{c['pid']}', '{advisor[0]}', 'FINALIZED', {js(c['profile'])}::jsonb, {dq(c['transcript'], 't')}, {ts(created)}, {ts(created + timedelta(seconds=120))});")

    age_h = (NOW - created).total_seconds() / 3600
    p_run = 0.78 if age_h > 36 else 0.45
    if rnd.random() < p_run:
        started = created + timedelta(minutes=rnd.randint(3, 35) if mode == 'LIVE' else rnd.randint(2, 20))
        if started > NOW - timedelta(minutes=5):
            continue
        dur = max(27, min(58, rnd.gauss(38, 5)))
        completed = started + timedelta(seconds=dur)
        compliant = rnd.random() < (0.62 if any(f['severity'] == 'high' for f in c['flags']) else 0.91)
        single = rnd.random() < 0.12
        run = make_run(c, started, dur, compliant, single)
        rid = f'demo-r-{i:04d}'
        stats['runs'] += 1
        sql.append(f"INSERT INTO {S}.recommendation_runs (run_id, customer_profile_id, status, error_message, started_at, completed_at) VALUES ('{rid}', '{c['pid']}', 'COMPLETED', NULL, {ts(started)}, {ts(completed)});")
        order = ['need', 'risk', 'affordability', 'merge', 'persona', 'productScoring', 'productShortlist', 'ragValidation', 'complianceCheck', 'summary', 'salesReport']
        tcur = started
        for k, ag in enumerate(order):
            share = [0.12, 0.07, 0.09, 0.06, 0.07, 0.07, 0.01, 0.07, 0.06, 0.08, 0.30][k]
            t0 = started if k < 3 else tcur
            t1 = t0 + timedelta(seconds=dur * share)
            if k >= 3 or k == 2:
                tcur = t1
            sql.append(f"INSERT INTO {S}.recommendation_agent_results (run_id, agent_name, status, input_json, result_json, started_at, completed_at) VALUES ('{rid}', '{ag}', 'COMPLETED', NULL, {js(run[ag])}::jsonb, {ts(t0)}, {ts(t1)});")
        if rnd.random() < 0.87:
            stats['packs'] += 1
            gen = completed + timedelta(seconds=rnd.randint(8, 40))
            reviewed_by = reviewed_at = None
            p_rev = 0.78 if (NOW - completed).days >= 3 else 0.35
            if rnd.random() < p_rev:
                reviewed_by = email(advisor[1])
                reviewed_at = min(NOW - timedelta(minutes=3), gen + timedelta(minutes=rnd.randint(12, 60 * 30)))
                stats['reviewed'] += 1
            pack = make_pack(c, run, gen, reviewed_by, reviewed_at)
            sql.append(f"INSERT INTO {S}.advice_packs (run_id, pack_json, updated_at) VALUES ('{rid}', {js(pack)}::jsonb, {ts(reviewed_at or gen)});")
sql.append('COMMIT;')
open(OUT, 'w').write('\n'.join(sql) + '\n')

open(OUT.replace('seed.sql', 'cleanup.sql'), 'w').write(f"""BEGIN;
DELETE FROM {S}.advice_packs WHERE run_id LIKE 'demo-r-%';
DELETE FROM {S}.recommendation_agent_results WHERE run_id LIKE 'demo-r-%';
DELETE FROM {S}.recommendation_runs WHERE run_id LIKE 'demo-r-%';
DELETE FROM {S}.customer_profiles WHERE id LIKE 'demo-p-%';
DELETE FROM {S}.users WHERE id LIKE 'demo-u-%' AND email LIKE '%@demo.aia.sg';
COMMIT;
""")
print(json.dumps(stats))
