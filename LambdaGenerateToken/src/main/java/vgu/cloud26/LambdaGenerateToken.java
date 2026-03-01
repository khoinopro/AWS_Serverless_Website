package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public class LambdaGenerateToken implements RequestHandler<Map<String, Object>, Object> {

    private static final String SECRET_KEY = ""; // Set as your local secret key

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        System.out.println("--- GENERATOR START ---");

        try {
            // 1. Get Body
            String body = "";
            if (event != null && event.containsKey("body") && event.get("body") != null) {
                body = (String) event.get("body");
                if (event.get("isBase64Encoded") instanceof Boolean && (Boolean) event.get("isBase64Encoded")) {
                    body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
                }
            }
            System.out.println("Body: " + body);

            // 2. Parse (Manual Split)
            String email = "";
            String[] parts = body.split("\"");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("email") && i + 2 < parts.length) {
                    email = parts[i + 2];
                    break;
                }
            }

            if (email.isEmpty()) {
                System.out.println("Result: Fail (No Email)");
                return createResponse(400, "{\"error\": \"Missing email\"}");
            }
            System.out.println("Email: " + email);

            // 3. Generate Token using HMAC-SHA256
            // This ensures the token is inextricably linked to the email and our secret key
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(
                    SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
            String token = Base64.getEncoder().encodeToString(hmacBytes);

            System.out.println("Result: Success");

            // 4. Return Map
            return createResponse(200, "{\"token\":\"" + token + "\", \"email\":\"" + email + "\"}");

        } catch (Throwable t) {
            System.out.println("CRASH: " + t.toString());
            return createResponse(500, "{\"error\":\"" + t.getMessage() + "\"}");
        }
    }

    private Map<String, Object> createResponse(int statusCode, String jsonBody) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        response.put("body", jsonBody);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        response.put("headers", headers);
        response.put("isBase64Encoded", false);
        return response;
    }
}
