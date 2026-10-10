# Ironside Gym: membership and trainer booking site

Clients create an account, buy a membership online and pick a trainer by specialty
(Weight Loss, Strength Training, Cardio, Pilates, Yoga & Mobility, HIIT & Boxing).
Staff get an admin dashboard.

- **Frontend:** plain HTML, CSS and JavaScript in `public/`
- **Backend:** plain Java (JDK 17+), no Maven, no libraries. It serves the website and a JSON API.
- **Data:** saved automatically to `data/gym-data.json`. Delete that file to reset everything.

## Run it

You need a JDK (not just a JRE), version 17 or newer. Check with `javac -version`.

    ./run.sh          (Mac / Linux)
    run.bat           (Windows)

Then open http://localhost:8080

Admin login: `admin@gym.local` / `admin1234`
Set your own password before the first run: `ADMIN_PASSWORD=something-long ./run.sh`

Other settings (environment variables): `PORT` (default 8080), `GYM_DATA` (data file path).

## Try the buying flow

1. Pick a plate in the hero (or scroll to Trainers) and choose a membership.
2. Pick a trainer, then create an account.
3. Pay with test card **4242 4242 4242 4242**, any future expiry, any 3 digits.
   The card **4000 0000 0000 0002** is declined.

## Where things are

    src/gym/Main.java         API routes, request handling, simulated payment
    src/gym/Store.java        data, passwords, sessions, rules (capacity, one plan at a time), seed data
    src/gym/Models.java       Plan, Trainer, Member, Subscription
    src/gym/Json.java         small JSON reader/writer
    src/gym/StaticFiles.java  serves public/
    public/index.html         page shell
    public/css/style.css      design (specialty colors are at the top)
    public/js/app.js          all pages: home, join, account, admin

Common edits:
- **Plans, prices, seed trainers:** `Store.seed()` in `Store.java`. Seeding only runs when `data/gym-data.json` doesn't exist, so delete it after editing.
- **Gym name:** `index.html`, `renderHeader()` in `app.js`.
- **Currency symbol:** `CURRENCY` at the top of `app.js`.
- **Specialties:** `Store.SPECIALTIES`, plus a matching color in `style.css` (`.s-...`) and label in `PLATE_LABELS` in `app.js`.

## API

| Method and path | Who | What |
|---|---|---|
| GET `/api/plans`, `/api/specialties`, `/api/trainers?specialty=Pilates` | anyone | catalog |
| POST `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | anyone | accounts (token in `Authorization: Bearer ...`) |
| GET `/api/me` | member | profile and active membership |
| POST `/api/subscriptions/checkout` | member | buy a plan (with optional `trainerId`) |
| PUT `/api/subscriptions/trainer` | member | change trainer |
| POST `/api/subscriptions/cancel` | member | cancel |
| GET `/api/admin/stats`, `/members`, `/subscriptions` | admin | dashboard data |
| POST `/api/admin/trainers`, DELETE `/api/admin/trainers/{id}` | admin | manage trainers |

## Before going live

This is a working starter, not a hardened production system.

1. **Payments are simulated.** `Main.charge()` checks the card format and approves it. Replace it with a real
   gateway (Stripe, PayPal, etc.) using the gateway's hosted card fields so card numbers never reach your server.
   Create the membership only after the gateway confirms payment (usually via a webhook).
2. **Use HTTPS.** Put the app behind a reverse proxy (Nginx, Caddy) or a host that provides TLS.
3. **Change the admin password** and add login rate limiting.
4. **Use a real database** (PostgreSQL or MySQL) once you have many members. The JSON file is fine for a demo or a very small gym.
5. **Logins reset when the server restarts** (sessions are kept in memory). Members just log in again.

## Ideas for next steps

Session booking and a trainer calendar, membership renewals and freezes, email receipts, check-in by QR code,
trainer accounts so trainers can see their own clients.
