package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaGetPhotosDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RDS_INSTANCE_HOSTNAME = "";

  private static final int RDS_INSTANCE_PORT = 3306;

  private static final String DB_USER = "";

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

    JSONArray items = new JSONArray();

    try {

      // 1. Establish connection to RDS MySQL using IAM authentication
      Class.forName("com.mysql.cj.jdbc.Driver");
      Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

      // 2. Query all records from the 'Photos' table
      PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");
      ResultSet rs = st.executeQuery();

      while (rs.next()) {

        JSONObject item = new JSONObject();

        item.put("ID", rs.getInt("ID"));

        item.put("Description", rs.getString("Description"));

        item.put("S3Key", rs.getString("S3Key"));

        item.put("Email", rs.getString("Email"));

        items.put(item);
      }

    } catch (ClassNotFoundException ex) {

      logger.log(ex.toString());

    } catch (Exception ex) {

      logger.log(ex.toString());
    }

    String jsonString = items.toString();

    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
    response.setStatusCode(200);
    response.setBody(jsonString);
    response.setIsBase64Encoded(false);
    response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));

    return response;
  }

  // Configures JDBC properties with an IAM-generated authentication token
  private static Properties setMySqlConnectionProperties() throws Exception {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    // Request a temporary token instead of a static password
    mysqlConnectionProperties.setProperty("password", generateAuthToken());
    return mysqlConnectionProperties;
  }

  // Generates a temporary RDS IAM authentication token
  private static String generateAuthToken() throws Exception {
    RdsUtilities rdsUtilities = RdsUtilities.builder().build();
    // Use the AWS SDK to generate a signed token valid for 15 minutes
    String authToken = rdsUtilities.generateAuthenticationToken(
        GenerateAuthenticationTokenRequest.builder()
            .hostname(RDS_INSTANCE_HOSTNAME)
            .port(RDS_INSTANCE_PORT)
            .username(DB_USER)
            .region(Region.AP_SOUTHEAST_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build());
    return authToken;
  }
}
