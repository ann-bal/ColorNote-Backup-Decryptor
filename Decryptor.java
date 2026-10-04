import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Security;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Decrypt a password-protected ColorNote V8 backup into a JSON notes array. */
public final class Decryptor {
    private static final int HEADER_SIZE = 28;
    private static final byte[] SALT = "ColorNote Fixed Salt".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 20;
    private static final String ALGORITHM = "PBEWITHMD5AND128BITAES-CBC-OPENSSL";

    private Decryptor() { }

    private static void progress(String message) {
        System.out.println("[colornote] " + message);
    }

    private static byte[] decrypt(byte[] backup, String password) throws Exception {
        if (backup.length <= HEADER_SIZE || (backup.length - HEADER_SIZE) % 16 != 0) {
            throw new UserError("Not a valid V8 encrypted backup (invalid ciphertext length).", 3);
        }

        progress("Skipping the 28-byte V8 header; decrypting " + (backup.length - HEADER_SIZE) + " bytes.");
        progress("Deriving parameters with Bouncy Castle. Password is not logged.");

        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(ALGORITHM, "BC");
        PBEKeySpec pbeKeySpec = new PBEKeySpec(password.toCharArray(), SALT, ITERATIONS);
        byte[] derivedKey = keyFactory.generateSecret(pbeKeySpec).getEncoded();
        SecretKeySpec key = new SecretKeySpec(derivedKey, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(new byte[16]));

        byte[] ciphertext = new byte[backup.length - HEADER_SIZE];
        System.arraycopy(backup, HEADER_SIZE, ciphertext, 0, ciphertext.length);
        try {
            return cipher.doFinal(ciphertext);
        } catch (BadPaddingException | IllegalBlockSizeException error) {
            throw new UserError("Decryption failed: incorrect password or corrupted backup.", 4);
        } finally {
            pbeKeySpec.clearPassword();
        }
    }

    private static String decodeUtf8(byte[] plaintext) {
        // The V8 stream has binary record-length prefixes between UTF-8 JSON
        // objects. Java's decoder replaces those invalid bytes, just as the
        // working extractor does, while leaving each JSON object intact.
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static ArrayNode extractActiveNotes(byte[] plaintext, ObjectMapper mapper) throws IOException, UserError {
        String text = decodeUtf8(plaintext);
        ArrayNode notes = mapper.createArrayNode();
        int records = 0;
        int position = 0;

        while (true) {
            int start = text.indexOf('{', position);
            if (start < 0) {
                break;
            }

            try (JsonParser parser = mapper.getFactory().createParser(text.substring(start))) {
                JsonNode record = mapper.readTree(parser);
                long consumed = parser.getCurrentLocation().getCharOffset();
                position = start + (int) Math.max(consumed, 1);

                if (record != null && record.isObject() && record.has("_id")) {
                    records++;
                    if (record.path("active_state").asInt(Integer.MIN_VALUE) == 0) {
                        notes.add(record);
                    }
                }
            } catch (IOException malformedJson) {
                // The decrypted stream also contains binary length prefixes.
                position = start + 1;
            }
        }

        progress("Found " + records + " note records; " + notes.size() + " are active.");
        if (notes.isEmpty()) {
            throw new UserError("No active notes found; password or backup may be invalid.", 4);
        }
        return notes;
    }

    private static void writeNotes(Path destination, ArrayNode notes, ObjectMapper mapper) throws IOException, UserError {
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path parent = absoluteDestination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new UserError("Output directory does not exist: " + parent, 3);
        }
        if (Files.exists(absoluteDestination)) {
            throw new UserError("Refusing to overwrite existing output: " + absoluteDestination, 3);
        }

        Path temporary = Files.createTempFile(parent, absoluteDestination.getFileName().toString(), ".part");
        try {
            progress("Writing JSON output to: " + absoluteDestination);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), notes);
            try {
                Files.move(temporary, absoluteDestination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteDestination);
            }
        } catch (IOException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
    }

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            System.err.println("Usage: Decryptor INPUT_BACKUP PASSWORD OUTPUT_PATH");
            System.exit(2);
        }

        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            Path source = Paths.get(arguments[0]);
            Path destination = Paths.get(arguments[2]);
            if (!Files.isRegularFile(source)) {
                throw new UserError("Cannot read input backup: " + source, 3);
            }
            if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
                throw new UserError("Output path must be different from the input backup.", 3);
            }

            byte[] backup = Files.readAllBytes(source);
            progress("Input opened: " + source + " (" + backup.length + " bytes). ");
            byte[] plaintext = decrypt(backup, arguments[1]);
            progress("Ciphertext decrypted successfully; extracting JSON note records.");
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode notes = extractActiveNotes(plaintext, mapper);
            writeNotes(destination, notes, mapper);
            System.out.println("Active notes JSON written to: " + destination);
        } catch (UserError error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(error.exitCode);
        } catch (Exception error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(3);
        }
    }

    private static final class UserError extends Exception {
        private final int exitCode;

        private UserError(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }
}
