package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent; // Import added
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaAddPhotoDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration
  private static final String RDS_INSTANCE_HOSTNAME = "";
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = ""; // User must match IAM policy
  private static final Region AWS_REGION = Region.AP_SOUTHEAST_1;
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      context.getLogger().log("Warming event received. Exiting.");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody("Warmed");
    }
    LambdaLogger logger = context.getLogger();

    // 1. Parse the body passed down from the Orchestrator
    String requestBody = event.getBody();
    JSONObject bodyJSON = new JSONObject(requestBody);

    // Extract required fields
    String originalFileName = bodyJSON.optString("key", "unknown.jpg");
    String description = bodyJSON.optString("description", "No description provided");
    String email = bodyJSON.optString("email", "unknown@example.com");

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
      logger.log("Processing DB insert for file: " + originalFileName + " by " + email);

      // 2. Use raw filename for S3Key column
      String rawKey = originalFileName;

      // 3. Securely connect using IAM tokens and insert the new metadata record
      Properties props = setMySqlConnectionProperties();
      try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {

        String sql = "INSERT INTO Photos (Description, S3Key, Email) VALUES (?, ?, ?)";

        try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
          st.setString(1, description); // Visual description provided by user
          st.setString(2, rawKey); // Filename used as the S3 link
          st.setString(3, email); // Uploader's email for ownership tracking
          st.executeUpdate();
          logger.log("Inserted row into RDS: " + rawKey);
        }
      }

      // 4. Return JSON Success (Wrapped in JSON for Orchestrator)
      return createResponse(200, new JSONObject().put("message", "Success: Row added to DB").toString());

    } catch (Exception ex) {
      logger.log("Error: " + ex.toString());
      return createResponse(500, new JSONObject().put("error", "Error adding to DB: " + ex.getMessage()).toString());
    }
  }

  // Helper to create standardized JSON response
  private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(message)
        .withIsBase64Encoded(false);
  }

  private static Properties setMySqlConnectionProperties() throws Exception {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("verifyServerCertificate", "false");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", generateAuthToken());
    return mysqlConnectionProperties;
  }

  private static String generateAuthToken() {
    RdsUtilities rdsUtilities = RdsUtilities.builder().region(AWS_REGION).build();
    String token = rdsUtilities.generateAuthenticationToken(
        GenerateAuthenticationTokenRequest.builder()
            .hostname(RDS_INSTANCE_HOSTNAME)
            .port(RDS_INSTANCE_PORT)
            .username(DB_USER)
            .region(AWS_REGION)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build());
    System.out.println("Generated IAM Token length: " + (token != null ? token.length() : "null"));
    return token;
  }

}
