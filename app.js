'use strict';

/* =========================================================
   Ironside Gym: website logic (no frameworks)
   - Talks to the Java backend through /api
   - Routes use the URL hash: #/ (home), #/join, #/account, #/admin
   ========================================================= */

const CURRENCY = '$'; // change to '₱', '€', etc. (prices themselves live in the backend: Store.java)

// How each specialty looks on its plate. The CSS class (s-<slug>) supplies the color.
const PLATE_LABELS = {
  'Weight Loss':       { lines: ['Weight', 'Loss'],       kg: 25 },
  'Strength Training': { lines: ['Strength', 'Training'], kg: 20 },
  'Cardio':            { lines: ['Cardio'],               kg: 15 },
  'Pilates':           { lines: ['Pilates'],              kg: 10 },
  'Yoga & Mobility':   { lines: ['Yoga &', 'Mobility'],   kg: 5 },
  'HIIT & Boxing':     { lines: ['HIIT &', 'Boxing'],     kg: 2.5 },
};

const state = {
  token: safeStorage('get', 'gym_token'),
  member: null,
  subscription: null,
  plans: [],
  trainers: [],
  specialties: [],
  filter: '',                       // home page trainer filter
  join: { step: 1, planId: null, trainerId: null, filter: '' },
  authTab: null,                    // null = use the default for the page
  adminTab: 'members',
  admin: null,
  pendingScroll: null,
};

const main = document.getElementById('main');

/* ---------------------------------------------------------
   Small helpers
   --------------------------------------------------------- */

function safeStorage(op, key, value) {
  try {
    if (op === 'get') return localStorage.getItem(key);
    if (op === 'set') localStorage.setItem(key, value);
    if (op === 'remove') localStorage.removeItem(key);
  } catch { /* storage blocked: the user just has to log in again next visit */ }
  return null;
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const round2 = (n) => Math.round(n * 100) / 100;
const money = (n) => CURRENCY + (Number.isInteger(n) ? n : n.toFixed(2));
const fmtDate = (iso) => new Date(iso + 'T00:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
const initials = (name) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
const firstName = (name) => name.split(/\s+/)[0];
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const cssEsc = (s) => (window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/"/g, '\\"'));
const trainerById = (id) => state.trainers.find((t) => t.id === id);
const planById = (id) => state.plans.find((p) => p.id === id);
const isAdmin = () => state.member && state.member.role === 'admin';

function currentRoute() {
  const name = location.hash.replace(/^#\/?/, '').split('?')[0];
  return ['join', 'account', 'admin'].includes(name) ? name : 'home';
}

function go(hash) {
  if (location.hash === hash) render(); else location.hash = hash;
}

let toastTimer;
function toast(message, isError = false) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.toggle('is-error', isError);
  el.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('is-visible'), isError ? 6000 : 3500);
}

function confirmDialog({ title, text, okLabel }) {
  return new Promise((resolve) => {
    const dialog = document.getElementById('confirm');
    document.getElementById('confirm-title').textContent = title;
    document.getElementById('confirm-text').textContent = text;
    document.getElementById('confirm-ok').textContent = okLabel;
    dialog.returnValue = '';
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true });
    dialog.showModal();
  });
}

/** Re-render but keep keyboard focus on the same control, so screen-reader and keyboard users don't lose their place. */
function keepFocus(fn) {
  const a = document.activeElement;
  let selector = null;
  if (a && a.dataset && a.dataset.action) {
    selector = `[data-action="${cssEsc(a.dataset.action)}"]`;
    if (a.dataset.id) selector += `[data-id="${cssEsc(a.dataset.id)}"]`;
    if (a.dataset.specialty !== undefined) selector += `[data-specialty="${cssEsc(a.dataset.specialty)}"]`;
    if (a.dataset.tab) selector += `[data-tab="${cssEsc(a.dataset.tab)}"]`;
    if (a.dataset.step) selector += `[data-step="${cssEsc(a.dataset.step)}"]`;
  }
  fn();
  if (selector) {
    const next = document.querySelector(selector);
    if (next && !next.disabled) next.focus({ preventScroll: true });
  }
}

/* ---------------------------------------------------------
   Talking to the backend
   --------------------------------------------------------- */

async function api(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (state.token) headers.Authorization = 'Bearer ' + state.token;

  let res;
  try {
    res = await fetch('/api' + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new Error("Can't reach the server. Check that it is running, then try again.");
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty or non-JSON body */ }

  if (!res.ok) {
    if (res.status === 401 && state.token && !path.startsWith('/auth/')) {
      clearSession(); // login expired
      renderHeader();
    }
    throw new Error((data && data.error) || `Request failed (${res.status}).`);
  }
  return data;
}

function setSession(token, member) {
  state.token = token;
  state.member = member;
  safeStorage('set', 'gym_token', token);
}

function clearSession() {
  state.token = null;
  state.member = null;
  state.subscription = null;
  state.admin = null;
  safeStorage('remove', 'gym_token');
}

async function loadMe() {
  if (!state.token) return;
  try {
    const me = await api('/me');
    state.member = me.member;
    state.subscription = me.subscription;
  } catch {
    clearSession();
  }
}

async function loadTrainers() {
  state.trainers = await api('/trainers');
}

async function loadCatalog() {
  const [plans, specialties, trainers] = await Promise.all([api('/plans'), api('/specialties'), api('/trainers')]);
  state.plans = plans;
  state.specialties = specialties;
  state.trainers = trainers;
}

/* ---------------------------------------------------------
   Shared pieces: header, chips, trainer cards, auth forms
   --------------------------------------------------------- */

function renderHeader() {
  const m = state.member;
  const links = m
    ? (isAdmin() ? '<a href="#/admin">Admin</a>' : '<a href="#/account">My membership</a>') +
      '<button type="button" data-action="logout">Log out</button>'
    : '<a href="#/account">Log in</a>';
  const showJoin = !isAdmin() && !state.subscription;
  document.getElementById('header').innerHTML = `
    <div class="site-header__inner">
      <a class="brand" href="#/"><span class="brand-mark" aria-hidden="true"></span>Ironside</a>
      <nav class="nav" aria-label="Main">
        <a href="#/" data-scroll="plans">Memberships</a>
        <a href="#/" data-scroll="trainers">Trainers</a>
        ${links}
        ${showJoin ? '<a class="nav__join" href="#/join">Join now</a>' : ''}
      </nav>
    </div>`;
}

function chips(selected, action) {
  const all = `<button type="button" class="chip" aria-pressed="${selected === ''}" data-action="${action}" data-specialty="">All trainers</button>`;
  return all + state.specialties.map((s) => `
    <button type="button" class="chip s-${slug(s)}" aria-pressed="${selected === s}" data-action="${action}" data-specialty="${esc(s)}">${esc(s)}</button>`).join('');
}

function spotsLabel(t) {
  if (t.spotsLeft === 0) return '<span class="spots spots--full">Fully booked</span>';
  return `<span class="spots ${t.spotsLeft <= 3 ? 'spots--low' : ''}">${plural(t.spotsLeft, 'spot', 'spots')} left</span>`;
}

/**
 * One trainer. mode:
 *   'choose' = home page, button starts the join flow with this trainer
 *   'select' = join step 2, button toggles the selection
 *   'view'   = read only (account page)
 */
function trainerCard(t, mode = 'view', selected = false) {
  let action = '';
  if (mode === 'choose') {
    action = `<button type="button" class="btn btn--small" data-action="pick-trainer" data-id="${esc(t.id)}" ${t.spotsLeft === 0 ? 'disabled' : ''}>Train with ${esc(firstName(t.name))}</button>`;
  } else if (mode === 'select') {
    const label = selected ? 'Selected' : t.spotsLeft === 0 ? 'Fully booked' : 'Select';
    action = `<button type="button" class="btn btn--small ${selected ? '' : 'btn--ghost'}" aria-pressed="${selected}" data-action="select-trainer" data-id="${esc(t.id)}" ${t.spotsLeft === 0 && !selected ? 'disabled' : ''}>${label}</button>`;
  }
  return `
    <article class="trainer s-${slug(t.specialty)} ${selected ? 'is-selected' : ''}">
      <div class="trainer__avatar" aria-hidden="true">${esc(initials(t.name))}</div>
      <div>
        <h3 class="trainer__name">${esc(t.name)}</h3>
        <p class="trainer__meta">${esc(t.specialty)}, ${plural(t.experienceYears, 'year', 'years')} coaching</p>
        <p class="trainer__bio">${esc(t.bio)}</p>
      </div>
      <div class="trainer__foot">${spotsLabel(t)}${action}</div>
    </article>`;
}

function rosterHtml(filter, mode, selectedId) {
  const list = state.trainers.filter((t) => !filter || t.specialty === filter);
  if (!list.length) {
    return `<div class="empty"><p>No trainers in ${esc(filter)} yet. Pick another specialty.</p></div>`;
  }
  return list.map((t) => trainerCard(t, mode, t.id === selectedId)).join('');
}

function authPanel(defaultTab, heading) {
  const tab = state.authTab || defaultTab;
  const form = tab === 'register' ? `
    <form class="form" data-form="register" novalidate>
      <div class="field"><label for="reg-name">Full name</label><input id="reg-name" name="name" autocomplete="name" required></div>
      <div class="field"><label for="reg-email">Email</label><input id="reg-email" name="email" type="email" autocomplete="email" required></div>
      <div class="field"><label for="reg-pass">Password</label><input id="reg-pass" name="password" type="password" autocomplete="new-password" minlength="8" required>
        <span class="hint">At least 8 characters.</span></div>
      <p class="form-error" role="alert"></p>
      <button class="btn btn--wide" type="submit">Create account</button>
    </form>` : `
    <form class="form" data-form="login" novalidate>
      <div class="field"><label for="log-email">Email</label><input id="log-email" name="email" type="email" autocomplete="email" required></div>
      <div class="field"><label for="log-pass">Password</label><input id="log-pass" name="password" type="password" autocomplete="current-password" required></div>
      <p class="form-error" role="alert"></p>
      <button class="btn btn--wide" type="submit">Log in</button>
    </form>`;
  return `
    <div class="panel">
      <h2>${esc(heading)}</h2>
      <div class="tabs">
        <button type="button" aria-pressed="${tab === 'register'}" data-action="auth-tab" data-tab="register">New member</button>
        <button type="button" aria-pressed="${tab === 'login'}" data-action="auth-tab" data-tab="login">I have an account</button>
      </div>
      ${form}
    </div>`;
}

/* ---------------------------------------------------------
   Home
   --------------------------------------------------------- */

function plateButton(specialty) {
  const info = PLATE_LABELS[specialty] || { lines: [specialty], kg: '' };
  return `
    <button type="button" class="plate s-${slug(specialty)}" data-action="filter-trainers" data-specialty="${esc(specialty)}" aria-label="Show ${esc(specialty)} trainers">
      <span class="plate__kg" aria-hidden="true">${info.kg ? info.kg + ' kg' : ''}</span>
      <span class="plate__name" aria-hidden="true">${info.lines.map(esc).join('<br>')}</span>
    </button>`;
}

function planColumn(p) {
  const monthly = p.months > 1 ? `${money(round2(p.price / p.months))} a month` : 'Cancel any time';
  return `
    <article class="plan">
      <h3 class="plan__name">${esc(p.name)}</h3>
      <p class="plan__tagline">${esc(p.tagline)}</p>
      <div class="plan__price">${money(p.price)}</div>
      <p class="plan__period">for ${plural(p.months, 'month', 'months')}</p>
      <p class="plan__monthly">${monthly}</p>
      <ul class="plan__features">${p.features.map((f) => `<li>${esc(f)}</li>`).join('')}</ul>
      <button type="button" class="btn" data-action="choose-plan" data-id="${esc(p.id)}">Choose ${esc(p.name)}</button>
    </article>`;
}

function viewHome() {
  const joinLink = state.subscription
    ? '<a class="btn btn--light" href="#/account">My membership</a>'
    : '<a class="btn btn--light" href="#/join">Join now</a>';
  main.innerHTML = `
    <section class="hero">
      <div class="wrap">
        <h1>Pick your goal, get the right coach</h1>
        <p class="hero__lead">Join online in three steps: choose a membership, pick a trainer who specializes in your goal, and pay.</p>
        <div class="hero__cta">
          ${joinLink}
          <button type="button" class="btn btn--outline-light" data-scroll="plans">See memberships</button>
        </div>
        <p class="hero__ask" id="rack-label">What are you training for? Tap a plate.</p>
        <div class="rack" role="group" aria-labelledby="rack-label">
          ${state.specialties.map(plateButton).join('')}
        </div>
      </div>
    </section>

    <section class="section" id="plans">
      <div class="wrap">
        <div class="section__head">
          <h2>Memberships</h2>
          <p>Every plan includes one-to-one sessions with the trainer you choose.</p>
        </div>
        <div class="plans">${state.plans.map(planColumn).join('')}</div>
      </div>
    </section>

    <section class="section" id="trainers">
      <div class="wrap">
        <div class="section__head">
          <h2>Find your trainer</h2>
          <p>Filter by specialty. Each trainer takes a limited number of clients, so you get real attention.</p>
        </div>
        <div class="chips" id="home-chips" role="group" aria-label="Filter trainers by specialty">${chips(state.filter, 'filter-trainers')}</div>
        <div class="roster" id="home-roster">${rosterHtml(state.filter, 'choose')}</div>
      </div>
    </section>`;
}

function updateHomeTrainers() {
  keepFocus(() => {
    document.getElementById('home-chips').innerHTML = chips(state.filter, 'filter-trainers');
    document.getElementById('home-roster').innerHTML = rosterHtml(state.filter, 'choose');
  });
}

function scrollToSection(id) {
  if (currentRoute() !== 'home') {
    state.pendingScroll = id;
    go('#/');
    return;
  }
  const el = document.getElementById(id);
  if (el) el.scrollIntoView({ behavior: 'smooth' });
}

/* ---------------------------------------------------------
   Join: choose plan, pick trainer, pay
   --------------------------------------------------------- */

function viewJoin() {
  if (isAdmin()) {
    main.innerHTML = pageShell('Join', 'Admin accounts can\'t buy memberships. Log out and join with a member account to test the flow.');
    return;
  }
  if (state.subscription) {
    main.innerHTML = pageShell('You\'re already a member',
      'You have an active membership. You can change your trainer or cancel from your account.',
      '<a class="btn" href="#/account">Go to my membership</a>');
    return;
  }

  const j = state.join;
  if (!planById(j.planId)) j.planId = null;
  if (j.trainerId && !trainerById(j.trainerId)) j.trainerId = null;
  if (j.step > 1 && !j.planId) j.step = 1;

  const stepDefs = [['1', 'Membership'], ['2', 'Trainer'], ['3', 'Payment']];
  const stepper = stepDefs.map(([n, label]) => {
    const num = Number(n);
    const reachable = num === 1 || !!j.planId;
    return `<li ${j.step === num ? 'aria-current="step"' : ''} class="${num < j.step ? 'is-done' : ''}">
      <button type="button" data-action="join-step" data-step="${num}" ${reachable ? '' : 'disabled'}>
        <span class="step__num">${num < j.step ? '✓' : num}</span><span class="step__label">${label}</span>
      </button></li>`;
  }).join('');

  let body;
  if (j.step === 1) body = stepPlan();
  else if (j.step === 2) body = stepTrainer();
  else body = stepPay();

  main.innerHTML = `
    <div class="page"><div class="wrap">
      <div class="page__title"><h1>Join Ironside</h1></div>
      <ol class="steps" aria-label="Progress">${stepper}</ol>
      <div class="join">
        <div>${body}</div>
        ${summaryPanel()}
      </div>
    </div></div>`;
}

function stepPlan() {
  const j = state.join;
  return `
    <div class="panel">
      <h2>Choose a membership</h2>
      <div class="plan-choice">
        ${state.plans.map((p) => `
          <button type="button" aria-pressed="${p.id === j.planId}" data-action="select-plan" data-id="${esc(p.id)}">
            <span class="pc__name">${esc(p.name)}</span>
            <span class="pc__price">${money(p.price)}</span>
            <span class="pc__detail">${esc(p.tagline)}. ${plural(p.sessionsPerMonth, 'session', 'sessions')} with your trainer each month.</span>
            <span class="pc__sub">for ${plural(p.months, 'month', 'months')}</span>
          </button>`).join('')}
      </div>
      <div class="step-nav">
        <span></span>
        <button type="button" class="btn" data-action="join-next" ${j.planId ? '' : 'disabled'}>Continue to trainer</button>
      </div>
    </div>`;
}

function stepTrainer() {
  const j = state.join;
  return `
    <div class="panel">
      <h2>Pick a trainer</h2>
      <p>Choose by specialty. You can change trainer any time from your account.</p>
      <div class="chips" id="join-chips" role="group" aria-label="Filter trainers by specialty">${chips(j.filter, 'join-filter')}</div>
      <div class="roster roster--single" id="join-roster">${rosterHtml(j.filter, 'select', j.trainerId)}</div>
      <div class="step-nav">
        <button type="button" class="btn btn--ghost" data-action="join-back">Back</button>
        <span>
          <button type="button" class="btn btn--ghost" data-action="skip-trainer">Decide later</button>
          <button type="button" class="btn" data-action="join-next" ${j.trainerId ? '' : 'disabled'}>Continue to payment</button>
        </span>
      </div>
    </div>`;
}

function stepPay() {
  const plan = planById(state.join.planId);
  const back = '<div class="step-nav"><button type="button" class="btn btn--ghost" data-action="join-back">Back</button><span></span></div>';

  if (!state.member) {
    return authPanel('register', 'Create an account to finish') + back;
  }
  return `
    <div class="panel">
      <h2>Payment</h2>
      <p class="notice"><strong>Demo mode.</strong> No money is charged. Use card 4242 4242 4242 4242 with any future date and any 3 digits. The card 4000 0000 0000 0002 simulates a decline.</p>
      <form class="form" data-form="checkout" novalidate>
        <div class="field"><label for="cc-name">Name on card</label><input id="cc-name" name="cardName" autocomplete="cc-name" required></div>
        <div class="field"><label for="cc-number">Card number</label><input id="cc-number" name="cardNumber" inputmode="numeric" autocomplete="cc-number" placeholder="4242 4242 4242 4242" data-format="card" required></div>
        <div class="field-row">
          <div class="field"><label for="cc-exp">Expiry (MM/YY)</label><input id="cc-exp" name="cardExpiry" inputmode="numeric" autocomplete="cc-exp" placeholder="12/30" data-format="expiry" maxlength="5" required></div>
          <div class="field"><label for="cc-cvc">Security code</label><input id="cc-cvc" name="cardCvc" inputmode="numeric" autocomplete="cc-csc" placeholder="123" maxlength="4" required></div>
        </div>
        <p class="form-error" role="alert"></p>
        <button class="btn btn--wide" type="submit">Pay ${money(plan.price)} and join</button>
      </form>
    </div>${back}`;
}

function summaryPanel() {
  const j = state.join;
  const plan = planById(j.planId);
  const trainer = trainerById(j.trainerId);
  return `
    <aside class="panel summary" aria-label="Order summary">
      <h2>Your order</h2>
      <dl>
        <dt>Membership</dt>
        <dd>${plan ? `${esc(plan.name)}, ${plural(plan.months, 'month', 'months')}` : 'Not chosen yet'}</dd>
        <dt>Sessions</dt>
        <dd>${plan ? `${plural(plan.sessionsPerMonth, 'session', 'sessions')} a month` : '-'}</dd>
        <dt>Trainer</dt>
        <dd>${trainer ? `${esc(trainer.name)} (${esc(trainer.specialty)})` : 'Not chosen yet'}</dd>
      </dl>
      <div class="total"><span>Total today</span><strong>${plan ? money(plan.price) : '-'}</strong></div>
    </aside>`;
}

async function submitCheckout(form, button) {
  const j = state.join;
  const fd = new FormData(form);
  const body = {
    planId: j.planId,
    trainerId: j.trainerId || '',
    cardName: fd.get('cardName'),
    cardNumber: fd.get('cardNumber'),
    cardExpiry: fd.get('cardExpiry'),
    cardCvc: fd.get('cardCvc'),
  };
  const me = await api('/subscriptions/checkout', { method: 'POST', body });
  state.subscription = me.subscription;
  state.join = { step: 1, planId: null, trainerId: null, filter: '' };
  await loadTrainers();
  toast('Payment approved. Welcome to Ironside!');
  go('#/account');
}

/* ---------------------------------------------------------
   Account
   --------------------------------------------------------- */

function pageShell(title, intro, extra = '') {
  return `<div class="page"><div class="wrap">
    <div class="page__title"><h1>${esc(title)}</h1>${intro ? `<p>${esc(intro)}</p>` : ''}</div>${extra}</div></div>`;
}

function viewAccount() {
  if (!state.member) {
    state.authTab = state.authTab || 'login';
    main.innerHTML = `<div class="page"><div class="wrap">
      <div class="page__title"><h1>Your account</h1><p>Log in to see your membership and trainer.</p></div>
      <div style="max-width:30rem">${authPanel('login', 'Welcome back')}</div></div></div>`;
    return;
  }
  if (isAdmin()) { go('#/admin'); return; }

  const s = state.subscription;
  if (!s) {
    main.innerHTML = pageShell(`Hi ${firstName(state.member.name)}`, 'You don\'t have an active membership yet.',
      '<a class="btn" href="#/join">Choose a membership</a>');
    return;
  }

  const options = state.specialties.map((spec) => {
    const group = state.trainers.filter((t) => t.specialty === spec);
    if (!group.length) return '';
    return `<optgroup label="${esc(spec)}">${group.map((t) => {
      const mine = s.trainer && s.trainer.id === t.id;
      const full = t.spotsLeft === 0 && !mine;
      return `<option value="${esc(t.id)}" ${mine ? 'selected' : ''} ${full ? 'disabled' : ''}>${esc(t.name)} (${full ? 'fully booked' : plural(t.spotsLeft, 'spot', 'spots') + ' left'})</option>`;
    }).join('')}</optgroup>`;
  }).join('');

  main.innerHTML = `
    <div class="page"><div class="wrap">
      <div class="page__title"><h1>Hi ${esc(firstName(state.member.name))}</h1></div>

      <section class="membership" aria-label="Your membership">
        <div><p style="margin:0;color:#b5c2d6;font-weight:600">Active membership</p><h2>${esc(s.plan.name)}</h2></div>
        <dl class="meta">
          <div><dt>Started</dt><dd>${fmtDate(s.startDate)}</dd></div>
          <div><dt>Runs until</dt><dd>${fmtDate(s.endDate)}</dd></div>
          <div><dt>Days left</dt><dd>${s.daysLeft}</dd></div>
          <div><dt>Sessions</dt><dd>${plural(s.plan.sessionsPerMonth, 'session', 'sessions')} a month</dd></div>
        </dl>
        <p style="margin:0;color:#b5c2d6">Paid ${money(s.amountPaid)} with the card ending ${esc(s.cardLast4)}. Receipt ${esc(s.paymentRef)}.</p>
      </section>

      <section class="panel" style="margin-top:1.5rem" aria-label="Your trainer">
        <h2>Your trainer</h2>
        ${s.trainer ? trainerCard(s.trainer, 'view') : '<p>You haven\'t picked a trainer yet. Choose one below.</p>'}
        <form class="form" data-form="change-trainer" style="margin-top:1.2rem;max-width:30rem">
          <div class="field">
            <label for="trainer-select">${s.trainer ? 'Change trainer' : 'Choose a trainer'}</label>
            <select id="trainer-select" name="trainerId">
              <option value="" ${s.trainer ? '' : 'selected'}>No trainer</option>
              ${options}
            </select>
          </div>
          <p class="form-error" role="alert"></p>
          <div><button class="btn btn--ghost" type="submit">Save trainer</button></div>
        </form>
      </section>

      <section class="panel" aria-label="Cancel membership">
        <h2>Cancel membership</h2>
        <p>Cancelling ends your membership today and frees up your trainer's spot. This demo doesn't issue refunds.</p>
        <button type="button" class="btn btn--danger" data-action="cancel-membership">Cancel membership</button>
      </section>
    </div></div>`;
}

/* ---------------------------------------------------------
   Admin
   --------------------------------------------------------- */

async function viewAdmin() {
  if (!state.member) {
    state.authTab = state.authTab || 'login';
    main.innerHTML = `<div class="page"><div class="wrap">
      <div class="page__title"><h1>Admin</h1><p>Log in with the admin account.</p></div>
      <div style="max-width:30rem">${authPanel('login', 'Admin login')}</div></div></div>`;
    return;
  }
  if (!isAdmin()) {
    main.innerHTML = pageShell('Admins only', 'This area is for gym staff.', '<a class="btn" href="#/account">Back to my membership</a>');
    return;
  }
  main.innerHTML = '<p class="loading">Loading dashboard…</p>';
  try {
    const [stats, members, subscriptions] = await Promise.all([
      api('/admin/stats'), api('/admin/members'), api('/admin/subscriptions')]);
    state.admin = { stats, members, subscriptions };
  } catch (err) {
    main.innerHTML = pageShell('Admin', err.message);
    return;
  }
  renderAdmin();
}

function table(headers, rows, emptyText) {
  if (!rows.length) return `<div class="empty"><p>${esc(emptyText)}</p></div>`;
  return `<div class="table-wrap"><table>
    <thead><tr>${headers.map((h) => `<th scope="col">${esc(h)}</th>`).join('')}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}

function renderAdmin() {
  const { stats, members, subscriptions } = state.admin;
  const tab = state.adminTab;
  let content;

  if (tab === 'members') {
    content = table(['Name', 'Email', 'Joined', 'Plan', 'Trainer', 'Runs until'],
      members.map((m) => [esc(m.name), esc(m.email), fmtDate(m.joinedAt), esc(m.plan || 'No active plan'), esc(m.trainer || '-'), m.endDate ? fmtDate(m.endDate) : '-']),
      'No members have signed up yet.');
  } else if (tab === 'subscriptions') {
    content = table(['Member', 'Plan', 'Trainer', 'Started', 'Ends', 'Status', 'Paid', 'Receipt'],
      subscriptions.map((s) => [esc(s.member), esc(s.plan), esc(s.trainer || '-'), fmtDate(s.startDate), fmtDate(s.endDate),
        `<span class="status status--${esc(s.status)}">${esc(s.status)}</span>`, money(s.amountPaid), esc(s.paymentRef)]),
      'No memberships have been bought yet.');
  } else {
    const rows = state.trainers.map((t) => [
      `<span class="dot s-${slug(t.specialty)}"></span>${esc(t.name)}`, esc(t.specialty), String(t.experienceYears),
      `${t.maxClients - t.spotsLeft} of ${t.maxClients}`,
      `<button type="button" class="btn btn--danger btn--small" data-action="remove-trainer" data-id="${esc(t.id)}" data-name="${esc(t.name)}">Remove</button>`]);
    content = `
      ${table(['Trainer', 'Specialty', 'Years', 'Clients', ''], rows, 'No trainers yet. Add one below.')}
      <section class="panel" style="margin-top:1.5rem">
        <h2>Add a trainer</h2>
        <form class="form" data-form="add-trainer" novalidate style="max-width:34rem">
          <div class="field"><label for="tr-name">Name</label><input id="tr-name" name="name" required></div>
          <div class="field"><label for="tr-spec">Specialty</label>
            <select id="tr-spec" name="specialty">${state.specialties.map((s) => `<option>${esc(s)}</option>`).join('')}</select></div>
          <div class="field-row">
            <div class="field"><label for="tr-years">Years coaching</label><input id="tr-years" name="experienceYears" type="number" min="0" max="60" value="3"></div>
            <div class="field"><label for="tr-max">Client limit</label><input id="tr-max" name="maxClients" type="number" min="1" max="100" value="10"></div>
          </div>
          <div class="field"><label for="tr-bio">Short bio</label><textarea id="tr-bio" name="bio" rows="3"></textarea></div>
          <p class="form-error" role="alert"></p>
          <div><button class="btn" type="submit">Add trainer</button></div>
        </form>
      </section>`;
  }

  const tabBtn = (id, label) => `<button type="button" aria-pressed="${tab === id}" data-action="admin-tab" data-tab="${id}">${label}</button>`;
  main.innerHTML = `
    <div class="page"><div class="wrap">
      <div class="page__title"><h1>Admin</h1></div>
      <section class="stats" aria-label="Summary">
        <div class="stat"><strong>${stats.members}</strong><span>Members</span></div>
        <div class="stat"><strong>${stats.activeMemberships}</strong><span>Active memberships</span></div>
        <div class="stat"><strong>${stats.trainers}</strong><span>Trainers</span></div>
        <div class="stat"><strong>${money(stats.revenueThisMonth)}</strong><span>Sales this month</span></div>
        <div class="stat"><strong>${money(stats.revenueTotal)}</strong><span>Sales all time</span></div>
      </section>
      <div class="tabs">${tabBtn('members', 'Members')}${tabBtn('subscriptions', 'Memberships')}${tabBtn('trainers', 'Trainers')}</div>
      ${content}
    </div></div>`;
}

/* ---------------------------------------------------------
   Router
   --------------------------------------------------------- */

let renderId = 0;

async function render() {
  const id = ++renderId;
  const route = currentRoute();
  renderHeader();
  const scrollTarget = state.pendingScroll;
  state.pendingScroll = null;

  if (route === 'home') viewHome();
  else if (route === 'join') viewJoin();
  else if (route === 'account') viewAccount();
  else if (route === 'admin') await viewAdmin();

  if (id !== renderId) return; // a newer navigation took over
  const titles = { home: 'Ironside Gym: memberships and personal trainers', join: 'Join Ironside Gym', account: 'My membership | Ironside Gym', admin: 'Admin | Ironside Gym' };
  document.title = titles[route];

  if (scrollTarget) document.getElementById(scrollTarget)?.scrollIntoView();
  else if (route !== lastRoute) window.scrollTo(0, 0);
  lastRoute = route;
}
let lastRoute = null;

window.addEventListener('hashchange', () => { state.authTab = null; render(); });

/* ---------------------------------------------------------
   Click and form handling (event delegation)
   --------------------------------------------------------- */

/** Move to another step of the join flow and put keyboard focus at the top of the page content. */
function showStep(step) {
  state.join.step = step;
  state.authTab = null;
  viewJoin();
  window.scrollTo(0, 0);
  main.focus({ preventScroll: true });
}

const actions = {
  'filter-trainers'(el) {
    state.filter = el.dataset.specialty;
    updateHomeTrainers();
    if (el.classList.contains('plate')) scrollToSection('trainers');
  },

  'choose-plan'(el) {
    state.join.planId = el.dataset.id;
    state.join.step = 2;
    go('#/join');
  },

  'pick-trainer'(el) {
    const t = trainerById(el.dataset.id);
    if (!t) return;
    state.join.trainerId = t.id;
    state.join.filter = t.specialty;
    state.join.step = state.join.planId ? 3 : 1;
    toast(state.join.planId ? `${t.name} selected.` : `${t.name} selected. Now choose a membership.`);
    go('#/join');
  },

  'select-plan'(el) {
    state.join.planId = el.dataset.id;
    keepFocus(viewJoin);
  },

  'select-trainer'(el) {
    state.join.trainerId = state.join.trainerId === el.dataset.id ? null : el.dataset.id;
    keepFocus(viewJoin);
  },

  'join-filter'(el) {
    state.join.filter = el.dataset.specialty;
    keepFocus(viewJoin);
  },

  'join-step'(el) { showStep(Number(el.dataset.step)); },
  'join-next'() { showStep(Math.min(3, state.join.step + 1)); },
  'join-back'() { showStep(Math.max(1, state.join.step - 1)); },
  'skip-trainer'() { state.join.trainerId = null; showStep(3); },

  'auth-tab'(el) {
    state.authTab = el.dataset.tab;
    keepFocus(() => {
      if (currentRoute() === 'join') viewJoin();
      else if (currentRoute() === 'admin') viewAdmin();
      else viewAccount();
    });
  },

  async logout() {
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* token already invalid */ }
    clearSession();
    toast('You\'re logged out.');
    go('#/');
  },

  async 'cancel-membership'() {
    const ok = await confirmDialog({
      title: 'Cancel your membership?',
      text: 'Your membership ends today and there is no refund in this demo. You can buy a new one any time.',
      okLabel: 'Cancel membership',
    });
    if (!ok) return;
    try {
      const me = await api('/subscriptions/cancel', { method: 'POST' });
      state.subscription = me.subscription;
      await loadTrainers();
      toast('Membership cancelled.');
      go('#/account');
    } catch (err) { toast(err.message, true); }
  },

  'admin-tab'(el) {
    state.adminTab = el.dataset.tab;
    keepFocus(renderAdmin);
  },

  async 'remove-trainer'(el) {
    const ok = await confirmDialog({
      title: `Remove ${el.dataset.name}?`,
      text: 'Their current clients keep their memberships but will have no trainer until they pick a new one.',
      okLabel: 'Remove trainer',
    });
    if (!ok) return;
    try {
      await api('/admin/trainers/' + encodeURIComponent(el.dataset.id), { method: 'DELETE' });
      await loadTrainers();
      state.admin.stats = await api('/admin/stats');
      toast('Trainer removed.');
      renderAdmin();
    } catch (err) { toast(err.message, true); }
  },
};

document.addEventListener('click', (e) => {
  const scroller = e.target.closest('[data-scroll]');
  if (scroller) {
    e.preventDefault();
    scrollToSection(scroller.dataset.scroll);
    return;
  }
  const el = e.target.closest('[data-action]');
  if (!el || el.disabled) return;
  const fn = actions[el.dataset.action];
  if (fn) fn(el);
});

const formHandlers = {
  async login(form) {
    const fd = new FormData(form);
    const res = await api('/auth/login', { method: 'POST', body: { email: fd.get('email'), password: fd.get('password') } });
    await afterAuth(res, `Welcome back, ${firstName(res.member.name)}.`);
  },

  async register(form) {
    const fd = new FormData(form);
    const res = await api('/auth/register', { method: 'POST', body: { name: fd.get('name'), email: fd.get('email'), password: fd.get('password') } });
    await afterAuth(res, `Account created. Welcome, ${firstName(res.member.name)}.`);
  },

  checkout: submitCheckout,

  async 'change-trainer'(form) {
    const trainerId = new FormData(form).get('trainerId') || null;
    const me = await api('/subscriptions/trainer', { method: 'PUT', body: { trainerId } });
    state.subscription = me.subscription;
    await loadTrainers();
    toast(trainerId ? 'Trainer updated.' : 'Trainer removed from your membership.');
    viewAccount();
  },

  async 'add-trainer'(form) {
    const fd = new FormData(form);
    await api('/admin/trainers', {
      method: 'POST',
      body: {
        name: fd.get('name'), specialty: fd.get('specialty'), bio: fd.get('bio'),
        experienceYears: Number(fd.get('experienceYears')), maxClients: Number(fd.get('maxClients')),
      },
    });
    await loadTrainers();
    state.admin.stats = await api('/admin/stats');
    toast('Trainer added.');
    renderAdmin();
  },
};

async function afterAuth(res, message) {
  setSession(res.token, res.member);
  await loadMe();
  state.authTab = null;
  toast(message);
  if (isAdmin()) { go('#/admin'); return; }
  await render(); // join stays on step 3 (now with the payment form); account shows the membership
}

document.addEventListener('submit', async (e) => {
  const form = e.target.closest('form[data-form]');
  if (!form) return;
  e.preventDefault();

  const handler = formHandlers[form.dataset.form];
  const button = form.querySelector('button[type="submit"]');
  const errorBox = form.querySelector('.form-error');
  const label = button ? button.textContent : '';
  if (errorBox) errorBox.textContent = '';
  if (button) { button.disabled = true; button.textContent = 'Please wait…'; }
  try {
    await handler(form);
  } catch (err) {
    if (errorBox && form.isConnected) { errorBox.textContent = err.message; }
    else toast(err.message, true);
    if (form.dataset.form === 'checkout') loadTrainers().catch(() => {});
  } finally {
    if (button && button.isConnected) { button.disabled = false; button.textContent = label; }
  }
});

// Format card number and expiry as the person types.
document.addEventListener('input', (e) => {
  const input = e.target;
  if (!input.matches || !input.matches('[data-format]')) return;
  const digits = input.value.replace(/\D/g, '');
  if (input.dataset.format === 'card') {
    input.value = digits.slice(0, 19).replace(/(.{4})/g, '$1 ').trim();
  } else if (input.dataset.format === 'expiry') {
    const d = digits.slice(0, 4);
    input.value = d.length > 2 ? d.slice(0, 2) + '/' + d.slice(2) : d;
  }
});

/* ---------------------------------------------------------
   Start
   --------------------------------------------------------- */

(async function init() {
  try {
    await Promise.all([loadCatalog(), loadMe()]);
  } catch (err) {
    renderHeader();
    main.innerHTML = `<div class="page"><div class="wrap">
      <div class="page__title"><h1>We can't load the gym right now</h1><p>${esc(err.message)}</p></div>
      <button type="button" class="btn" onclick="location.reload()">Try again</button></div></div>`;
    return;
  }
  render();
})();
