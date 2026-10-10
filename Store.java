package gym;

import gym.Models.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * All gym data lives here (in memory) and is saved to a JSON file after every change.
 * Every public method is synchronized, so concurrent requests can't corrupt data.
 */
final class Store {
    static final List<String> SPECIALTIES = List.of(
            "Weight Loss", "Strength Training", "Cardio", "Pilates", "Yoga & Mobility", "HIIT & Boxing");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_SECONDS = 7L * 24 * 3600;

    private final Path file;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<String, Trainer> trainers = new LinkedHashMap<>();
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new HashMap<>(); // login tokens (cleared on restart)

    private record Session(String memberId, Instant expires) {}

    Store(Path file, String adminPassword) {
        this.file = file;
        if (Files.exists(file)) load(); else seed();
        if (members.values().stream().noneMatch(m -> m.role().equals("admin"))) {
            Member admin = new Member(newId("m"), "Gym Admin", "admin@gym.local",
                    hashPassword(adminPassword), "admin", LocalDate.now().toString());
            members.put(admin.id(), admin);
        }
        save();
    }

    // ---------------------------------------------------------------- reading

    synchronized List<Plan> plans() { return List.copyOf(plans.values()); }

    synchronized Plan plan(String id) { return plans.get(id); }

    synchronized List<Trainer> trainers() { return List.copyOf(trainers.values()); }

    synchronized Trainer trainer(String id) { return id == null ? null : trainers.get(id); }

    synchronized List<Member> members() { return List.copyOf(members.values()); }

    synchronized List<Subscription> subscriptions() { return List.copyOf(subscriptions.values()); }

    synchronized Member member(String id) { return members.get(id); }

    /** The member's current paid-up subscription, or null. */
    synchronized Subscription activeSubscription(String memberId) {
        return subscriptions.values().stream()
                .filter(s -> s.memberId().equals(memberId) && s.isActive())
                .findFirst().orElse(null);
    }

    synchronized long clientCount(String trainerId) {
        return subscriptions.values().stream()
                .filter(s -> trainerId.equals(s.trainerId()) && s.isActive()).count();
    }

    // ---------------------------------------------------------------- accounts & sessions

    synchronized Member register(String name, String email, String password) {
        name = name == null ? "" : name.trim();
        email = email == null ? "" : email.trim().toLowerCase();
        if (name.length() < 2) throw new ApiException(400, "Enter your full name.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ApiException(400, "Enter a valid email address.");
        if (password == null || password.length() < 8) throw new ApiException(400, "Password must be at least 8 characters.");
        if (findByEmail(email) != null) throw new ApiException(409, "An account with this email already exists. Log in instead.");
        Member m = new Member(newId("m"), name, email, hashPassword(password), "member", LocalDate.now().toString());
        members.put(m.id(), m);
        save();
        return m;
    }

    synchronized Member authenticate(String email, String password) {
        Member m = findByEmail(email == null ? "" : email.trim().toLowerCase());
        // Same error for "no such email" and "wrong password" so attackers can't probe for accounts.
        if (m == null || password == null || !verifyPassword(password, m.passwordHash())) {
            throw new ApiException(401, "Email or password is incorrect.");
        }
        return m;
    }

    synchronized String createSession(String memberId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(memberId, Instant.now().plusSeconds(SESSION_SECONDS)));
        return token;
    }

    synchronized Member memberForToken(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires().isBefore(Instant.now())) { sessions.remove(token); return null; }
        return members.get(s.memberId());
    }

    synchronized void endSession(String token) { if (token != null) sessions.remove(token); }

    private Member findByEmail(String email) {
        return members.values().stream().filter(m -> m.email().equals(email)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- subscriptions

    /** Throws if this member can't buy this plan/trainer right now. Safe to call before taking payment. */
    synchronized void checkCanSubscribe(String memberId, String planId, String trainerId) {
        if (plans.get(planId) == null) throw new ApiException(400, "Choose a membership plan.");
        if (activeSubscription(memberId) != null) {
            throw new ApiException(409, "You already have an active membership. Cancel it first to buy a different one.");
        }
        if (trainerId != null && !trainerId.isBlank()) {
            Trainer t = trainers.get(trainerId);
            if (t == null) throw new ApiException(400, "That trainer no longer exists. Pick another.");
            if (clientCount(trainerId) >= t.maxClients()) {
                throw new ApiException(409, t.name() + " has no spots left. Pick another trainer in the same specialty.");
            }
        }
    }

    synchronized Subscription createSubscription(String memberId, String planId, String trainerId,
                                                 String paymentRef, String cardLast4) {
        checkCanSubscribe(memberId, planId, trainerId); // re-check: things may have changed while paying
        Plan plan = plans.get(planId);
        LocalDate start = LocalDate.now();
        Subscription s = new Subscription(newId("s"), memberId, planId,
                trainerId == null || trainerId.isBlank() ? null : trainerId,
                start.toString(), start.plusMonths(plan.months()).toString(),
                "active", plan.price(), paymentRef, cardLast4);
        subscriptions.put(s.id(), s);
        save();
        return s;
    }

    synchronized void cancelSubscription(String memberId) {
        Subscription s = activeSubscription(memberId);
        if (s == null) throw new ApiException(404, "You don't have an active membership.");
        subscriptions.put(s.id(), s.withStatus("cancelled"));
        save();
    }

    synchronized void changeTrainer(String memberId, String trainerId) {
        Subscription s = activeSubscription(memberId);
        if (s == null) throw new ApiException(404, "Buy a membership before choosing a trainer.");
        if (trainerId != null && !trainerId.isBlank()) {
            Trainer t = trainers.get(trainerId);
            if (t == null) throw new ApiException(400, "That trainer doesn't exist.");
            boolean alreadyTheirs = trainerId.equals(s.trainerId());
            if (!alreadyTheirs && clientCount(trainerId) >= t.maxClients()) {
                throw new ApiException(409, t.name() + " has no spots left.");
            }
        }
        subscriptions.put(s.id(), s.withTrainer(trainerId == null || trainerId.isBlank() ? null : trainerId));
        save();
    }

    // ---------------------------------------------------------------- admin: trainers

    synchronized Trainer addTrainer(String name, String specialty, String bio, int years, int maxClients) {
        name = name == null ? "" : name.trim();
        if (name.length() < 2) throw new ApiException(400, "Enter the trainer's name.");
        if (!SPECIALTIES.contains(specialty)) throw new ApiException(400, "Pick a specialty from the list.");
        if (maxClients < 1 || maxClients > 100) throw new ApiException(400, "Client limit must be between 1 and 100.");
        if (years < 0 || years > 60) throw new ApiException(400, "Years of experience must be between 0 and 60.");
        Trainer t = new Trainer(newId("t"), name, specialty, bio == null ? "" : bio.trim(), years, maxClients);
        trainers.put(t.id(), t);
        save();
        return t;
    }

    synchronized void removeTrainer(String id) {
        if (trainers.remove(id) == null) throw new ApiException(404, "Trainer not found.");
        // Members who had this trainer keep their membership but are left without a trainer.
        subscriptions.replaceAll((k, s) -> id.equals(s.trainerId()) ? s.withTrainer(null) : s);
        save();
    }

    // ---------------------------------------------------------------- persistence

    private void save() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("plans", plans.values());
        all.put("trainers", trainers.values());
        all.put("members", members.values());
        all.put("subscriptions", subscriptions.values());
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(all), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not save data: " + e.getMessage());
        }
    }

    private void load() {
        try {
            Map<?, ?> root = (Map<?, ?>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : (List<?>) root.get("plans")) { Plan p = Json.toRecord((Map<?, ?>) o, Plan.class); plans.put(p.id(), p); }
            for (Object o : (List<?>) root.get("trainers")) { Trainer t = Json.toRecord((Map<?, ?>) o, Trainer.class); trainers.put(t.id(), t); }
            for (Object o : (List<?>) root.get("members")) { Member m = Json.toRecord((Map<?, ?>) o, Member.class); members.put(m.id(), m); }
            for (Object o : (List<?>) root.get("subscriptions")) { Subscription s = Json.toRecord((Map<?, ?>) o, Subscription.class); subscriptions.put(s.id(), s); }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage()
                    + ". Fix or delete the file to start fresh.", e);
        }
    }

    private void seed() {
        addPlan(new Plan("starter", "Starter", "Month to month, cancel any time", 1, 39, 2, List.of(
                "Full gym floor access", "2 one-to-one sessions each month", "All group classes", "Locker and shower access")));
        addPlan(new Plan("steady", "Steady", "Three months to build the habit", 3, 99, 4, List.of(
                "Everything in Starter", "4 one-to-one sessions each month", "Monthly body composition check", "2 guest passes")));
        addPlan(new Plan("committed", "Committed", "A full year, best price per month", 12, 349, 6, List.of(
                "Everything in Steady", "6 one-to-one sessions each month", "Training plan reviewed every quarter", "5 guest passes")));

        addSeedTrainer("Marco Reyes", "Weight Loss", 8, 12,
                "Builds fat-loss plans around daily steps, simple food tracking and two strength sessions a week.");
        addSeedTrainer("Dana Whitfield", "Weight Loss", 5, 10,
                "Works with beginners and returning lifters who want steady progress without crash diets.");
        addSeedTrainer("Tomas Ilves", "Strength Training", 11, 8,
                "Powerlifting coach. Squat, bench and deadlift technique, and programs that add weight to the bar.");
        addSeedTrainer("Aiko Tanaka", "Strength Training", 6, 10,
                "Muscle-building and injury-proof lifting for people who sit most of the day.");
        addSeedTrainer("Priya Nair", "Cardio", 7, 12,
                "Endurance coach for runners, cyclists and anyone training for a first 5K or 10K.");
        addSeedTrainer("Lucas Ferreira", "Cardio", 4, 12,
                "Heart-rate based conditioning on the rower, bike and treadmill.");
        addSeedTrainer("Elena Markovic", "Pilates", 9, 8,
                "Mat and reformer Pilates for core strength, posture and back-pain management.");
        addSeedTrainer("Sofia Lindqvist", "Yoga & Mobility", 10, 10,
                "Mobility work, stretching and yoga flows to move better and recover faster.");
        addSeedTrainer("Jamal Brooks", "HIIT & Boxing", 8, 14,
                "Boxing fundamentals and high-intensity circuits that leave you tired in a good way.");
    }

    private void addPlan(Plan p) { plans.put(p.id(), p); }

    private void addSeedTrainer(String name, String specialty, int years, int max, String bio) {
        Trainer t = new Trainer(newId("t"), name, specialty, bio, years, max);
        trainers.put(t.id(), t);
    }

    // ---------------------------------------------------------------- helpers

    private static String newId(String prefix) {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return prefix + "_" + HexFormat.of().formatHex(b);
    }

    /** PBKDF2 password hash, stored as "iterations:salt:hash" (never store plain passwords). */
    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        int iterations = 120_000;
        return iterations + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(pbkdf2(password, salt, iterations));
    }

    static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 3) return false;
        byte[] expected = Base64.getDecoder().decode(parts[2]);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(parts[1]), Integer.parseInt(parts[0]));
        return MessageDigest.isEqual(expected, actual); // constant-time comparison
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
