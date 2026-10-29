package ai.ontoflow.platform.connector;

public interface SecretStore {
    String seal(String plaintext);
    String open(String ciphertext);
}
