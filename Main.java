package gym;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gym.Models.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Gym management server: JSON API under /api, website files from ./public.
 * Run with:  java -cp out gym.Main     (see README.md)
 */
public final class Main {
    private static Store store;

    // ---- request bodies (JSON fields map onto these records by name) ----
    record RegisterRequest(String name, String email, String password) {}
    record LoginRequest(String email, String password) {}
    record CheckoutRequest(String planId, String trainerId, String cardName,
                           String cardNumber, String cardExpiry, String cardCvc) {}
    record TrainerChoice(String trainerId) {}
    record NewTrainer(String name, String specialty, String bio, int experienceYears, int maxClients) {}

    /** Lets a route choose its HTTP status (e.g. 201 Created). */
    record Reply(int status, Object body) {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8080"));
        String adminPassword = env("ADMIN_PASSWORD", "admin1234");
        store = new Store(Path.of(env("GYM_DATA", "data/gym-data.json")), adminPassword);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", new StaticFiles(Path.of(env("GYM_PUBLIC", "public"))));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Gym site running at http://localhost:" + port);
        System.out.println("Admin login: admin@gym.local / " + adminPassword
                + (adminPassword.equals("admin1234") ? "   (change it: set ADMIN_PASSWORD before first run)" : ""));
    }

    // ================================================================== API routing

    private static void handleApi(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            Object result = route(ex, method, path, query);
            if (result instanceof Reply r) sendJson(ex, r.status(), r.body());
            else sendJson(ex, 200, result);
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", "Bad request: " + e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Something went wrong on our side. Try again."));
        }
    }

    private static Object route(HttpExchange ex, String method, String path, Map<String, String> query) throws IOException {
        switch (method + " " + path) {
            // ---------- public ----------
            case "GET /api/plans":
                return store.plans();
            case "GET /api/specialties":
                return Store.SPECIALTIES;
            case "GET /api/trainers":
                return trainerViews(query.get("specialty"));

            // ---------- accounts ----------
            case "POST /api/auth/register": {
                RegisterRequest req = Json.parseAs(body(ex), RegisterRequest.class);
                Member m = store.register(req.name(), req.email(), req.password());
                return new Reply(201, authReply(m));
            }
            case "POST /api/auth/login": {
                LoginRequest req = Json.parseAs(body(ex), LoginRequest.class);
                return authReply(store.authenticate(req.email(), req.password()));
            }
            case "POST /api/auth/logout":
                store.endSession(bearerToken(ex));
                return Map.of("ok", true);
            case "GET /api/me":
                return meView(requireMember(ex));

            // ---------- buying and managing a membership ----------
            case "POST /api/subscriptions/checkout": {
                Member me = requireMember(ex);
                CheckoutRequest req = Json.parseAs(body(ex), CheckoutRequest.class);
                store.checkCanSubscribe(me.id(), req.planId(), req.trainerId());
                Plan plan = store.plan(req.planId());
                Payment payment = charge(req, plan.price());
                store.createSubscription(me.id(), req.planId(), req.trainerId(), payment.reference(), payment.last4());
                return new Reply(201, meView(me));
            }
            case "POST /api/subscriptions/cancel": {
                Member me = requireMember(ex);
                store.cancelSubscription(me.id());
                return meView(me);
            }
            case "PUT /api/subscriptions/trainer": {
                Member me = requireMember(ex);
                store.changeTrainer(me.id(), Json.parseAs(body(ex), TrainerChoice.class).trainerId());
                return meView(me);
            }

            // ---------- admin ----------
            case "GET /api/admin/stats":
                requireAdmin(ex);
                return adminStats();
            case "GET /api/admin/members":
                requireAdmin(ex);
                return adminMembers();
            case "GET /api/admin/subscriptions":
                requireAdmin(ex);
                return adminSubscriptions();
            case "POST /api/admin/trainers": {
                requireAdmin(ex);
                NewTrainer req = Json.parseAs(body(ex), NewTrainer.class);
                Trainer t = store.addTrainer(req.name(), req.specialty(), req.bio(), req.experienceYears(), req.maxClients());
                return new Reply(201, trainerView(t));
            }
            default:
                if (method.equals("DELETE") && path.startsWith("/api/admin/trainers/")) {
                    requireAdmin(ex);
                    store.removeTrainer(path.substring("/api/admin/trainers/".length()));
                    return Map.of("ok", true);
                }
                throw new ApiException(404, "Unknown API route: " + method + " " + path);
        }
    }

    // ================================================================== views (what the browser sees)

    private static Map<String, Object> publicMember(Member m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("name", m.name());
        out.put("email", m.email());
        out.put("role", m.role());
        out.put("joinedAt", m.joinedAt());
        return out; // note: passwordHash is deliberately left out
    }

    private static Map<String, Object> authReply(Member m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", store.createSession(m.id()));
        out.put("member", publicMember(m));
        return out;
    }

    private static Map<String, Object> trainerView(Trainer t) {
        long clients = store.clientCount(t.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("name", t.name());
        out.put("specialty", t.specialty());
        out.put("bio", t.bio());
        out.put("experienceYears", t.experienceYears());
        out.put("maxClients", t.maxClients());
        out.put("spotsLeft", Math.max(0, t.maxClients() - clients));
        return out;
    }

    private static List<Map<String, Object>> trainerViews(String specialty) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Trainer t : store.trainers()) {
            if (specialty == null || specialty.isBlank() || t.specialty().equalsIgnoreCase(specialty)) {
                out.add(trainerView(t));
            }
        }
        return out;
    }

    private static Map<String, Object> subscriptionView(Subscription s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("startDate", s.startDate());
        out.put("endDate", s.endDate());
        out.put("status", s.status());
        out.put("daysLeft", Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(s.endDate()))));
        out.put("amountPaid", s.amountPaid());
        out.put("paymentRef", s.paymentRef());
        out.put("cardLast4", s.cardLast4());
        out.put("plan", store.plan(s.planId()));
        Trainer t = store.trainer(s.trainerId());
        out.put("trainer", t == null ? null : trainerView(t));
        return out;
    }

    private static Map<String, Object> meView(Member m) {
        Subscription s = store.activeSubscription(m.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("member", publicMember(m));
        out.put("subscription", s == null ? null : subscriptionView(s));
        return out;
    }

    private static Map<String, Object> adminStats() {
        List<Subscription> subs = store.subscriptions();
        YearMonth thisMonth = YearMonth.now();
        double total = 0, month = 0;
        long active = 0;
        for (Subscription s : subs) {
            total += s.amountPaid();
            if (YearMonth.from(LocalDate.parse(s.startDate())).equals(thisMonth)) month += s.amountPaid();
            if (s.isActive()) active++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", store.members().stream().filter(m -> m.role().equals("member")).count());
        out.put("activeMemberships", active);
        out.put("trainers", store.trainers().size());
        out.put("revenueThisMonth", month);
        out.put("revenueTotal", total);
        return out;
    }

    private static List<Map<String, Object>> adminMembers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Member m : store.members()) {
            if (!m.role().equals("member")) continue;
            Map<String, Object> row = publicMember(m);
            Subscription s = store.activeSubscription(m.id());
            row.put("plan", s == null ? null : store.plan(s.planId()).name());
            Trainer t = s == null ? null : store.trainer(s.trainerId());
            row.put("trainer", t == null ? null : t.name());
            row.put("endDate", s == null ? null : s.endDate());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> adminSubscriptions() {
        List<Subscription> subs = new ArrayList<>(store.subscriptions());
        Collections.reverse(subs); // newest first
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subscription s : subs) {
            Member m = store.member(s.memberId());
            Plan p = store.plan(s.planId());
            Trainer t = store.trainer(s.trainerId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("member", m == null ? "(deleted)" : m.name());
            row.put("email", m == null ? "" : m.email());
            row.put("plan", p == null ? "(removed plan)" : p.name());
            row.put("trainer", t == null ? null : t.name());
            row.put("startDate", s.startDate());
            row.put("endDate", s.endDate());
            row.put("status", s.isActive() ? "active" : s.status().equals("cancelled") ? "cancelled" : "expired");
            row.put("amountPaid", s.amountPaid());
            row.put("paymentRef", s.paymentRef());
            out.add(row);
        }
        return out;
    }

    // ================================================================== payments (SIMULATED)

    private record Payment(String reference, String last4) {}

    /**
     * Demo payment step. It validates the card form and approves it, but moves no money and
     * keeps no card number. To go live, replace this with a real gateway (Stripe, PayPal, ...)
     * that collects card details in the gateway's own hosted fields, so card numbers never touch your server.
     * Test cards: 4242 4242 4242 4242 is approved, 4000 0000 0000 0002 is declined.
     */
    private static Payment charge(CheckoutRequest req, double amount) {
        String number = req.cardNumber() == null ? "" : req.cardNumber().replaceAll("[\\s-]", "");
        if (req.cardName() == null || req.cardName().isBlank()) throw new ApiException(400, "Enter the name on the card.");
        if (!number.matches("\\d{13,19}") || !luhnValid(number)) throw new ApiException(400, "That card number isn't valid. Check each digit.");
        if (!cardExpiryValid(req.cardExpiry())) throw new ApiException(400, "That card is expired or the date isn't in MM/YY format.");
        if (req.cardCvc() == null || !req.cardCvc().matches("\\d{3,4}")) throw new ApiException(400, "Enter the 3 or 4 digit security code.");
        if (number.equals("4000000000000002")) throw new ApiException(402, "Your card was declined. Try a different card.");

        byte[] b = new byte[5];
        new SecureRandom().nextBytes(b);
        return new Payment("PAY-" + HexFormat.of().formatHex(b).toUpperCase(), number.substring(number.length() - 4));
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl && (d *= 2) > 9) d -= 9;
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static boolean cardExpiryValid(String mmYY) {
        if (mmYY == null || !mmYY.matches("\\d{2}\\s*/\\s*\\d{2}")) return false;
        String[] p = mmYY.split("\\s*/\\s*");
        int month = Integer.parseInt(p[0]);
        int year = 2000 + Integer.parseInt(p[1]);
        if (month < 1 || month > 12) return false;
        return !YearMonth.of(year, month).isBefore(YearMonth.now());
    }

    // ================================================================== auth helpers

    private static String bearerToken(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    private static Member requireMember(HttpExchange ex) {
        Member m = store.memberForToken(bearerToken(ex));
        if (m == null) throw new ApiException(401, "Log in to continue.");
        return m;
    }

    private static void requireAdmin(HttpExchange ex) {
        if (!requireMember(ex).role().equals("admin")) throw new ApiException(403, "Admins only.");
    }

    // ================================================================== HTTP helpers

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(1_000_000 + 1);
            if (data.length > 1_000_000) throw new ApiException(413, "Request is too large.");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
