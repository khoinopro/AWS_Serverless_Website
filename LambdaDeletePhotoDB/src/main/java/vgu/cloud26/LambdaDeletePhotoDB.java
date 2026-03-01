package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaDeletePhotoDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Configuration
    private static final String RDS_INSTANCE_HOSTNAME = "";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "";
    private static final Region AWS_REGION = Region.AP_SOUTHEAST_1;
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT
            + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            context.getLogger().log("Warming event received. Exiting.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Warmed");
        }
        LambdaLogger logger = context.getLogger();

        try {
            // 1. Parse Input
            String requestBody = event.getBody();
            JSONObject bodyJSON = new JSONObject(requestBody);
            String originalKey = bodyJSON.getString("key");
            String requesterEmail = bodyJSON.optString("email", "");

            logger.log("Processing Delete DB for file: " + originalKey + " by " + requesterEmail);
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 2. Use raw filename (no hashing)
            String rawKey = originalKey;

            // 3. Connect and perform localized deletion with ownership check
            // We use 'S3Key AND Email' to guarantee users can only delete their own data
            Properties props = setMySqlConnectionProperties();
            try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {

                String sql = "DELETE FROM Photos WHERE S3Key = ? AND Email = ?";

                try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
                    st.setString(1, rawKey);
                    st.setString(2, requesterEmail);
                    int rowsAffected = st.executeUpdate();

                    if (rowsAffected > 0) {
                        return createResponse(200, new JSONObject().put("message", "Success: Row deleted").toString());
                    } else {
                        // Either file doesn't exist OR user doesn't own it
                        return createResponse(403,
                                new JSONObject()
                                        .put("error", "do not allow")
                                        .toString());
                    }
                }
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.toString());
            return createResponse(500, new JSONObject().put("error", "Error: " + ex.getMessage()).toString());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(message)
                .withIsBase64Encoded(false);
    }

    // --- Helper Methods ---
    private static Properties setMySqlConnectionProperties() {
        Properties mysqlConnectionProperties = new Properties();
        mysqlConnectionProperties.setProperty("useSSL", "true");
        mysqlConnectionProperties.setProperty("verifyServerCertificate", "false");
        mysqlConnectionProperties.setProperty("user", DB_USER);
        mysqlConnectionProperties.setProperty("password", generateAuthToken());
        return mysqlConnectionProperties;
    }

    private static String generateAuthToken() {
        RdsUtilities rdsUtilities = RdsUtilities.builder().region(AWS_REGION).build();
        return rdsUtilities.generateAuthenticationToken(
                GenerateAuthenticationTokenRequest.builder()
                        .hostname(RDS_INSTANCE_HOSTNAME)
                        .port(RDS_INSTANCE_PORT)
                        .username(DB_USER)
                        .region(AWS_REGION)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build());
    }
}
