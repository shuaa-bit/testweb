package gym;

import java.time.LocalDate;
import java.util.List;

/** Plain data types shared by the store and the API. Records are immutable, so "changes" create a copy. */
final class Models {
    private Models() {}

    /** A membership plan members can buy. */
    record Plan(String id, String name, String tagline, int months, double price,
                int sessionsPerMonth, List<String> features) {}

    /** A trainer. {@code specialty} must be one of Store.SPECIALTIES. */
    record Trainer(String id, String name, String specialty, String bio,
                   int experienceYears, int maxClients) {}

    /** A login. role is "member" or "admin". passwordHash is never sent to the browser. */
    record Member(String id, String name, String email, String passwordHash,
                  String role, String joinedAt) {}

    /** One purchased membership. status is "active" or "cancelled". trainerId may be null. */
    record Subscription(String id, String memberId, String planId, String trainerId,
                        String startDate, String endDate, String status,
                        double amountPaid, String paymentRef, String cardLast4) {

        boolean isActive() {
            return "active".equals(status) && !LocalDate.parse(endDate).isBefore(LocalDate.now());
        }

        Subscription withTrainer(String newTrainerId) {
            return new Subscription(id, memberId, planId, newTrainerId, startDate, endDate,
                    status, amountPaid, paymentRef, cardLast4);
        }

        Subscription withStatus(String newStatus) {
            return new Subscription(id, memberId, planId, trainerId, startDate, endDate,
                    newStatus, amountPaid, paymentRef, cardLast4);
        }
    }
}
