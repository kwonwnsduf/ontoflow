package ai.ontoflow.platform.connector;

import java.util.List;

public interface EnterpriseAdapter {
    List<Payment> failedPayments(String credential);
    Payment payment(String credential, String id);
    Customer customer(String credential, String id);
    List<Employee> employees(String credential);
    Followup createFollowup(String credential, String customerId, String assigneeId, String summary);
    Draft createDraft(String credential, String to, String subject, String content);
    void test(String credential);

    record Payment(String id, String customerId, long amount, String currency, String status, String failedAt) {}
    record Customer(String id, String name, String email) {}
    record Employee(String id, String name) {}
    record Followup(String id, String customerId, String assigneeId, String summary) {}
    record Draft(String id, String to, String subject, String content) {}
}
